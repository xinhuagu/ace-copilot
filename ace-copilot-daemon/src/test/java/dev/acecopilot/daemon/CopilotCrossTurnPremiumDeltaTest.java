package dev.acecopilot.daemon;

import dev.acecopilot.llm.copilot.CopilotAcpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the session-runtime cross-turn premium delta math
 * (PR #9 review thread).
 *
 * <p>GitHub's {@code premium_interactions.usedRequests} counter is
 * eventually consistent: the +1 for a billable turn can land between two
 * {@code assistant.usage} events INSIDE the current {@code sendAndWait}.
 * Sampling at the first event races with the flush; sampling at the last
 * event does not (it sees whatever the backend has settled on by the time
 * the turn wraps up). These tests pin that invariant.
 */
class CopilotCrossTurnPremiumDeltaTest {

    private static CopilotAcpClient.UsageSnapshot snap(long premiumUsed) {
        return new CopilotAcpClient.UsageSnapshot(
                "test-model", 0L, 0L, 0.0, "agent", premiumUsed, 100L);
    }

    @Test
    void nullBaselineReturnsMinusOne() {
        assertThat(StreamingAgentHandler.computeCrossTurnPremiumDelta(null, snap(5)))
                .as("no previous turn on record -> no comparison possible")
                .isEqualTo(-1);
    }

    @Test
    void missingLastSampleReturnsMinusOne() {
        assertThat(StreamingAgentHandler.computeCrossTurnPremiumDelta(5L, null))
                .isEqualTo(-1);
    }

    @Test
    void noBillingBetweenTurnsReportsZero() {
        // Turn N ended at 10, turn N+1 also ended at 10 → no billable
        // increment landed in either window. Honest 0.
        assertThat(StreamingAgentHandler.computeCrossTurnPremiumDelta(10L, snap(10)))
                .isEqualTo(0);
    }

    @Test
    void billingBetweenTurnsCapturedCleanly() {
        // Previous turn settled at 10; this turn's backend has since flushed
        // to 11 by the last assistant.usage event.
        assertThat(StreamingAgentHandler.computeCrossTurnPremiumDelta(10L, snap(11)))
                .isEqualTo(1);
    }

    @Test
    void midStreamFlushCapturedByLastNotFirst() {
        // Race scenario (reviewer's fix): the previous turn ended at 10.
        // The current turn's first assistant.usage came in BEFORE the
        // backend incremented — firstUsage.premiumUsed == 10, so an
        // implementation that subtracts first from prevLast reports 0.
        // By the last assistant.usage the counter has flushed to 11.
        // The helper samples last and correctly reports 1.
        var last = snap(11);
        assertThat(StreamingAgentHandler.computeCrossTurnPremiumDelta(10L, last))
                .as("last.premiumUsed must drive the delta, not first.premiumUsed")
                .isEqualTo(1);
    }

    @Test
    void resolveBaselinePrefersLast() {
        assertThat(StreamingAgentHandler.resolvePremiumBaseline(snap(11), snap(10)))
                .isEqualTo(11);
    }

    @Test
    void resolveBaselineFallsBackToFirst() {
        assertThat(StreamingAgentHandler.resolvePremiumBaseline(null, snap(10)))
                .as("when sidecar emits only one usage event the handler stored it as 'first'")
                .isEqualTo(10);
    }

    @Test
    void resolveBaselineReturnsNullWhenBothMissing() {
        assertThat(StreamingAgentHandler.resolvePremiumBaseline(null, null)).isNull();
    }
}
