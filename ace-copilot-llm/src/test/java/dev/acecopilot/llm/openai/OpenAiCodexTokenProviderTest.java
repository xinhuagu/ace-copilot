package dev.acecopilot.llm.openai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCodexTokenProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void configuredToken_hasHighestPriority() throws Exception {
        Path authFile = tempDir.resolve("auth.json");
        Files.writeString(authFile, """
                {
                  "tokens": {
                    "access_token": "from-file"
                  }
                }
                """);

        var provider = new OpenAiCodexTokenProvider("from-config", authFile);
        assertThat(provider.get()).isEqualTo("from-config");
    }

    @Test
    void loadsAccessToken_fromTokensObject() throws Exception {
        Path authFile = tempDir.resolve("auth.json");
        Files.writeString(authFile, """
                {
                  "tokens": {
                    "access_token": "from-tokens-access-token"
                  }
                }
                """);

        var provider = new OpenAiCodexTokenProvider(null, authFile);
        assertThat(provider.get()).isEqualTo("from-tokens-access-token");
    }

    @Test
    void fallsBackToLegacyOpenaiApiKey_field() throws Exception {
        Path authFile = tempDir.resolve("auth.json");
        Files.writeString(authFile, """
                {
                  "OPENAI_API_KEY": "from-legacy-field"
                }
                """);

        var provider = new OpenAiCodexTokenProvider(null, authFile);
        assertThat(provider.get()).isEqualTo("from-legacy-field");
    }

    @Test
    void returnsNotConfigured_whenNoSourceAvailable() {
        Path missingFile = tempDir.resolve("missing-auth.json");
        var provider = new OpenAiCodexTokenProvider(null, missingFile);
        assertThat(provider.get()).isEqualTo("not-configured");
    }
}
