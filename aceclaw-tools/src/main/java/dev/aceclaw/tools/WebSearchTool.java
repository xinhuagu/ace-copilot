package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Performs web searches using the Brave Search API.
 *
 * <p>Only registered when a Brave Search API key is configured. Returns a
 * numbered list of results with title, URL, and snippet.
 */
public final class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BRAVE_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 20;

    private final Path workingDir;
    private final String apiKey;
    private final HttpClient httpClient;

    public WebSearchTool(Path workingDir, String apiKey) {
        this.workingDir = workingDir;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("query", SchemaBuilder.string(
                        "The search query string"))
                .optionalProperty("max_results", SchemaBuilder.integer(
                        "Maximum number of results to return (default: 5, max: 20)"))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("query") || input.get("query").asText().isBlank()) {
            return new ToolResult("Missing required parameter: query", true);
        }

        var query = input.get("query").asText();
        int maxResults = DEFAULT_MAX_RESULTS;
        if (input.has("max_results") && !input.get("max_results").isNull()) {
            maxResults = Math.max(1, Math.min(input.get("max_results").asInt(DEFAULT_MAX_RESULTS), MAX_RESULTS_LIMIT));
        }

        log.debug("Web search: query='{}', maxResults={}", query, maxResults);

        try {
            return performSearch(query, maxResults);
        } catch (java.io.IOException e) {
            return new ToolResult("Search failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult("Search interrupted", true);
        }
    }

    private ToolResult performSearch(String query, int maxResults) throws Exception {
        var encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        var uri = URI.create(BRAVE_SEARCH_URL + "?q=" + encodedQuery + "&count=" + maxResults);

        var request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return new ToolResult("Brave Search API key is invalid or expired", true);
        }
        if (response.statusCode() == 429) {
            return new ToolResult("Brave Search rate limit exceeded. Please wait and try again.", true);
        }
        if (response.statusCode() >= 400) {
            return new ToolResult("Brave Search API error (HTTP " + response.statusCode() + ")", true);
        }

        var body = response.body();
        if (body == null || body.isEmpty()) {
            return new ToolResult("Empty response from Brave Search", true);
        }

        return formatResults(body, maxResults);
    }

    private ToolResult formatResults(String jsonBody, int maxResults) {
        try {
            var root = MAPPER.readTree(jsonBody);
            var webResults = root.path("web").path("results");

            if (!webResults.isArray() || webResults.isEmpty()) {
                return new ToolResult("No results found", false);
            }

            var sb = new StringBuilder();
            int count = 0;
            for (var result : webResults) {
                if (count >= maxResults) break;
                count++;

                var title = result.path("title").asText("(no title)");
                var url = result.path("url").asText("(no url)");
                var snippet = result.path("description").asText("");

                sb.append(count).append(". ").append(title).append('\n');
                sb.append("   URL: ").append(url).append('\n');
                if (!snippet.isEmpty()) {
                    sb.append("   ").append(snippet).append('\n');
                }
                sb.append('\n');
            }

            return new ToolResult(sb.toString().strip(), false);

        } catch (Exception e) {
            log.error("Failed to parse Brave Search response: {}", e.getMessage());
            return new ToolResult("Failed to parse search results: " + e.getMessage(), true);
        }
    }
}
