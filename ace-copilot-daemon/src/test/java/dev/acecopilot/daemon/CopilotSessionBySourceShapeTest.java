package dev.acecopilot.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.acecopilot.core.llm.RequestAttribution;
import dev.acecopilot.core.llm.RequestSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 (#6) PR A safety net for the #17 reviewer fix.
 *
 * <p>The Copilot session path must emit per-source attribution on the
 * same wire shape the chat path uses — field name
 * {@code llmRequestsBySource} and lowercase {@link RequestSource} name
 * keys — so the CLI's existing parser (which looks only at that field)
 * sees session turns. Session-only changes that accidentally drift the
 * shape away from this contract are a silent regression: the TUI would
 * show "0 LLM requests" for turns that actually fired a sendAndWait.
 *
 * <p>This test pins the helper that produces the shape. If a future
 * refactor moves to a new field name or changes key casing, the test
 * fails before the TUI does.
 */
class CopilotSessionBySourceShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sessionPathMainTurnProducesChatCompatibleShape() {
        var usage = mapper.createObjectNode();
        var attribution = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN)
                .build();

        StreamingAgentHandler.writeLlmRequestsBySource(mapper, usage, attribution);

        var bySource = usage.path("llmRequestsBySource");
        assertThat(bySource.isObject())
                .as("field name must be llmRequestsBySource (the chat-path contract the CLI parses)")
                .isTrue();
        assertThat(bySource.path("main_turn").asInt(-1))
                .as("main_turn key is lowercase, not MAIN_TURN")
                .isEqualTo(1);
        assertThat(bySource.fieldNames())
                .toIterable()
                .as("session path records only MAIN_TURN; skipped subsystems are omitted, not zeroed")
                .containsExactly("main_turn");
    }

    @Test
    void emptyAttributionDoesNotAddField() {
        var usage = mapper.createObjectNode();
        StreamingAgentHandler.writeLlmRequestsBySource(mapper, usage, RequestAttribution.empty());

        assertThat(usage.has("llmRequestsBySource"))
                .as("zero-total attribution is elided so old CLIs see no needless field")
                .isFalse();
    }

    @Test
    void multipleSourcesAllLowercased() {
        var usage = mapper.createObjectNode();
        var attribution = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN, 2)
                .record(RequestSource.PLANNER, 1)
                .record(RequestSource.COMPACTION_SUMMARY, 1)
                .build();

        StreamingAgentHandler.writeLlmRequestsBySource(mapper, usage, attribution);

        var bySource = usage.path("llmRequestsBySource");
        assertThat(bySource.path("main_turn").asInt()).isEqualTo(2);
        assertThat(bySource.path("planner").asInt()).isEqualTo(1);
        assertThat(bySource.path("compaction_summary").asInt()).isEqualTo(1);
        // Invariant from the chat-path javadoc: sum of values == total
        int sum = 0;
        for (var v : bySource) sum += v.asInt();
        assertThat(sum).isEqualTo(attribution.total());
    }
}
