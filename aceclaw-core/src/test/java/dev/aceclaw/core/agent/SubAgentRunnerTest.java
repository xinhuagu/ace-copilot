package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void filteredRegistryExcludesTask() {
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(stubTool("read_file"));
        parentRegistry.register(stubTool("write_file"));
        parentRegistry.register(stubTool("task"));
        parentRegistry.register(stubTool("glob"));

        var runner = new SubAgentRunner(
                new StubLlmClient(), parentRegistry, "parent-model", tempDir, 4096, 0);

        // General agent: all tools minus task
        var generalConfig = new SubAgentConfig("general", "", null, List.of(), List.of(), 25, "");
        var generalRegistry = runner.createFilteredRegistry(generalConfig);
        assertThat(generalRegistry.size()).isEqualTo(3);
        assertThat(generalRegistry.get("task")).isEmpty();
        assertThat(generalRegistry.get("read_file")).isPresent();
        assertThat(generalRegistry.get("write_file")).isPresent();
        assertThat(generalRegistry.get("glob")).isPresent();
    }

    @Test
    void filteredRegistryRespectsAllowList() {
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(stubTool("read_file"));
        parentRegistry.register(stubTool("write_file"));
        parentRegistry.register(stubTool("task"));
        parentRegistry.register(stubTool("glob"));
        parentRegistry.register(stubTool("grep"));

        var runner = new SubAgentRunner(
                new StubLlmClient(), parentRegistry, "parent-model", tempDir, 4096, 0);

        // Explore agent: only read_file, glob, grep
        var exploreConfig = new SubAgentConfig(
                "explore", "", null,
                List.of("read_file", "glob", "grep"), List.of(), 25, "");
        var exploreRegistry = runner.createFilteredRegistry(exploreConfig);
        assertThat(exploreRegistry.size()).isEqualTo(3);
        assertThat(exploreRegistry.get("read_file")).isPresent();
        assertThat(exploreRegistry.get("glob")).isPresent();
        assertThat(exploreRegistry.get("grep")).isPresent();
        assertThat(exploreRegistry.get("write_file")).isEmpty();
        assertThat(exploreRegistry.get("task")).isEmpty();
    }

    @Test
    void filteredRegistryRespectsDisallowList() {
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(stubTool("read_file"));
        parentRegistry.register(stubTool("write_file"));
        parentRegistry.register(stubTool("task"));
        parentRegistry.register(stubTool("bash"));

        var runner = new SubAgentRunner(
                new StubLlmClient(), parentRegistry, "parent-model", tempDir, 4096, 0);

        var config = new SubAgentConfig(
                "safe", "", null, List.of(), List.of("bash"), 25, "");
        var registry = runner.createFilteredRegistry(config);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("read_file")).isPresent();
        assertThat(registry.get("write_file")).isPresent();
        assertThat(registry.get("bash")).isEmpty();
        assertThat(registry.get("task")).isEmpty();
    }

    @Test
    void modelResolutionInheritsParent() throws Exception {
        var mockClient = new SimpleMockLlmClient("Sub-agent result");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "parent-model-123", tempDir, 4096, 0);

        var config = new SubAgentConfig("general", "", null, List.of(), List.of(), 25, "test prompt");
        var result = runner.run(config, "Do something", null);

        assertThat(result).isEqualTo("Sub-agent result");
        // Verify the model used was the parent model
        assertThat(mockClient.lastRequest).isNotNull();
        assertThat(mockClient.lastRequest.model()).isEqualTo("parent-model-123");
    }

    @Test
    void modelResolutionUsesConfigModel() throws Exception {
        var mockClient = new SimpleMockLlmClient("Result from haiku");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "parent-model-123", tempDir, 4096, 0);

        var config = new SubAgentConfig("explore", "", "haiku-model", List.of(), List.of(), 25, "test");
        var result = runner.run(config, "Search files", null);

        assertThat(result).isEqualTo("Result from haiku");
        assertThat(mockClient.lastRequest.model()).isEqualTo("haiku-model");
    }

    @Test
    void systemPromptIncludesWorkingDir() throws Exception {
        var mockClient = new SimpleMockLlmClient("done");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0);

        var config = new SubAgentConfig("test", "", null, List.of(), List.of(), 25,
                "Agent working in %s");
        runner.run(config, "test task", null);

        assertThat(mockClient.lastRequest.systemPrompt()).contains(tempDir.toString());
    }

    // -- Test helpers ---------------------------------------------------------

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " tool"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }
            @Override public ToolResult execute(String inputJson) {
                return new ToolResult("ok", false);
            }
        };
    }

    /**
     * Minimal mock LLM client that returns a single text response.
     */
    private static final class SimpleMockLlmClient implements LlmClient {

        private final String responseText;
        volatile LlmRequest lastRequest;

        SimpleMockLlmClient(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public LlmResponse sendMessage(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) {
            lastRequest = request;
            return new StreamSession() {
                @Override
                public void onEvent(StreamEventHandler handler) {
                    handler.onMessageStart(new StreamEvent.MessageStart("msg-sub", "mock"));
                    handler.onContentBlockStart(new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")));
                    handler.onTextDelta(new StreamEvent.TextDelta(responseText));
                    handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                    handler.onMessageDelta(new StreamEvent.MessageDelta(StopReason.END_TURN, new Usage(50, 20)));
                    handler.onComplete(new StreamEvent.StreamComplete());
                }

                @Override
                public void cancel() {}
            };
        }

        @Override public String provider() { return "mock"; }
        @Override public String defaultModel() { return "mock-model"; }
    }

    /**
     * Stub client for tests that only need registry filtering (no actual calls).
     */
    private static final class StubLlmClient implements LlmClient {
        @Override public LlmResponse sendMessage(LlmRequest request) { throw new UnsupportedOperationException(); }
        @Override public StreamSession streamMessage(LlmRequest request) { throw new UnsupportedOperationException(); }
        @Override public String provider() { return "stub"; }
        @Override public String defaultModel() { return "stub-model"; }
    }
}
