package dev.acecopilot.llm.copilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for {@link CopilotAcpClient} covering the Phase 1 (issue #3)
 * startup chain without touching the real Copilot backend.
 *
 * <p>Uses {@code src/test/resources/copilot/mock-sidecar.mjs} — a stripped
 * sidecar that speaks the same LSP-framed JSON-RPC protocol but emits
 * canned notifications and a fixed result. This exercises:
 * <ul>
 *   <li>subprocess spawn + initialize handshake</li>
 *   <li>notification decoding ({@code session/text}, {@code session/usage})</li>
 *   <li>usage snapshot first/last capture and premium delta</li>
 *   <li>final {@code sendAndWait} response parsing</li>
 *   <li>graceful {@code shutdown} + process teardown</li>
 * </ul>
 *
 * <p>Skipped automatically when {@code node} is not on {@code PATH}; the
 * session runtime itself is opt-in and guarded by a startup preflight in
 * {@code AceCopilotDaemon}, so we do the same here to keep CI on machines
 * without Node.js green.
 */
@EnabledIf("nodeAvailable")
class CopilotAcpClientSmokeTest {

    /** Used by {@link EnabledIf} to skip when Node.js is not installed. */
    static boolean nodeAvailable() {
        try {
            var p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void launchesSidecarHandshakesAndReportsUsage(@TempDir Path dir) throws IOException {
        // CopilotAcpClient expects sidecarDir/sidecar.mjs — copy the mock
        // out of resources so the file name matches.
        copyResource("/copilot/mock-sidecar.mjs", dir.resolve("sidecar.mjs"));

        var deltas = new ArrayList<String>();
        CopilotAcpClient.SendResult result;

        try (var client = new CopilotAcpClient(dir, null)) {
            result = client.sendAndWait("test-model", "hi", deltas::add);
        }

        assertThat(deltas)
                .as("both session/text deltas forwarded to consumer in order")
                .containsExactly("Hello ", "world.");

        assertThat(result.content()).isEqualTo("Hello world.");
        assertThat(result.stopReason()).isEqualTo("COMPLETE");

        assertThat(result.usageEventCount())
                .as("both session/usage events captured")
                .isEqualTo(2);

        assertThat(result.firstUsage()).isNotNull();
        assertThat(result.firstUsage().initiator()).isEqualTo("user");
        assertThat(result.firstUsage().premiumUsed()).isEqualTo(42L);

        assertThat(result.lastUsage()).isNotNull();
        assertThat(result.lastUsage().initiator()).isEqualTo("agent");
        assertThat(result.lastUsage().premiumUsed()).isEqualTo(43L);

        assertThat(result.intraTurnPremiumDelta())
                .as("diagnostic: subtraction inside a single turn — honest cross-turn delta lives on the daemon")
                .isEqualTo(1L);
    }

    private static void copyResource(String cp, Path dest) throws IOException {
        try (InputStream in = CopilotAcpClientSmokeTest.class.getResourceAsStream(cp)) {
            if (in == null) throw new IOException("classpath resource missing: " + cp);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
