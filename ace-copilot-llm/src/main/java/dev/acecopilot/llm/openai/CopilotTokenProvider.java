package dev.acecopilot.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Handles GitHub Copilot authentication with two strategies:
 *
 * <p><b>Strategy 1 — Token exchange</b> (for OAuth tokens from device-code flow):
 * Exchange via {@code GET https://api.github.com/copilot_internal/v2/token}
 * to get a short-lived Copilot session token.
 *
 * <p><b>Strategy 2 — Direct PAT</b> (for classic PATs and fine-grained PATs):
 * If token exchange fails (PATs are not supported by that endpoint), use the
 * GitHub token directly as Bearer token for the Copilot API. This works for
 * tokens with the {@code copilot} scope or "Copilot Requests" permission.
 *
 * <p>Token resolution order (tries each until one works). The order
 * deliberately mirrors the CLI's first-time-login pre-flight: the cached
 * device-code token and {@code gh auth token} come first, so the source
 * that satisfied pre-flight is the same one runtime uses. Other sources
 * remain available as fallbacks for headless / CI scenarios but cannot
 * silently override an account the user already confirmed.
 * <ol>
 *   <li>Cached device-code token at {@code ~/.ace-copilot/copilot-oauth-token}</li>
 *   <li>{@code gh auth token} from GitHub CLI</li>
 *   <li>Configured {@code apiKey} from config.json</li>
 *   <li>{@code GITHUB_TOKEN} environment variable</li>
 *   <li>{@code GH_TOKEN} environment variable</li>
 * </ol>
 */
public final class CopilotTokenProvider implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(CopilotTokenProvider.class);

    private static final String TOKEN_EXCHANGE_URL = "https://api.github.com/copilot_internal/v2/token";
    private static final String DEFAULT_ENDPOINT = "https://api.githubcopilot.com";
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(30);

    // Headers matching what the Copilot API expects (from copilot-api project)
    private static final String COPILOT_VERSION = "0.26.7";
    private static final String EDITOR_PLUGIN_VERSION = "copilot-chat/" + COPILOT_VERSION;
    private static final String USER_AGENT = "GitHubCopilotChat/" + COPILOT_VERSION;
    private static final String API_VERSION = "2025-04-01";
    private static final String EDITOR_VERSION = "vscode/1.95.0";

    /** Refresh the token 5 minutes before expiry. */
    private static final long REFRESH_MARGIN_SECONDS = 300;

    private final String configuredToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String activeToken;
    private volatile String resolvedEndpoint;
    private volatile Instant expiresAt;
    /** True if using token exchange; false if using direct PAT. */
    private volatile boolean isExchangedToken;

    public CopilotTokenProvider(String configuredToken) {
        this.configuredToken = configuredToken;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String get() {
        // For direct PAT mode, just return the token (no expiry management)
        if (activeToken != null && !isExchangedToken) {
            return activeToken;
        }
        // For exchanged token, check expiry
        if (activeToken != null && expiresAt != null
                && Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS).isBefore(expiresAt)) {
            return activeToken;
        }
        synchronized (this) {
            if (activeToken != null && !isExchangedToken) {
                return activeToken;
            }
            if (activeToken != null && expiresAt != null
                    && Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS).isBefore(expiresAt)) {
                return activeToken;
            }
            resolveToken();
            return activeToken;
        }
    }

    /**
     * Returns the API endpoint URL discovered from token exchange,
     * or the default endpoint.
     */
    public String apiEndpoint() {
        String ep = resolvedEndpoint;
        return ep != null ? ep : DEFAULT_ENDPOINT;
    }

    /**
     * Tries each available GitHub token with two strategies:
     * 1. Token exchange (for OAuth tokens)
     * 2. Direct use as Bearer (for PATs)
     */
    private void resolveToken() {
        List<String> candidates = collectTokenCandidates();

        if (candidates.isEmpty()) {
            throw new RuntimeException(
                    "No GitHub token available for Copilot. Configure apiKey in the copilot profile, "
                            + "or set GITHUB_TOKEN / GH_TOKEN, or install GitHub CLI (gh auth login).");
        }

        // Strategy 1: Try token exchange (works for OAuth tokens from device-code flow)
        for (String token : candidates) {
            try {
                exchange(token);
                log.info("Copilot auth: token exchange succeeded");
                return;
            } catch (RuntimeException e) {
                String masked = maskToken(token);
                log.debug("Token exchange failed for {}: {}", masked, e.getMessage());
            }
        }

        // Strategy 2: Use the first token directly as Bearer (works for PATs)
        // PATs with 'copilot' scope or 'Copilot Requests' permission can call
        // the Copilot API directly without token exchange.
        String directToken = candidates.getFirst();
        log.info("Copilot auth: token exchange not available, using token directly as Bearer");
        this.activeToken = directToken;
        this.isExchangedToken = false;
        this.resolvedEndpoint = DEFAULT_ENDPOINT;
    }

    private List<String> collectTokenCandidates() {
        return collectGithubTokenCandidates(configuredToken);
    }

    /**
     * Returns the ordered list of GitHub token candidates. Order: cached
     * OAuth → {@code gh auth token} → configured {@code apiKey} →
     * {@code GITHUB_TOKEN} → {@code GH_TOKEN}.
     *
     * <p>The first two positions match the pre-flight policy
     * ({@link #firstPreflightTokenCandidate()}), so whichever source let
     * the user past the first-time login screen is also the source the
     * Copilot quota gets billed to. Config / env tokens remain as
     * fallbacks for headless callers but cannot silently take over an
     * account the user already confirmed via {@code gh}.
     *
     * <p>Exposed so consumers that need the raw GitHub token (e.g. the
     * Copilot SDK sidecar, issue #3) can reuse this logic without
     * triggering Copilot's token-exchange flow.
     */
    public static List<String> collectGithubTokenCandidates(String configuredToken) {
        List<String> candidates = new ArrayList<>(5);
        addIfValid(candidates, CopilotDeviceAuth.loadCachedToken(), "cached OAuth token");
        addIfValid(candidates, resolveGhCliToken(), "gh CLI");
        addIfValid(candidates, configuredToken, "config");
        addIfValid(candidates, System.getenv("GITHUB_TOKEN"), "GITHUB_TOKEN");
        addIfValid(candidates, System.getenv("GH_TOKEN"), "GH_TOKEN");
        return candidates;
    }

    /**
     * Convenience wrapper around {@link #collectGithubTokenCandidates} that
     * returns the first candidate, or {@code null} if none are available.
     */
    public static String firstGithubTokenCandidate(String configuredToken) {
        var list = collectGithubTokenCandidates(configuredToken);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * Narrow probe used by the CLI's first-time-auth pre-flight: only checks
     * the cached device-code token and {@code gh auth token}. Other sources
     * (configured {@code apiKey}, {@code GITHUB_TOKEN}, {@code GH_TOKEN}) are
     * intentionally excluded — they are usable at runtime by the daemon, but
     * must not silently bypass the first-time login UX.
     *
     * @return cached device-code token, else gh CLI token, else {@code null}
     */
    public static String firstPreflightTokenCandidate() {
        String cached = CopilotDeviceAuth.loadCachedToken();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String ghCli = resolveGhCliToken();
        if (ghCli != null && !ghCli.isBlank()) {
            return ghCli;
        }
        return null;
    }

    private static void addIfValid(List<String> candidates, String token, String source) {
        if (token != null && !token.isBlank() && !candidates.contains(token)) {
            log.debug("Found GitHub token candidate from {}", source);
            candidates.add(token);
        }
    }

    private static String resolveGhCliToken() {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            int exitCode = process.waitFor();
            if (exitCode == 0 && output != null && !output.isBlank()) {
                log.info("Resolved GitHub token from gh CLI");
                return output.trim();
            }
        } catch (Exception e) {
            log.debug("Could not resolve token from gh CLI: {}", e.getMessage());
        }
        return null;
    }

    private void exchange(String githubToken) {
        log.info("Attempting Copilot token exchange...");
        try {
            // OAuth tokens use "token" prefix; PATs use "Bearer"
            String authPrefix = (githubToken.startsWith("github_pat_") || githubToken.startsWith("ghp_"))
                    ? "Bearer " : "token ";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_EXCHANGE_URL))
                    .timeout(EXCHANGE_TIMEOUT)
                    .header("Authorization", authPrefix + githubToken)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("editor-version", EDITOR_VERSION)
                    .header("editor-plugin-version", EDITOR_PLUGIN_VERSION)
                    .header("x-github-api-version", API_VERSION)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("No token in response");
            }

            this.activeToken = token;
            this.isExchangedToken = true;

            long expiresAtEpoch = root.path("expires_at").asLong(0);
            this.expiresAt = expiresAtEpoch > 0
                    ? Instant.ofEpochSecond(expiresAtEpoch)
                    : Instant.now().plusSeconds(1800);

            String endpoint = root.path("endpoints").path("api").asText(null);
            this.resolvedEndpoint = (endpoint != null && !endpoint.isBlank())
                    ? endpoint.replaceAll("/+$", "")
                    : DEFAULT_ENDPOINT;

            log.info("Copilot token exchanged: endpoint={}, expiresAt={}", resolvedEndpoint, expiresAt);

            // Query available models for diagnostics
            try {
                // Try multiple model list endpoints
                for (String modelsPath : List.of("/models", "/v1/models", "/chat/completions/models")) {
                    var modelsReq = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(resolvedEndpoint + modelsPath))
                            .timeout(EXCHANGE_TIMEOUT)
                            .header("Authorization", "Bearer " + token)
                            .header("openai-intent", "conversation-panel")
                            .header("editor-version", "vscode/1.95.0")
                            .header("editor-plugin-version", "copilot-chat/0.26.7")
                            .header("Copilot-Integration-Id", "vscode-chat")
                            .GET()
                            .build();
                    var modelsResp = httpClient.send(modelsReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    log.info("Copilot {} → {} body={}", modelsPath, modelsResp.statusCode(),
                            modelsResp.body().length() > 500
                                    ? modelsResp.body().substring(0, 500) + "..."
                                    : modelsResp.body());
                    if (modelsResp.statusCode() == 200) {
                        var modelsRoot = objectMapper.readTree(modelsResp.body());
                        var ids = new java.util.ArrayList<String>();
                        for (var m : modelsRoot.path("data")) {
                            ids.add(m.path("id").asText());
                        }
                        java.util.Collections.sort(ids);
                        log.info("Copilot available models: {}", ids);
                        break;
                    }
                }
            } catch (Exception modelsEx) {
                log.debug("Could not query Copilot models: {}", modelsEx.getMessage());
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Token exchange interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Token exchange failed: " + e.getMessage(), e);
        }
    }

    private static String maskToken(String token) {
        return token.length() > 8 ? token.substring(0, 8) + "..." : "***";
    }
}
