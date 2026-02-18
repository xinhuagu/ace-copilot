package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTypeRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void builtinTypesAvailable() {
        var registry = AgentTypeRegistry.withBuiltins();

        assertThat(registry.names()).containsExactly("explore", "plan", "general", "bash");
        assertThat(registry.all()).hasSize(4);
    }

    @Test
    void lookupBuiltinType() {
        var registry = AgentTypeRegistry.withBuiltins();

        var explore = registry.get("explore");
        assertThat(explore).isPresent();
        assertThat(explore.get().name()).isEqualTo("explore");
        assertThat(explore.get().model()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(explore.get().allowedTools()).containsExactly("read_file", "glob", "grep", "list_directory");

        var general = registry.get("general");
        assertThat(general).isPresent();
        assertThat(general.get().inheritsModel()).isTrue();
        assertThat(general.get().allowedTools()).isEmpty();
    }

    @Test
    void unknownTypeReturnsEmpty() {
        var registry = AgentTypeRegistry.withBuiltins();
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void customAgentParsing() throws Exception {
        // Create a custom agent file
        Path agentsDir = tempDir.resolve(".aceclaw/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("reviewer.md"), """
                ---
                description: "Code review agent"
                model: claude-sonnet-4-5-20250929
                allowed_tools:
                  - read_file
                  - glob
                  - grep
                max_turns: 15
                ---

                You are a code review agent. Review the given files for bugs and style issues.
                """);

        var registry = AgentTypeRegistry.load(tempDir);

        // Builtins still present
        assertThat(registry.get("explore")).isPresent();

        // Custom agent loaded
        var reviewer = registry.get("reviewer");
        assertThat(reviewer).isPresent();
        assertThat(reviewer.get().description()).isEqualTo("Code review agent");
        assertThat(reviewer.get().model()).isEqualTo("claude-sonnet-4-5-20250929");
        assertThat(reviewer.get().allowedTools()).containsExactly("read_file", "glob", "grep");
        assertThat(reviewer.get().maxTurns()).isEqualTo(15);
        assertThat(reviewer.get().systemPromptTemplate()).contains("code review agent");
    }

    @Test
    void customAgentWithInlineTools() throws Exception {
        Path agentsDir = tempDir.resolve(".aceclaw/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("runner.md"), """
                ---
                description: "Test runner"
                allowed_tools: ["bash", "read_file"]
                disallowed_tools: ["write_file"]
                ---

                Run tests and report results.
                """);

        var registry = AgentTypeRegistry.load(tempDir);
        var runner = registry.get("runner");
        assertThat(runner).isPresent();
        assertThat(runner.get().allowedTools()).containsExactly("bash", "read_file");
        assertThat(runner.get().disallowedTools()).containsExactly("write_file");
        assertThat(runner.get().inheritsModel()).isTrue();
    }

    @Test
    void namesListIncludesCustom() throws Exception {
        Path agentsDir = tempDir.resolve(".aceclaw/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("custom.md"), """
                ---
                description: "Custom agent"
                ---

                Do custom work.
                """);

        var registry = AgentTypeRegistry.load(tempDir);
        assertThat(registry.names()).contains("explore", "plan", "general", "bash", "custom");
    }

    @Test
    void noAgentsDirDoesNotError() {
        var registry = AgentTypeRegistry.load(tempDir);
        assertThat(registry.names()).containsExactly("explore", "plan", "general", "bash");
    }

    @Test
    void malformedFilesSkipped() throws Exception {
        Path agentsDir = tempDir.resolve(".aceclaw/agents");
        Files.createDirectories(agentsDir);
        // No frontmatter — should be skipped
        Files.writeString(agentsDir.resolve("broken.md"), "Just some text without frontmatter.");

        var registry = AgentTypeRegistry.load(tempDir);
        assertThat(registry.get("broken")).isEmpty();
        // Builtins still loaded
        assertThat(registry.names()).containsExactly("explore", "plan", "general", "bash");
    }
}
