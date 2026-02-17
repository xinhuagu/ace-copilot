package dev.aceclaw.mcp;

import dev.aceclaw.core.agent.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the lifecycle of MCP server processes and their client connections.
 *
 * <p>For each configured server, spawns the process via {@link StdioClientTransport},
 * initializes the MCP protocol handshake, discovers available tools, and creates
 * {@link McpToolBridge} adapters for registration in AceClaw's {@link dev.aceclaw.core.agent.ToolRegistry}.
 *
 * <p>Implements {@link AutoCloseable} for graceful shutdown of all server processes.
 */
public final class McpClientManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private static final int MAX_START_ATTEMPTS = 3;
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Health status of an individual MCP server.
     */
    public enum ServerStatus {
        STARTING, CONNECTED, FAILED, SHUTDOWN
    }

    private final Map<String, McpServerConfig.ServerEntry> serverConfigs;
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();
    private final List<Tool> bridgedTools = new ArrayList<>();

    /**
     * Creates a manager for the given server configurations.
     *
     * @param serverConfigs map of server name to configuration entry
     */
    public McpClientManager(Map<String, McpServerConfig.ServerEntry> serverConfigs) {
        this.serverConfigs = serverConfigs;
    }

    /**
     * Starts all configured MCP servers, initializes connections, and discovers tools.
     *
     * <p>Each server gets up to {@value MAX_START_ATTEMPTS} start attempts with exponential backoff.
     * Failed servers are logged and skipped; the daemon still starts normally.
     */
    public void start() {
        for (var entry : serverConfigs.entrySet()) {
            var serverName = entry.getKey();
            var config = entry.getValue();
            statuses.put(serverName, ServerStatus.STARTING);

            McpSyncClient client = null;
            Exception lastError = null;

            for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
                try {
                    client = createAndInitialize(serverName, config);
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("MCP server '{}' start attempt {}/{} failed: {}",
                            serverName, attempt, MAX_START_ATTEMPTS, e.getMessage());
                    if (attempt < MAX_START_ATTEMPTS) {
                        try {
                            Thread.sleep(BACKOFF_BASE.toMillis() * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (client != null) {
                clients.put(serverName, client);
                statuses.put(serverName, ServerStatus.CONNECTED);
                discoverAndBridgeTools(serverName, client);
            } else {
                statuses.put(serverName, ServerStatus.FAILED);
                log.error("MCP server '{}' failed to start after {} attempts: {}",
                        serverName, MAX_START_ATTEMPTS,
                        lastError != null ? lastError.getMessage() : "unknown error");
            }
        }

        log.info("MCP client manager started: {}/{} servers connected, {} tools discovered",
                clients.size(), serverConfigs.size(), bridgedTools.size());
    }

    /**
     * Returns all bridged MCP tools adapted to the AceClaw {@link Tool} interface.
     */
    public List<Tool> bridgedTools() {
        return Collections.unmodifiableList(bridgedTools);
    }

    /**
     * Returns the health status of each configured server.
     */
    public Map<String, ServerStatus> serverStatus() {
        return Collections.unmodifiableMap(statuses);
    }

    @Override
    public void close() {
        for (var entry : clients.entrySet()) {
            var serverName = entry.getKey();
            var client = entry.getValue();
            try {
                client.closeGracefully();
                statuses.put(serverName, ServerStatus.SHUTDOWN);
                log.info("MCP server '{}' shut down gracefully", serverName);
            } catch (Exception e) {
                log.warn("Error shutting down MCP server '{}': {}", serverName, e.getMessage());
            }
        }
        clients.clear();
    }

    private McpSyncClient createAndInitialize(String serverName, McpServerConfig.ServerEntry config) {
        log.info("Starting MCP server '{}': {} {}", serverName, config.command(), config.args());

        // Build server parameters
        var paramsBuilder = ServerParameters.builder(config.command());
        if (!config.args().isEmpty()) {
            paramsBuilder.args(config.args().toArray(new String[0]));
        }
        if (!config.env().isEmpty()) {
            paramsBuilder.env(config.env());
        }
        var params = paramsBuilder.build();

        // Create transport and client
        var jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        var transport = new StdioClientTransport(params, jsonMapper);
        var client = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();

        // Initialize (protocol handshake)
        client.initialize();
        log.info("MCP server '{}' initialized successfully", serverName);

        return client;
    }

    private void discoverAndBridgeTools(String serverName, McpSyncClient client) {
        try {
            var toolsResult = client.listTools();
            var tools = toolsResult.tools();

            if (tools.isEmpty()) {
                log.info("MCP server '{}' has no tools", serverName);
                return;
            }

            for (var mcpTool : tools) {
                var bridged = McpToolBridge.create(serverName, mcpTool, client);
                bridgedTools.add(bridged);
                log.debug("Bridged MCP tool: {}", bridged.name());
            }

            log.info("MCP server '{}' provides {} tool(s): {}", serverName, tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
        } catch (Exception e) {
            log.warn("Failed to discover tools from MCP server '{}': {}", serverName, e.getMessage());
        }
    }
}
