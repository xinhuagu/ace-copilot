package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Resolves OpenAI Codex OAuth token from local credentials.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Configured token from profile apiKey</li>
 *   <li>{@code ~/.codex/auth.json} → {@code tokens.access_token}</li>
 *   <li>{@code ~/.codex/auth.json} → {@code OPENAI_API_KEY}</li>
 *   <li>{@code OPENAI_API_KEY} environment variable</li>
 * </ol>
 */
public final class OpenAiCodexTokenProvider implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCodexTokenProvider.class);
    private static final Path DEFAULT_AUTH_FILE = Path.of(
            System.getProperty("user.home"), ".codex", "auth.json");

    private final String configuredToken;
    private final Path authFile;
    private final ObjectMapper mapper;

    private volatile String cachedToken;
    private volatile long cachedFileModifiedMs = Long.MIN_VALUE;

    public OpenAiCodexTokenProvider(String configuredToken) {
        this(configuredToken, DEFAULT_AUTH_FILE);
    }

    OpenAiCodexTokenProvider(String configuredToken, Path authFile) {
        this.configuredToken = configuredToken;
        this.authFile = authFile;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String get() {
        if (configuredToken != null && !configuredToken.isBlank()) {
            return configuredToken;
        }

        String fromFile = loadFromCodexAuthFile();
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }

        String fromEnv = System.getenv("OPENAI_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }

        return "not-configured";
    }

    private String loadFromCodexAuthFile() {
        try {
            if (!Files.exists(authFile)) {
                return null;
            }

            long modifiedMs = Files.getLastModifiedTime(authFile).toMillis();
            String current = cachedToken;
            if (current != null && modifiedMs == cachedFileModifiedMs) {
                return current;
            }

            synchronized (this) {
                current = cachedToken;
                if (current != null && modifiedMs == cachedFileModifiedMs) {
                    return current;
                }

                JsonNode root = mapper.readTree(Files.readString(authFile));
                String token = text(root.path("tokens").path("access_token"));
                if (token == null) {
                    token = text(root.path("OPENAI_API_KEY"));
                }

                if (token != null && !token.isBlank()) {
                    cachedToken = token;
                    cachedFileModifiedMs = modifiedMs;
                    return token;
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read Codex auth file {}: {}", authFile, e.getMessage());
        } catch (Exception e) {
            log.debug("Invalid Codex auth file {}: {}", authFile, e.getMessage());
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }
}
