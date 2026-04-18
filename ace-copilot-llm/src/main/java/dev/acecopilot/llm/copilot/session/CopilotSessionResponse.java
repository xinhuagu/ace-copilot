package dev.acecopilot.llm.copilot.session;

import java.util.Map;
import java.util.Objects;

/**
 * Client responses to {@link CopilotSessionEvent}s. Sending one of these back
 * into the Copilot session completes the in-session exchange without counting
 * as a new external prompt — the core mechanism evaluated by issue #1.
 *
 * <p>The variants correspond one-to-one with
 * {@link CopilotSessionEvent.UserInputRequested} (answered via
 * {@code session.respondToUserInput()}) and
 * {@link CopilotSessionEvent.ElicitationRequested} (answered via
 * {@code session.respondToElicitation()}) in the Copilot SDK.
 */
public sealed interface CopilotSessionResponse
        permits CopilotSessionResponse.UserInputResponse,
                CopilotSessionResponse.ElicitationResponse {

    /** Must match the {@code requestId} of the originating event. */
    String requestId();

    /**
     * Free-form or choice response to a {@link CopilotSessionEvent.UserInputRequested}.
     *
     * @param requestId correlation id from the originating event
     * @param response  the user's answer (one of the offered {@code choices} or free text)
     */
    record UserInputResponse(String requestId, String response) implements CopilotSessionResponse {
        public UserInputResponse {
            requestId = Objects.requireNonNull(requestId, "requestId");
            response = Objects.requireNonNull(response, "response");
        }
    }

    /**
     * Structured response to a {@link CopilotSessionEvent.ElicitationRequested}.
     *
     * @param requestId correlation id from the originating event
     * @param action    user's disposition — accept, decline, or cancel
     * @param content   populated data conforming to the requested schema when
     *                  {@code action == ACCEPT}; empty map otherwise
     */
    record ElicitationResponse(
            String requestId,
            ElicitationAction action,
            Map<String, Object> content
    ) implements CopilotSessionResponse {
        public ElicitationResponse {
            requestId = Objects.requireNonNull(requestId, "requestId");
            action = Objects.requireNonNull(action, "action");
            content = content != null ? Map.copyOf(content) : Map.of();
        }
    }

    /** User's disposition on an elicitation request. */
    enum ElicitationAction {
        /** User provided the data; {@code content} is populated. */
        ACCEPT,
        /** User explicitly refused to answer. */
        DECLINE,
        /** User cancelled the dialog without deciding. */
        CANCEL
    }
}
