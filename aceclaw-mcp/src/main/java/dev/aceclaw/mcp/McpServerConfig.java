package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses MCP server configuration from Claude Code-compatible {@code .mcp.json} files
 * and AceClaw-specific {@code mcp-servers.json} files.
 *
 * <p>Config loading order (later overrides earlier):
 * <ol>
 *   <li>{@code ~/.aceclaw/mcp-servers.json} (global)</li>
 *   <li>{@code {project}/.mcp.json} (project, Claude Code compatible)</li>
 *   <li>{@code {project}/.aceclaw/mcp-servers.json} (project AceClaw-specific)</li>
 * </ol>
 *
 * <p>Expected JSON format:
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "server-name": {
 *       "command": "npx",
 *       "args": ["-y", "@package/mcp-server@latest"],
 *       "env": { "KEY": "value" }
 *     }
 *   }
 * }
 * }</pre>
 */
public final class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A single MCP server entry from configuration.
     *
     * @param command the executable command (e.g. "npx", "node", "python")
     * @param args    command-line arguments
     * @param env     environment variables to set for the server process
     */
    public record ServerEntry(String command, List<String> args, Map<String, String> env) {

        public ServerEntry {
            args = args != null ? List.copyOf(args) : List.of();
            env = env != null ? Map.copyOf(env) : Map.of();
        }
    }

    private McpServerConfig() {}

    /**
     * Loads MCP server configuration by merging all config sources.
     *
     * @param workingDir the project working directory
     * @return merged server configurations, keyed by server name
     */
    public static Map<String, ServerEntry> load(Path workingDir) {
        var result = new LinkedHashMap<String, ServerEntry>();

        // 1. Global: ~/.aceclaw/mcp-servers.json
        var globalConfig = Path.of(System.getProperty("user.home"), ".aceclaw", "mcp-servers.json");
        mergeFrom(result, globalConfig);

        // 2. Project: {workingDir}/.mcp.json (Claude Code compatible)
        mergeFrom(result, workingDir.resolve(".mcp.json"));

        // 3. Project: {workingDir}/.aceclaw/mcp-servers.json
        mergeFrom(result, workingDir.resolve(".aceclaw").resolve("mcp-servers.json"));

        if (!result.isEmpty()) {
            log.info("Loaded {} MCP server(s): {}", result.size(), result.keySet());
        }

        return Collections.unmodifiableMap(result);
    }

    private static void mergeFrom(Map<String, ServerEntry> target, Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return;
        }

        try {
            var root = MAPPER.readTree(Files.readString(configFile));
            var servers = root.get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.debug("No mcpServers key in {}", configFile);
                return;
            }

            var it = servers.fields();
            while (it.hasNext()) {
                var entry = it.next();
                var name = entry.getKey();
                var value = entry.getValue();

                var command = value.has("command") ? value.get("command").asText() : null;
                if (command == null || command.isBlank()) {
                    log.warn("MCP server '{}' in {} has no command; skipping", name, configFile);
                    continue;
                }

                var args = new java.util.ArrayList<String>();
                if (value.has("args") && value.get("args").isArray()) {
                    for (var arg : value.get("args")) {
                        args.add(arg.asText());
                    }
                }

                var env = new LinkedHashMap<String, String>();
                if (value.has("env") && value.get("env").isObject()) {
                    var envIt = value.get("env").fields();
                    while (envIt.hasNext()) {
                        var envEntry = envIt.next();
                        env.put(envEntry.getKey(), envEntry.getValue().asText());
                    }
                }

                target.put(name, new ServerEntry(command, args, env));
                log.debug("Loaded MCP server '{}' from {}: {} {}", name, configFile, command, args);
            }
        } catch (IOException e) {
            log.warn("Failed to parse MCP config {}: {}", configFile, e.getMessage());
        }
    }
}
