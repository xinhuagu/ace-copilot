package dev.acecopilot.llm.copilot.session;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * Session-internal UI events emitted by the GitHub Copilot SDK / CLI ACP
 * runtime when the agent needs to ask the user something from inside an
 * existing session, instead of requiring a new external prompt.
 *
 * <p>Modeled after the {@code user_input.requested} and
 * {@code elicitation.requested} events documented in the Copilot SDK
 * ({@code github/copilot-sdk docs/features/streaming-events.md}). These are
 * distinct from {@link dev.acecopilot.core.llm.StreamEvent} which represents
 * LLM content deltas — session events sit at a different protocol layer.
 *
 * <p>Each event carries a {@link #requestId()} that must be echoed back via
 * the corresponding {@link CopilotSessionResponse} to complete the exchange.
 */
public sealed interface CopilotSessionEvent
        permits CopilotSessionEvent.UserInputRequested,
                CopilotSessionEvent.ElicitationRequested {

    /** Correlation identifier used to route the response back to the waiting agent. */
    String requestId();

    /**
     * The agent is asking the user a free-form or choice-based question.
     *
     * @param requestId      correlation id — echo via {@link CopilotSessionResponse.UserInputResponse}
     * @param question       prompt to present to the user
     * @param choices        predefined selectable answers; empty list if not provided
     * @param allowFreeform  whether arbitrary text input is accepted alongside any choices
     */
    record UserInputRequested(
            String requestId,
            String question,
            List<String> choices,
            boolean allowFreeform
    ) implements CopilotSessionEvent {
        public UserInputRequested {
            requestId = Objects.requireNonNull(requestId, "requestId");
            question = Objects.requireNonNull(question, "question");
            choices = choices != null ? List.copyOf(choices) : List.of();
        }
    }

    /**
     * The agent needs structured form input (MCP elicitation protocol).
     *
     * @param requestId          correlation id — echo via {@link CopilotSessionResponse.ElicitationResponse}
     * @param message            description of what information is needed
     * @param mode               {@link ElicitationMode#FORM} for schema-driven input,
     *                           {@link ElicitationMode#URL} for browser-based flows
     * @param requestedSchema    JSON Schema describing the form fields (required when mode is FORM)
     * @param elicitationSource  origin of the request (e.g. MCP server name); may be {@code null}
     * @param url                browser URL when {@code mode} is {@link ElicitationMode#URL}; else {@code null}
     */
    record ElicitationRequested(
            String requestId,
            String message,
            ElicitationMode mode,
            JsonNode requestedSchema,
            String elicitationSource,
            String url
    ) implements CopilotSessionEvent {
        public ElicitationRequested {
            requestId = Objects.requireNonNull(requestId, "requestId");
            message = Objects.requireNonNull(message, "message");
            mode = mode != null ? mode : ElicitationMode.FORM;
        }
    }

    /** Elicitation presentation style. */
    enum ElicitationMode {
        /** Agent expects structured data conforming to {@code requestedSchema}. */
        FORM,
        /** Agent expects the user to complete a flow at an external URL. */
        URL
    }
}
