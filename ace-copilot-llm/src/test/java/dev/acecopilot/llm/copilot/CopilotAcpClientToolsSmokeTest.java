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
 * Phase 2 (#4) smoke test: covers the bidirectional JSON-RPC channel and
 * the {@code tool.invoke} RPC that the sidecar uses to dispatch an SDK
 * agent's tool call back to the Java daemon.
 *
 * <p>Uses {@code src/test/resources/copilot/mock-sidecar-tools.mjs}, a
 * stripped sidecar that during {@code session.sendAndWait} issues a
 * {@code tool.invoke} request back to us (simulating the SDK agent) and
 * returns the tool's content as the assistant reply. Exercises:
 * <ul>
 *   <li>{@code initialize} carries tool descriptors ({@code toolsRegistered})</li>
 *   <li>Java-side reader distinguishes incoming requests from notifications
 *       and responses, dispatching to the registered {@link CopilotAcpClient.RequestHandler}</li>
 *   <li>handler result is serialized back to the sidecar with the right id</li>
 * </ul>
 */
@EnabledIf("nodeAvailable")
class CopilotAcpClientToolsSmokeTest {

    static boolean nodeAvailable() {
        try {
            var p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void initializePassesToolDefinitionsAndRoutesToolInvoke(@TempDir Path dir) throws IOException {
        copyResource("/copilot/mock-sidecar-tools.mjs", dir.resolve("sidecar.mjs"));

        var mapper = new ObjectMapper();
        var readFileSchema = mapper.createObjectNode();
        readFileSchema.put("type", "object");
        var tools = List.of(
                new CopilotAcpClient.ToolDescriptor("read_file", "Read a file", readFileSchema),
                new CopilotAcpClient.ToolDescriptor("grep", "Search files", readFileSchema)
        );

        var receivedInvoke = new AtomicReference<JsonNode>();
        CopilotAcpClient.SendResult result;
        try (var client = new CopilotAcpClient(dir, null, tools)) {
            client.setRequestHandler((method, params) -> {
                if ("tool.invoke".equals(method)) {
                    receivedInvoke.set(params);
                    var node = mapper.createObjectNode();
                    node.put("isError", false);
                    node.put("content", "mock file contents");
                    return node;
                }
                throw new IllegalArgumentException("unexpected method: " + method);
            });
            result = client.sendAndWait("test-model", "read a file please", null);
        }

        assertThat(receivedInvoke.get())
                .as("sidecar issued tool.invoke back to Java")
                .isNotNull();
        assertThat(receivedInvoke.get().path("name").asText()).isEqualTo("read_file");
        assertThat(receivedInvoke.get().path("arguments").path("path").asText())
                .isEqualTo("mock-path.txt");

        assertThat(result.content())
                .as("tool result flowed back through the SDK reply")
                .isEqualTo("tool said: mock file contents");
        assertThat(result.stopReason()).isEqualTo("COMPLETE");
    }

    private static void copyResource(String cp, Path dest) throws IOException {
        try (InputStream in = CopilotAcpClientToolsSmokeTest.class.getResourceAsStream(cp)) {
            if (in == null) throw new IOException("classpath resource missing: " + cp);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
