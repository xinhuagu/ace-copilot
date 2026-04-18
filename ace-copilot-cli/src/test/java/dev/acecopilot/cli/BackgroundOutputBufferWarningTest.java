package dev.acecopilot.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fix from PR #8: {@code stream.warning} emitted while a task
 * is in {@code /bg} was being dropped because {@code OutputSink.onWarning}
 * had no buffered variant. Issue #12 asks for regression coverage so the
 * buffering + replay path stays correct when Phase 3 adds more warning
 * sites (tool catalog resets, elicitation declines, respondToUserInput
 * state transitions).
 */
class BackgroundOutputBufferWarningTest {

    @Test
    void warningCountedInBufferSize() {
        var buffer = new BackgroundOutputBuffer();
        int before = buffer.size();
        buffer.onWarning("Copilot session context will reset: tool catalog changed");
        assertThat(buffer.size())
                .as("onWarning must create a buffered event (not fall through the default no-op)")
                .isEqualTo(before + 1);
    }

    @Test
    void warningIncludedInExtractedText() {
        var buffer = new BackgroundOutputBuffer();
        buffer.onTextDelta("agent reply ");
        buffer.onWarning("remote context reset");
        buffer.onTextDelta("continues");

        String text = buffer.extractTextContent();
        assertThat(text)
                .as("background completion dumps text + errors + warnings so users catch context-reset signals after-the-fact")
                .contains("agent reply ")
                .contains("[warning: remote context reset]")
                .contains("continues");
    }
}
