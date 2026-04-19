package dev.acecopilot.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Reads the streaming response for a single agent task on a virtual thread.
 *
 * <p>Replaces the inline {@code while (!done)} loop that was previously in
 * {@code TerminalRepl.processInput()}. Dispatches events to an {@link OutputSink}
 * and routes permission requests through a {@link PermissionBridge}.
 */
public final class TaskStreamReader implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskStreamReader.class);
    /**
     * Slightly shorter than the daemon-side 120s permission timeout so the
     * client can resume reading the stream before the server emits its timeout result.
     */
    static final long CLIENT_PERMISSION_WAIT_TIMEOUT_MS = 115_000L;

    private final TaskHandle handle;
    private final DaemonConnection connection;
    private final String sessionId;
    private final String fullPrompt;
    private final PermissionBridge permissionBridge;
    private final UserInputBridge userInputBridge;
    private final Consumer<TaskHandle> onComplete;

    /**
     * Phase 1-compatible constructor without {@link UserInputBridge}; any
     * {@code user_input.requested} notification gets auto-cancelled so the
     * sendAndWait does not hang. New code should pass a bridge so the REPL
     * can actually answer the question (Phase 3, #5).
     */
    public TaskStreamReader(TaskHandle handle, DaemonConnection connection,
                            String sessionId, String fullPrompt,
                            PermissionBridge permissionBridge,
                            Consumer<TaskHandle> onComplete) {
        this(handle, connection, sessionId, fullPrompt, permissionBridge, null, onComplete);
    }

    public TaskStreamReader(TaskHandle handle, DaemonConnection connection,
                            String sessionId, String fullPrompt,
                            PermissionBridge permissionBridge,
                            UserInputBridge userInputBridge,
                            Consumer<TaskHandle> onComplete) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.fullPrompt = Objects.requireNonNull(fullPrompt, "fullPrompt");
        this.permissionBridge = Objects.requireNonNull(permissionBridge, "permissionBridge");
        this.userInputBridge = userInputBridge;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        try {
            sendPromptRequest();
            readStreamLoop();
        } catch (IOException e) {
            if (!handle.cancelled().get()) {
                log.error("I/O error in task {}: {}", handle.taskId(), e.getMessage(), e);
                handle.setState(TaskHandle.TaskState.FAILED);
                handle.outputSink().onStreamError("Connection error: " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error in task {}: {}", handle.taskId(), e.getMessage(), e);
            handle.setState(TaskHandle.TaskState.FAILED);
            handle.outputSink().onStreamError("Unexpected error: " + e.getMessage());
        } finally {
            if (handle.isRunning()) {
                handle.setState(TaskHandle.TaskState.FAILED);
            }
            if (onComplete != null) {
                onComplete.accept(handle);
            }
        }
    }

    private void sendPromptRequest() throws IOException {
        long id = connection.nextRequestId();
        ObjectNode request = connection.objectMapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", "agent.prompt");
        ObjectNode params = connection.objectMapper().createObjectNode();
        params.put("sessionId", sessionId);
        params.put("prompt", fullPrompt);
        request.set("params", params);
        request.put("id", id);
        connection.writeLine(connection.objectMapper().writeValueAsString(request));
    }

    private void readStreamLoop() throws IOException {
        boolean done = false;
        while (!done) {
            String responseLine = connection.readLine();
            if (responseLine == null) {
                handle.outputSink().onConnectionClosed();
                handle.setState(TaskHandle.TaskState.FAILED);
                return;
            }

            JsonNode message = connection.objectMapper().readTree(responseLine);

            if (message.has("id") && !message.get("id").isNull()) {
                // Final JSON-RPC response
                done = true;
                handleFinalResponse(message);
            } else if (message.has("method")) {
                handleNotification(message);
            } else {
                log.debug("Task {}: ignoring unrecognized message", handle.taskId());
            }
        }
    }

    private void handleFinalResponse(JsonNode message) {
        boolean hasError = message.has("error") && !message.get("error").isNull();
        handle.setResult(message);
        handle.markActivity(hasError ? "failed" : "completed");

        if (handle.cancelled().get()) {
            handle.setState(TaskHandle.TaskState.CANCELLED);
        } else if (hasError) {
            handle.setState(TaskHandle.TaskState.FAILED);
        } else {
            handle.setState(TaskHandle.TaskState.COMPLETED);
        }

        handle.outputSink().onTurnComplete(message, hasError);
    }

    private void handleNotification(JsonNode message) {
        String method = message.get("method").asText();
        JsonNode params = message.get("params");
        // Read sink once per notification for consistency within a single event
        var sink = handle.outputSink();

        switch (method) {
            case "stream.thinking" -> {
                if (params != null && params.has("delta")) {
                    handle.markActivity("thinking");
                    sink.onThinkingDelta(params.get("delta").asText());
                }
            }
            case "stream.text" -> {
                if (params != null && params.has("delta")) {
                    handle.markActivity("responding");
                    sink.onTextDelta(params.get("delta").asText());
                }
            }
            case "stream.tool_use" -> {
                if (params != null) {
                    String toolId = params.path("id").asText("");
                    String toolName = params.path("name").asText("unknown");
                    String summary = params.path("summary").asText("");
                    handle.appendToolEvent(toolName, "start", false, 0, summary);
                    handle.markActivity("tool:" + toolName);
                    sink.onToolUse(toolId, toolName, summary);
                }
            }
            case "stream.tool_completed" -> {
                if (params != null) {
                    String toolId = params.path("id").asText("");
                    String toolName = params.path("name").asText("unknown");
                    long durationMs = params.path("durationMs").asLong(0);
                    boolean isError = params.path("isError").asBoolean(false);
                    String error = params.path("error").asText("");
                    handle.appendToolEvent(toolName, "done", isError, durationMs, isError ? error : "");
                    handle.markActivity("tool done");
                    sink.onToolCompleted(toolId, toolName, durationMs, isError, error);
                }
            }
            case "permission.request" -> handlePermissionRequest(params);
            case "stream.error" -> {
                if (params != null && params.has("error")) {
                    handle.appendToolEvent("stream", "error", true, 0, params.get("error").asText());
                    handle.markActivity("stream error");
                    sink.onStreamError(params.get("error").asText());
                }
            }
            case "stream.warning" -> {
                if (params != null) {
                    String msg = params.path("message").asText("");
                    if (!msg.isBlank()) {
                        handle.appendToolEvent("stream", "warn", false, 0, msg);
                        handle.markActivity("warning");
                        sink.onWarning(msg);
                    }
                }
            }
            case "user_input.requested" -> handleUserInputRequested(params);
            case "stream.heartbeat" -> {
                String phase = "active";
                if (params != null) {
                    String raw = params.path("phase").asText("active");
                    phase = (raw == null || raw.isBlank()) ? "active" : raw;
                }
                handle.markActivity("heartbeat:" + phase);
            }
            case "stream.cancelled" -> {
                handle.markActivity("cancelled");
                sink.onStreamCancelled();
            }
            case "stream.subagent.start" -> {
                String agentType = params != null ? params.path("agentType").asText("sub-agent") : "sub-agent";
                handle.markActivity("sub-agent:" + agentType);
                sink.onSubAgentStart(params);
            }
            case "stream.subagent.end" -> {
                handle.markActivity("sub-agent done");
                sink.onSubAgentEnd(params);
            }
            case "stream.plan_created" -> {
                handle.markActivity("plan created");
                sink.onPlanCreated(params);
            }
            case "stream.plan_step_started" -> {
                String stepName = params != null ? params.path("stepName").asText("plan step") : "plan step";
                handle.markActivity("plan:" + stepName);
                sink.onPlanStepStarted(params);
            }
            case "stream.plan_step_completed" -> {
                handle.markActivity("plan step done");
                sink.onPlanStepCompleted(params);
            }
            case "stream.plan_completed" -> {
                handle.markActivity("plan done");
                sink.onPlanCompleted(params);
            }
            case "stream.usage" -> {
                if (params != null) {
                    long inputTokens = params.path("inputTokens").asLong(0);
                    handle.setLiveInputTokens(inputTokens);
                    handle.markActivity("streaming");
                    int ctxWindow = handle.contextWindow();
                    if (ctxWindow > 0) {
                        sink.onUsageUpdate(inputTokens, ctxWindow);
                    }
                }
            }
            case "stream.compaction" -> {
                handle.markActivity("compacting");
                sink.onCompaction(params);
            }
            case "stream.budget_exhausted" -> {
                String reason = params != null ? params.path("reason").asText("unknown") : "unknown";
                handle.markActivity("budget exhausted:" + reason);
                sink.onBudgetExhausted(params);
            }
            default -> log.debug("Task {}: ignoring notification: {}", handle.taskId(), method);
        }
    }

    /**
     * Routes a Copilot clarification (Phase 3, #5) through the
     * {@link UserInputBridge} so the REPL collects the user's answer and
     * this thread sends it back as a {@code user_input.response}
     * notification. The whole exchange stays inside the in-flight
     * {@code sendAndWait} — cost stays at 0 premium requests.
     *
     * <p>When no bridge is registered (pre-#5 wiring paths, test harnesses),
     * falls back to an immediate cancel so the sendAndWait unwinds with a
     * visible warning rather than deadlocking.
     */
    private void handleUserInputRequested(JsonNode params) {
        if (params == null) return;
        String requestId = params.path("requestId").asText("");
        if (requestId.isEmpty()) {
            log.warn("Task {}: user_input.requested missing requestId — cannot route", handle.taskId());
            return;
        }
        String question = params.path("question").asText("");
        boolean allowFreeform = params.path("allowFreeform").asBoolean(true);
        java.util.List<String> choices = new java.util.ArrayList<>();
        if (params.has("choices") && params.get("choices").isArray()) {
            params.get("choices").forEach(c -> { if (c != null && !c.isNull()) choices.add(c.asText()); });
        }

        if (userInputBridge == null) {
            autoCancelUserInput(requestId, question,
                    "This CLI does not have a clarification UX wired (UserInputBridge missing)");
            return;
        }

        handle.markActivity("clarification pending");
        var sink = handle.outputSink();
        sink.onUserInputRequest(requestId, question, choices, allowFreeform);

        var req = new UserInputBridge.UserInputRequest(
                handle.taskId(), requestId, question, choices, allowFreeform);
        UserInputBridge.UserInputAnswer answer;
        try {
            // No timeout here — Phase 3 c5 adds a configurable one.
            answer = userInputBridge.requestInput(req, 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.onUserInputResolved(requestId, "interrupted");
            autoCancelUserInput(requestId, question, "interrupted while waiting for user");
            return;
        } catch (java.util.concurrent.TimeoutException e) {
            sink.onUserInputResolved(requestId, "timeout");
            autoCancelUserInput(requestId, question, "timed out waiting for user");
            return;
        }

        String resolvedReason = answer.cancel() ? "cancelled" : null;
        sink.onUserInputResolved(requestId, resolvedReason);

        try {
            ObjectNode responseParams = connection.objectMapper().createObjectNode();
            responseParams.put("requestId", requestId);
            responseParams.put("cancel", answer.cancel());
            responseParams.put("answer", answer.answer() != null ? answer.answer() : "");
            responseParams.put("wasFreeform", answer.wasFreeform());
            connection.sendNotification("user_input.response", responseParams);
        } catch (IOException e) {
            log.error("Task {}: failed to send user_input.response: {}", handle.taskId(), e.getMessage());
        }
    }

    private void autoCancelUserInput(String requestId, String question, String reasonMsg) {
        String prefix = question.isBlank()
                ? "Copilot agent asked a clarifying question"
                : "Copilot agent asked: " + question;
        String warning = prefix + ". " + reasonMsg + " — cancelling so the session does not hang.";
        handle.appendToolEvent("stream", "user_input_auto_cancel", false, 0, warning);
        handle.markActivity("user_input auto-cancelled");
        handle.outputSink().onWarning(warning);
        try {
            ObjectNode responseParams = connection.objectMapper().createObjectNode();
            responseParams.put("requestId", requestId);
            responseParams.put("cancel", true);
            responseParams.put("answer", "");
            responseParams.put("wasFreeform", true);
            connection.sendNotification("user_input.response", responseParams);
        } catch (IOException e) {
            log.error("Task {}: failed to send user_input.response cancel: {}",
                    handle.taskId(), e.getMessage());
        }
    }

    private void handlePermissionRequest(JsonNode params) {
        if (params == null) return;

        String tool = params.path("tool").asText("unknown");
        String description = params.path("description").asText("");
        String requestId = params.path("requestId").asText("");

        if (requestId.isEmpty()) {
            log.warn("Task {}: permission.request missing requestId, skipping", handle.taskId());
            return;
        }

        var request = new PermissionBridge.PermissionRequest(
                handle.taskId(), tool, description, requestId);
        handle.markWaitingPermission(description);

        try {
            var answer = permissionBridge.requestPermission(
                    request, CLIENT_PERMISSION_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Send permission response back to daemon on this task's connection
            ObjectNode responseParams = connection.objectMapper().createObjectNode();
            responseParams.put("requestId", requestId);
            responseParams.put("approved", answer.approved());
            responseParams.put("remember", answer.remember());
            connection.sendNotification("permission.response", responseParams);
            handle.clearWaitingPermission();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Task {}: permission request interrupted", handle.taskId());
            handle.markActivity("permission interrupted");
        } catch (TimeoutException e) {
            log.info("Task {}: local permission wait timed out for requestId={} after {}ms",
                    handle.taskId(), requestId, CLIENT_PERMISSION_WAIT_TIMEOUT_MS);
            handle.markActivity("permission wait expired");
        } catch (IOException e) {
            log.error("Task {}: failed to send permission response: {}",
                    handle.taskId(), e.getMessage());
            handle.markActivity("permission response failed");
        }
    }
}
