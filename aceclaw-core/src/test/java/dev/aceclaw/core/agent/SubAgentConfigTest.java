package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubAgentConfigTest {

    @Test
    void constructionWithAllFields() {
        var config = new SubAgentConfig(
                "test-agent", "A test agent", "test-model",
                List.of("read_file", "glob"), List.of("bash"),
                10, "You are a test agent.");

        assertThat(config.name()).isEqualTo("test-agent");
        assertThat(config.description()).isEqualTo("A test agent");
        assertThat(config.model()).isEqualTo("test-model");
        assertThat(config.allowedTools()).containsExactly("read_file", "glob");
        assertThat(config.disallowedTools()).containsExactly("bash");
        assertThat(config.maxTurns()).isEqualTo(10);
        assertThat(config.systemPromptTemplate()).isEqualTo("You are a test agent.");
    }

    @Test
    void defensiveCopiesOfLists() {
        var allowed = new java.util.ArrayList<>(List.of("read_file"));
        var disallowed = new java.util.ArrayList<>(List.of("bash"));

        var config = new SubAgentConfig("test", "desc", null, allowed, disallowed, 5, "prompt");

        // Mutating the original lists should not affect the config
        allowed.add("glob");
        disallowed.add("write_file");

        assertThat(config.allowedTools()).containsExactly("read_file");
        assertThat(config.disallowedTools()).containsExactly("bash");
    }

    @Test
    void inheritsModelWhenNull() {
        var config = new SubAgentConfig("explore", "desc", null, List.of(), List.of(), 25, "");
        assertThat(config.inheritsModel()).isTrue();

        var configWithModel = new SubAgentConfig("explore", "desc", "haiku", List.of(), List.of(), 25, "");
        assertThat(configWithModel.inheritsModel()).isFalse();
    }

    @Test
    void defaultMaxTurns() {
        var config = new SubAgentConfig("test", "desc", null, null, null, 0, null);
        assertThat(config.maxTurns()).isEqualTo(SubAgentConfig.DEFAULT_MAX_TURNS);
    }

    @Test
    void nullDefaults() {
        var config = new SubAgentConfig("test", null, null, null, null, -1, null);
        assertThat(config.description()).isEmpty();
        assertThat(config.allowedTools()).isEmpty();
        assertThat(config.disallowedTools()).isEmpty();
        assertThat(config.maxTurns()).isEqualTo(SubAgentConfig.DEFAULT_MAX_TURNS);
        assertThat(config.systemPromptTemplate()).isEmpty();
    }

    @Test
    void blankNameThrows() {
        assertThatThrownBy(() -> new SubAgentConfig("", "desc", null, List.of(), List.of(), 25, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be");

        assertThatThrownBy(() -> new SubAgentConfig(null, "desc", null, List.of(), List.of(), 25, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be");
    }
}
