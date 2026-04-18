package dev.acecopilot.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    @TempDir
    Path tempDir;

    private Path rulesDir;

    @BeforeEach
    void setUp() throws IOException {
        rulesDir = tempDir.resolve(".ace-copilot/rules");
        Files.createDirectories(rulesDir);
    }

    @Test
    void loadRulesFromDirectory() throws IOException {
        Files.writeString(rulesDir.resolve("test-conventions.md"), """
                ---
                paths:
                  - "**/*Test.java"
                  - "**/*Spec.java"
                ---

                # Test Conventions

                - Always use JUnit 5
                """);

        var engine = RuleEngine.loadRules(tempDir);

        assertThat(engine.rules()).hasSize(1);
        assertThat(engine.rules().getFirst().name()).isEqualTo("test-conventions");
        assertThat(engine.rules().getFirst().patterns()).containsExactly("**/*Test.java", "**/*Spec.java");
        assertThat(engine.rules().getFirst().content()).contains("JUnit 5");
    }

    @Test
    void matchRulesByGlobPattern() throws IOException {
        Files.writeString(rulesDir.resolve("test-rules.md"), """
                ---
                paths:
                  - "**/*Test.java"
                ---

                Use JUnit 5 annotations.
                """);

        var engine = RuleEngine.loadRules(tempDir);

        var matches = engine.matchRules("src/test/FooTest.java");
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().name()).isEqualTo("test-rules");
    }

    @Test
    void matchMultipleRules() throws IOException {
        Files.writeString(rulesDir.resolve("test-rules.md"), """
                ---
                paths:
                  - "**/*Test.java"
                ---

                Use JUnit 5.
                """);

        Files.writeString(rulesDir.resolve("naming.md"), """
                ---
                paths:
                  - "**/*.java"
                ---

                Use camelCase for methods.
                """);

        var engine = RuleEngine.loadRules(tempDir);

        var matches = engine.matchRules("src/FooTest.java");
        assertThat(matches).hasSize(2);
    }

    @Test
    void noMatchReturnsEmpty() throws IOException {
        Files.writeString(rulesDir.resolve("test-rules.md"), """
                ---
                paths:
                  - "**/*Test.java"
                ---

                Test rules content.
                """);

        var engine = RuleEngine.loadRules(tempDir);

        var matches = engine.matchRules("src/Main.java");
        assertThat(matches).isEmpty();
    }

    @Test
    void malformedYamlSkipped() throws IOException {
        // No frontmatter delimiters
        Files.writeString(rulesDir.resolve("bad-rule.md"), """
                No frontmatter here.
                Just regular content.
                """);

        // Valid rule
        Files.writeString(rulesDir.resolve("good-rule.md"), """
                ---
                paths:
                  - "*.md"
                ---

                Markdown rules.
                """);

        var engine = RuleEngine.loadRules(tempDir);

        assertThat(engine.rules()).hasSize(1);
        assertThat(engine.rules().getFirst().name()).isEqualTo("good-rule");
    }

    @Test
    void formatForPrompt() throws IOException {
        Files.writeString(rulesDir.resolve("test-conventions.md"), """
                ---
                paths:
                  - "**/*Test.java"
                ---

                # Test Conventions

                - Always use JUnit 5
                """);

        var engine = RuleEngine.loadRules(tempDir);

        String prompt = engine.formatForPrompt(List.of("src/test/FooTest.java", "src/Main.java"));
        assertThat(prompt).contains("Path-Based Rules");
        assertThat(prompt).contains("test-conventions");
        assertThat(prompt).contains("JUnit 5");
    }

    @Test
    void emptyRulesDirectory() {
        var engine = RuleEngine.loadRules(tempDir.resolve("nonexistent"));
        assertThat(engine.rules()).isEmpty();
    }

    @Test
    void inlinePathsSyntax() throws IOException {
        Files.writeString(rulesDir.resolve("inline.md"), """
                ---
                paths: ["*.ts", "*.tsx"]
                ---

                TypeScript rules.
                """);

        var engine = RuleEngine.loadRules(tempDir);

        assertThat(engine.rules()).hasSize(1);
        assertThat(engine.rules().getFirst().patterns()).containsExactly("*.ts", "*.tsx");
    }
}
