package dev.acecopilot.llm.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 (#5) smoke test: verifies the sidecar → daemon
 * {@code user_input.request} round-trip via the mock sidecar
 * {@code mock-sidecar-user-input.mjs}. No real SDK involved.
 *
 * <p>Exercises:
 * <ul>
 *   <li>sidecar initiates {@code user_input.request} during sendAndWait</li>
 *   <li>Java {@link CopilotAcpClient.RequestHandler} receives the RPC
 *       with the expected params shape</li>
 *   <li>handler's response flows back and is threaded into the final
 *       assistant content — proving the Promise seen by the SDK
 *       actually resolves with our answer</li>
 * </ul>
 */
@EnabledIf("nodeAvailable")
class CopilotAcpClientUserInputSmokeTest {

    static boolean nodeAvailable() {
        try {
            var p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void sidecarUserInputRequestRoundtripsThroughRequestHandler(@TempDir Path dir) throws IOException {
        copyResource("/copilot/mock-sidecar-user-input.mjs", dir.resolve("sidecar.mjs"));
        var mapper = new ObjectMapper();

        var captured = new AtomicReference<JsonNode>();
        CopilotAcpClient.SendResult result;
        try (var client = new CopilotAcpClient(dir, null)) {
            client.setRequestHandler((method, params) -> {
                if ("user_input.request".equals(method)) {
                    captured.set(params);
                    var answer = mapper.createObjectNode();
                    answer.put("requestId", params.path("requestId").asText());
                    answer.put("answer", "red");
                    answer.put("wasFreeform", false);
                    return answer;
                }
                throw new IllegalArgumentException("unexpected method: " + method);
            });
            result = client.sendAndWait("test-model", "pick a color", List.of(), null);
        }

        var params = captured.get();
        assertThat(params).as("sidecar issued user_input.request back to Java").isNotNull();
        assertThat(params.path("requestId").asText()).isEqualTo("mock-rid-42");
        assertThat(params.path("question").asText()).isEqualTo("Which color?");
        assertThat(params.path("allowFreeform").asBoolean()).isTrue();
        var choices = params.get("choices");
        assertThat(choices).isNotNull();
        assertThat(choices.isArray()).isTrue();
        assertThat(choices.size()).isEqualTo(2);
        assertThat(choices.get(0).asText()).isEqualTo("red");

        assertThat(result.content())
                .as("handler's answer was delivered back to the SDK and included in the assistant content")
                .isEqualTo("agent received: red");
        assertThat(result.stopReason()).isEqualTo("COMPLETE");
    }

    @Test
    void handlerReturningCancelResolvesToAgentWithCancelFlag(@TempDir Path dir) throws IOException {
        copyResource("/copilot/mock-sidecar-user-input.mjs", dir.resolve("sidecar.mjs"));
        var mapper = new ObjectMapper();
        CopilotAcpClient.SendResult result;
        try (var client = new CopilotAcpClient(dir, null)) {
            client.setRequestHandler((method, params) -> {
                if ("user_input.request".equals(method)) {
                    var answer = mapper.createObjectNode();
                    answer.put("requestId", params.path("requestId").asText());
                    answer.put("cancel", true);
                    return answer;
                }
                throw new IllegalArgumentException("unexpected method: " + method);
            });
            result = client.sendAndWait("test-model", "pick a color", List.of(), null);
        }
        assertThat(result.content())
                .as("/new escape hatch: handler returns cancel:true, SDK sees a decline payload")
                .isEqualTo("agent received: (cancelled)");
    }

    private static void copyResource(String cp, Path dest) throws IOException {
        try (InputStream in = CopilotAcpClientUserInputSmokeTest.class.getResourceAsStream(cp)) {
            if (in == null) throw new IOException("classpath resource missing: " + cp);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
