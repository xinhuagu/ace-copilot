package dev.acecopilot.llm.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client for the ace-copilot Copilot SDK sidecar ({@code ace-copilot-sidecar/sidecar.mjs}).
 *
 * <p>Launches the Node subprocess, speaks LSP-framed JSON-RPC over stdio, and
 * exposes a single agent turn as a Java method. Each {@link #sendAndWait} call
 * maps to one Copilot SDK {@code session.sendAndWait} — billed as exactly one
 * {@code premium_interactions.usedRequests} regardless of how many internal
 * ReAct iterations or tool calls happen inside it.
 *
 * <p>Not a {@code LlmClient} — the semantics are different. The SDK agent runs
 * its own internal ReAct loop inside one call, whereas {@code LlmClient} is
 * request-response at the LLM layer.
 *
 * <p>This is the Phase 1 skeleton (issue #3): no tool registration, no
 * {@code respondToUserInput} routing, no {@code LlmClientFactory} wiring.
 * It is callable from Java but not yet reached by a user prompt through the
 * daemon agent loop.
 */
public final class CopilotAcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CopilotAcpClient.class);

    /** Notification emitted by the sidecar during {@code session.sendAndWait}. */
    public record NotificationEvent(String method, JsonNode params) {}

    /**
     * Handles incoming RPC requests from the sidecar (sidecar → Java). Used
     * by Phase 2 (#4) for {@code tool.invoke} and {@code permission.request}.
     *
     * <p>Return a {@link JsonNode} to send back as {@code result}; throw to
     * send an {@code error}. Handlers run on a dedicated executor so they
     * don't block the reader thread.
     */
    @FunctionalInterface
    public interface RequestHandler {
        JsonNode handle(String method, JsonNode params) throws Exception;
    }

    /** Single {@code assistant.usage} sample. */
    public record UsageSnapshot(
            String model,
            Long inputTokens,
            Long outputTokens,
            Double cost,
            String initiator,
            Long premiumUsed,
            Long premiumLimit
    ) {}

    /** Final result of one {@link #sendAndWait}. */
    public record SendResult(
            String content,
            String stopReason,
            UsageSnapshot firstUsage,
            UsageSnapshot lastUsage,
            int usageEventCount
    ) {
        /** Change in {@code premium_interactions.usedRequests} across this turn. */
        public long premiumDelta() {
            if (firstUsage == null || lastUsage == null) return -1;
            if (firstUsage.premiumUsed == null || lastUsage.premiumUsed == null) return -1;
            return lastUsage.premiumUsed - firstUsage.premiumUsed;
        }
    }

    private final Process sidecar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Thread readerThread;
    private final ExecutorService requestExecutor;
    private volatile Consumer<NotificationEvent> notificationHandler;
    private volatile RequestHandler requestHandler;

    /**
     * Tool definition registered with the SDK via {@code defineTool}. The
     * name must match the tool ID our {@code ToolRegistry} dispatches on;
     * the sidecar's {@code onPreToolUse} allowlist uses the same name.
     *
     * @param name        tool identifier (e.g. {@code read_file})
     * @param description human-readable description passed to the SDK agent
     * @param inputSchema JSON Schema describing {@code arguments}; rendered as
     *                    {@code parameters} on the SDK side. {@code null}
     *                    defaults to {@code {"type":"object","properties":{}}}.
     */
    public record ToolDescriptor(String name, String description, JsonNode inputSchema) {
        public ToolDescriptor {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Convenience constructor with no tools (Phase 1 behavior, SDK built-ins). */
    public CopilotAcpClient(Path sidecarDir, String githubToken) throws IOException {
        this(sidecarDir, githubToken, List.of());
    }

    /**
     * Launches the sidecar located at {@code sidecarDir/sidecar.mjs},
     * completes the {@code initialize} handshake, and registers
     * ace-copilot's custom tools with the SDK (Phase 2, issue #4).
     *
     * <p>When {@code tools} is non-empty, the sidecar also installs an
     * {@code onPreToolUse} hook that denies any tool outside the registered
     * set — this blocks the SDK's built-in filesystem/shell tools so
     * ace-copilot is the only tool surface and {@code PermissionManager}
     * stays authoritative.
     *
     * @param sidecarDir  directory containing {@code sidecar.mjs} and installed
     *                    {@code node_modules} (typically {@code ace-copilot-sidecar/})
     * @param githubToken GitHub token passed to the SDK; may be {@code null} to
     *                    let the SDK discover credentials from the logged-in
     *                    {@code gh} user. Resolve upstream via
     *                    {@code CopilotTokenProvider}.
     * @param tools       ace-copilot tool descriptors to register via
     *                    {@code defineTool}. Tool invocations arrive as
     *                    {@code tool.invoke} RPC via {@link RequestHandler}.
     *                    Empty list means Phase 1 behavior (SDK built-ins).
     */
    public CopilotAcpClient(Path sidecarDir, String githubToken, List<ToolDescriptor> tools) throws IOException {
        Objects.requireNonNull(sidecarDir, "sidecarDir");
        var script = sidecarDir.resolve("sidecar.mjs").toAbsolutePath();
        var pb = new ProcessBuilder("node", script.toString())
                .directory(sidecarDir.toFile())
                .redirectErrorStream(false);
        log.info("Launching Copilot sidecar: {}", script);
        try {
            this.sidecar = pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "Failed to launch Copilot sidecar: " + e.getMessage()
                    + ". copilotRuntime=session requires Node.js on PATH — install Node.js 20+"
                    + " (https://nodejs.org/) and restart the daemon, or set copilotRuntime back to 'chat'.",
                    e);
        }

        this.requestExecutor = Executors.newCachedThreadPool(r -> {
            var t = new Thread(r, "copilot-sidecar-request");
            t.setDaemon(true);
            return t;
        });

        this.readerThread = new Thread(this::readLoop, "copilot-sidecar-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        var errThread = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(
                    sidecar.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.info("sidecar: {}", line);
                }
            } catch (IOException e) {
                log.debug("sidecar stderr closed", e);
            }
        }, "copilot-sidecar-stderr");
        errThread.setDaemon(true);
        errThread.start();

        try {
            ObjectNode initParams = mapper.createObjectNode();
            if (githubToken != null && !githubToken.isBlank()) {
                initParams.put("githubToken", githubToken);
            }
            if (tools != null && !tools.isEmpty()) {
                var arr = initParams.putArray("tools");
                for (var t : tools) {
                    var node = arr.addObject();
                    node.put("name", t.name());
                    if (t.description() != null) node.put("description", t.description());
                    if (t.inputSchema() != null) node.set("parameters", t.inputSchema());
                }
            }
            JsonNode init = request("initialize", initParams).get(30, TimeUnit.SECONDS);
            int toolsRegistered = init.path("toolsRegistered").asInt(0);
            log.info("Sidecar initialized: protocolVersion={}, toolsRegistered={}",
                    init.path("protocolVersion").asText(), toolsRegistered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy();
            throw new IOException("sidecar initialize interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            destroy();
            throw new IOException("sidecar initialize failed", e);
        }
    }

    /**
     * Issues a single agent turn to the Copilot SDK and blocks until it
     * completes. Text deltas and usage snapshots emitted during the turn are
     * forwarded to the supplied consumers.
     *
     * @param model     model identifier to pass to {@code createSession}
     * @param prompt    user prompt text
     * @param textDelta optional consumer invoked for each text delta chunk;
     *                  may be {@code null} to drop text
     * @return final assistant content, stop reason, and first/last usage snapshots
     */
    public SendResult sendAndWait(String model, String prompt, Consumer<String> textDelta) throws IOException {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");

        var accum = new Object() {
            UsageSnapshot firstUsage;
            UsageSnapshot lastUsage;
            int usageCount;
        };

        this.notificationHandler = (n) -> {
            switch (n.method()) {
                case "session/text" -> {
                    var d = n.params().path("delta").asText(null);
                    if (d != null && textDelta != null) {
                        try { textDelta.accept(d); }
                        catch (RuntimeException e) { log.warn("textDelta consumer threw", e); }
                    }
                }
                case "session/usage" -> {
                    var u = readUsage(n.params());
                    if (accum.firstUsage == null) accum.firstUsage = u;
                    accum.lastUsage = u;
                    accum.usageCount++;
                }
                default -> log.debug("ignored notification: {}", n.method());
            }
        };

        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("model", model);
            params.put("prompt", prompt);
            JsonNode resp = request("session.sendAndWait", params).get(10, TimeUnit.MINUTES);
            String content = resp.path("content").isNull() ? null : resp.path("content").asText(null);
            String stopReason = resp.path("stopReason").isNull() ? null : resp.path("stopReason").asText(null);
            return new SendResult(content, stopReason, accum.firstUsage, accum.lastUsage, accum.usageCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("sendAndWait interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("sendAndWait failed: " + e.getMessage(), e);
        } finally {
            this.notificationHandler = null;
        }
    }

    private UsageSnapshot readUsage(JsonNode p) {
        return new UsageSnapshot(
                optText(p, "model"),
                optLong(p, "inputTokens"),
                optLong(p, "outputTokens"),
                optDouble(p, "cost"),
                optText(p, "initiator"),
                optLong(p, "premiumUsed"),
                optLong(p, "premiumLimit")
        );
    }

    private static String optText(JsonNode p, String f) {
        var n = p.get(f);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static Long optLong(JsonNode p, String f) {
        var n = p.get(f);
        return (n == null || n.isNull()) ? null : n.asLong();
    }

    private static Double optDouble(JsonNode p, String f) {
        var n = p.get(f);
        return (n == null || n.isNull()) ? null : n.asDouble();
    }

    private CompletableFuture<JsonNode> request(String method, Object params) {
        long id = nextId.getAndIncrement();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            if (params != null) req.set("params", mapper.valueToTree(params));
            writeMessage(req);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    private synchronized void writeMessage(JsonNode msg) throws IOException {
        byte[] body = mapper.writeValueAsBytes(msg);
        OutputStream out = sidecar.getOutputStream();
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void readLoop() {
        try (var in = new BufferedInputStream(sidecar.getInputStream())) {
            while (true) {
                int contentLength = readHeader(in);
                if (contentLength < 0) break;
                byte[] body = in.readNBytes(contentLength);
                if (body.length < contentLength) {
                    log.warn("truncated body: got {} of {} bytes", body.length, contentLength);
                    break;
                }
                JsonNode msg = mapper.readTree(body);
                handleIncoming(msg);
            }
        } catch (IOException e) {
            log.debug("sidecar reader terminated: {}", e.getMessage());
        } finally {
            var closeErr = new IOException("sidecar closed");
            pending.values().forEach(f -> f.completeExceptionally(closeErr));
            pending.clear();
        }
    }

    private int readHeader(InputStream in) throws IOException {
        var line = new StringBuilder();
        int contentLength = -1;
        while (true) {
            int ch;
            line.setLength(0);
            while ((ch = in.read()) != -1 && ch != '\r') {
                line.append((char) ch);
            }
            if (ch == -1) return -1;
            int nl = in.read();
            if (nl != '\n') throw new IOException("malformed header framing");
            if (line.length() == 0) break;
            String h = line.toString();
            int colon = h.indexOf(':');
            if (colon > 0 && "Content-Length".equalsIgnoreCase(h.substring(0, colon).trim())) {
                try {
                    contentLength = Integer.parseInt(h.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("invalid Content-Length: " + h, e);
                }
            }
        }
        return contentLength;
    }

    private void handleIncoming(JsonNode msg) {
        boolean hasId = msg.has("id") && !msg.get("id").isNull();
        boolean hasMethod = msg.has("method");
        if (hasId && (msg.has("result") || msg.has("error"))) {
            long id = msg.get("id").asLong();
            var fut = pending.remove(id);
            if (fut == null) {
                log.warn("no pending request for response id={}", id);
                return;
            }
            if (msg.has("error")) {
                fut.completeExceptionally(new RuntimeException(
                        "sidecar error: " + msg.get("error").toString()));
            } else {
                fut.complete(msg.get("result"));
            }
        } else if (hasId && hasMethod) {
            // Incoming RPC request from sidecar → dispatch to handler, send response.
            dispatchIncomingRequest(msg);
        } else if (hasMethod) {
            // Notification (no id).
            var h = notificationHandler;
            if (h != null) {
                try {
                    h.accept(new NotificationEvent(
                            msg.get("method").asText(),
                            msg.has("params") ? msg.get("params") : mapper.createObjectNode()));
                } catch (RuntimeException e) {
                    log.warn("notification handler threw", e);
                }
            }
        }
    }

    private void dispatchIncomingRequest(JsonNode msg) {
        long id = msg.get("id").asLong();
        String method = msg.get("method").asText();
        JsonNode params = msg.has("params") ? msg.get("params") : mapper.createObjectNode();
        RequestHandler h = requestHandler;
        if (h == null) {
            sendErrorResponse(id, -32601, "no RequestHandler registered for method: " + method);
            return;
        }
        requestExecutor.submit(() -> {
            try {
                JsonNode result = h.handle(method, params);
                sendResultResponse(id, result != null ? result : mapper.nullNode());
            } catch (Exception e) {
                log.debug("RequestHandler threw for method {}: {}", method, e.getMessage());
                sendErrorResponse(id, -32000, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    private void sendResultResponse(long id, JsonNode result) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.set("result", result);
        try {
            writeMessage(resp);
        } catch (IOException e) {
            log.warn("failed to send response for id={}: {}", id, e.getMessage());
        }
    }

    private void sendErrorResponse(long id, int code, String message) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        var err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message != null ? message : "(no message)");
        try {
            writeMessage(resp);
        } catch (IOException e) {
            log.warn("failed to send error response for id={}: {}", id, e.getMessage());
        }
    }

    /** Registers a handler for incoming RPC requests from the sidecar. */
    public void setRequestHandler(RequestHandler handler) {
        this.requestHandler = handler;
    }

    @Override
    public void close() {
        try {
            request("shutdown", null).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("shutdown request did not complete cleanly: {}", e.getMessage());
        }
        destroy();
    }

    private void destroy() {
        requestExecutor.shutdownNow();
        if (sidecar.isAlive()) {
            sidecar.destroy();
            try {
                if (!sidecar.waitFor(3, TimeUnit.SECONDS)) {
                    sidecar.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sidecar.destroyForcibly();
            }
        }
    }
}
