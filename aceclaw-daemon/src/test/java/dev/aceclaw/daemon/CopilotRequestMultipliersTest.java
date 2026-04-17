package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotRequestMultipliersTest {

    @Test
    void returnsNullForNonCopilotProvider() {
        assertThat(CopilotRequestMultipliers.forProviderAndModel("anthropic", "claude-opus-4-6"))
                .isNull();
        assertThat(CopilotRequestMultipliers.forProviderAndModel("openai", "gpt-4o"))
                .isNull();
        assertThat(CopilotRequestMultipliers.forProviderAndModel(null, "claude-opus-4-6"))
                .isNull();
    }

    @Test
    void returnsKnownMultipliersForCopilotModels() {
        // Exact model identifier hits the map directly.
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-opus-4-6"))
                .isEqualTo(10.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-sonnet-4-5"))
                .isEqualTo(1.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "o3-mini"))
                .isEqualTo(0.33);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "gpt-4.1"))
                .isEqualTo(0.0);
    }

    @Test
    void stripsTrailingDateSuffixBeforeLookup() {
        // Anthropic model ids in this repo carry a date suffix — the lookup should strip
        // it before hitting the table so "claude-opus-4-6-20250929" maps to the same
        // multiplier as "claude-opus-4-6".
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-opus-4-6-20250929"))
                .isEqualTo(10.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-sonnet-4-5-20260101"))
                .isEqualTo(1.0);
    }

    @Test
    void unknownCopilotModelFallsBackToDefault() {
        // Unknown Copilot model (newly released, or a typo in config) falls back to 1.0 —
        // the common-case rate. Logged offline data still has "model" so analysts can spot
        // the fallback and update the table.
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "future-model-9000"))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
    }

    @Test
    void caseInsensitiveProviderAndModel() {
        assertThat(CopilotRequestMultipliers.forProviderAndModel("COPILOT", "CLAUDE-OPUS-4-6"))
                .isEqualTo(10.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("Copilot", "Claude-Sonnet-4-5"))
                .isEqualTo(1.0);
    }

    @Test
    void blankOrNullModelYieldsDefault() {
        // Copilot with no model yields the default — caller knows the provider but can't
        // distinguish the tier; 1.0 is the safe assumption for projection.
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", null))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", ""))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "   "))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
    }
}
