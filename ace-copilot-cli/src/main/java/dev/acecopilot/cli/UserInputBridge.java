package dev.acecopilot.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 3 (#5) sibling of {@link PermissionBridge} for the Copilot
 * session runtime's clarifying questions.
 *
 * <p>When the SDK agent fires {@code ask_user}, the sidecar round-trips
 * through the daemon, which emits a {@code user_input.requested}
 * notification on the task connection. {@link TaskStreamReader} surfaces
 * it here by calling {@link #requestInput}, blocking until the REPL
 * thread resolves the answer via {@link #submitAnswer} (or cancels with
 * {@link #cancel}).
 *
 * <p>Only one question is expected in-flight per session — the SDK
 * serializes {@code ask_user} calls — so keyed lookup by {@code requestId}
 * is defensive rather than structural.
 */
public final class UserInputBridge {

    private static final Logger log = LoggerFactory.getLogger(UserInputBridge.class);

    private final BlockingQueue<UserInputRequest> pending = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, CompletableFuture<UserInputAnswer>> futures =
            new ConcurrentHashMap<>();
    private volatile RequestListener requestListener;

    /** Notified on the publishing thread when a new question arrives. */
    @FunctionalInterface
    public interface RequestListener {
        void onUserInputRequested(UserInputRequest request);
    }

    public void setRequestListener(RequestListener listener) {
        this.requestListener = listener;
    }

    /**
     * Called from {@link TaskStreamReader} when a
     * {@code user_input.requested} notification arrives. Blocks until the
     * REPL resolves or cancels the answer.
     *
     * @throws InterruptedException if the calling thread is interrupted
     * @throws TimeoutException     if the timeout elapses with no answer
     */
    public UserInputAnswer requestInput(UserInputRequest request, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        var future = new CompletableFuture<UserInputAnswer>();
        var previous = futures.putIfAbsent(request.requestId(), future);
        if (previous != null) {
            log.warn("Duplicate user_input requestId {}; reusing existing future", request.requestId());
            future = previous;
        }
        pending.offer(request);
        var listener = requestListener;
        if (listener != null) {
            try { listener.onUserInputRequested(request); }
            catch (RuntimeException e) { log.warn("UserInputBridge listener threw", e); }
        }
        try {
            return unit == null
                    ? future.get()
                    : future.get(timeout, unit);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            futures.remove(request.requestId());
            pending.remove(request);
        }
    }

    /** Resolves a waiting {@link #requestInput} with an answer. */
    public boolean submitAnswer(String requestId, UserInputAnswer answer) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(answer, "answer");
        var future = futures.remove(requestId);
        if (future == null) return false;
        return future.complete(answer);
    }

    /** Cancels a waiting {@link #requestInput}. The answer is {@code {cancel: true}}. */
    public boolean cancel(String requestId) {
        return submitAnswer(requestId, UserInputAnswer.cancelled());
    }

    /** Retrieves the next pending request without blocking; {@code null} if none. */
    public UserInputRequest pollPending() {
        return pending.poll();
    }

    /** Peeks at the currently-pending request without removing it; {@code null} if none. */
    public UserInputRequest peekPending() {
        return pending.peek();
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Payload delivered to the REPL. */
    public record UserInputRequest(
            String taskId,
            String requestId,
            String question,
            List<String> choices,
            boolean allowFreeform) {
        public UserInputRequest {
            Objects.requireNonNull(requestId, "requestId");
            choices = choices != null ? List.copyOf(choices) : List.of();
        }
    }

    /** The REPL's resolution: an answer, or a cancel that declines the clarification. */
    public record UserInputAnswer(String answer, boolean wasFreeform, boolean cancel) {
        public static UserInputAnswer freeform(String text) {
            return new UserInputAnswer(text, true, false);
        }

        public static UserInputAnswer choice(String text) {
            return new UserInputAnswer(text, false, false);
        }

        public static UserInputAnswer cancelled() {
            return new UserInputAnswer("", true, true);
        }
    }
}
