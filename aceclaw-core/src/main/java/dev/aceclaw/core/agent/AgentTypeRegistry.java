package dev.aceclaw.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Registry of available sub-agent types (built-in and custom).
 *
 * <p>Built-in types: {@code explore}, {@code plan}, {@code general}, {@code bash}.
 * Custom types are loaded from {@code .aceclaw/agents/*.md} files with YAML frontmatter.
 *
 * <p>Agent file format:
 * <pre>
 * ---
 * description: "Fast read-only codebase exploration"
 * model: claude-haiku-4-5-20251001
 * allowed_tools:
 *   - read_file
 *   - glob
 *   - grep
 * max_turns: 15
 * ---
 *
 * You are a codebase exploration agent. Search files and report findings.
 * </pre>
 */
public final class AgentTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentTypeRegistry.class);
    private static final String AGENTS_DIR = ".aceclaw/agents";

    private final Map<String, SubAgentConfig> registry;

    private AgentTypeRegistry(Map<String, SubAgentConfig> registry) {
        // Use unmodifiableMap to preserve LinkedHashMap insertion order
        // (Map.copyOf does not guarantee order)
        this.registry = Collections.unmodifiableMap(new LinkedHashMap<>(registry));
    }

    /**
     * Creates a registry with only the 4 built-in agent types.
     */
    public static AgentTypeRegistry withBuiltins() {
        var map = new LinkedHashMap<String, SubAgentConfig>();
        for (var config : builtinTypes()) {
            map.put(config.name(), config);
        }
        return new AgentTypeRegistry(map);
    }

    /**
     * Creates a registry with built-in types plus custom agents from the project directory.
     *
     * @param projectPath the project root directory
     * @return registry with all available agent types
     */
    public static AgentTypeRegistry load(Path projectPath) {
        var map = new LinkedHashMap<String, SubAgentConfig>();
        for (var config : builtinTypes()) {
            map.put(config.name(), config);
        }

        Path agentsDir = projectPath.resolve(AGENTS_DIR);
        if (Files.isDirectory(agentsDir)) {
            try (Stream<Path> stream = Files.list(agentsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                var config = parseAgentFile(file);
                                if (config != null) {
                                    if (map.containsKey(config.name())) {
                                        log.warn("Custom agent '{}' overrides built-in type", config.name());
                                    }
                                    map.put(config.name(), config);
                                }
                            } catch (Exception e) {
                                log.warn("Skipping malformed agent file {}: {}",
                                        file.getFileName(), e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to scan agents directory: {}", e.getMessage());
            }
        }

        log.debug("Agent type registry loaded: {}", map.keySet());
        return new AgentTypeRegistry(map);
    }

    /**
     * Looks up an agent type by name.
     */
    public Optional<SubAgentConfig> get(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * Returns all registered agent type names (ordered: builtins first, then custom).
     */
    public List<String> names() {
        return List.copyOf(registry.keySet());
    }

    /**
     * Returns all registered agent configurations.
     */
    public List<SubAgentConfig> all() {
        return List.copyOf(registry.values());
    }

    // -- Built-in types -------------------------------------------------------

    private static List<SubAgentConfig> builtinTypes() {
        return List.of(
                new SubAgentConfig(
                        "explore",
                        "Fast read-only codebase exploration. Use for searching files, finding definitions, and understanding code structure.",
                        "claude-haiku-4-5-20251001",
                        List.of("read_file", "glob", "grep", "list_directory"),
                        List.of(),
                        SubAgentConfig.DEFAULT_MAX_TURNS,
                        """
                        You are a codebase exploration agent. Your task is to search files, find definitions, \
                        and report findings concisely. You have read-only access to the codebase.

                        Working directory: %s

                        Guidelines:
                        - Search broadly first, then drill down into relevant files
                        - Report file paths and line numbers for key findings
                        - Summarize patterns and structure, don't just dump file contents
                        - Be thorough but concise — the parent agent needs actionable information
                        """
                ),
                new SubAgentConfig(
                        "plan",
                        "Research and planning agent with read-only access plus bash. Use for investigating issues, profiling, and designing implementation approaches.",
                        null,
                        List.of("read_file", "glob", "grep", "list_directory", "bash"),
                        List.of(),
                        SubAgentConfig.DEFAULT_MAX_TURNS,
                        """
                        You are a planning and research agent. Investigate the codebase, run commands to gather \
                        information, and produce a clear implementation plan.

                        Working directory: %s

                        Guidelines:
                        - Explore the codebase thoroughly before proposing changes
                        - Identify affected files, dependencies, and potential risks
                        - Present a step-by-step plan with specific file paths and changes
                        - Note any ambiguities or decisions that need user input
                        """
                ),
                new SubAgentConfig(
                        "general",
                        "General-purpose agent with full tool access (minus task delegation). Use for complex multi-step tasks that need file editing, bash execution, etc.",
                        null,
                        List.of(),
                        List.of(),
                        SubAgentConfig.DEFAULT_MAX_TURNS,
                        """
                        You are a general-purpose agent handling a delegated task. Complete the task thoroughly \
                        and report what you did.

                        Working directory: %s

                        Guidelines:
                        - Complete the task as described in the prompt
                        - Be thorough — verify your changes compile or work as expected
                        - Report what files were modified and what was done
                        """
                ),
                new SubAgentConfig(
                        "bash",
                        "Isolated terminal execution agent. Use for running commands, scripts, or build tasks in an isolated context.",
                        null,
                        List.of("bash"),
                        List.of(),
                        SubAgentConfig.DEFAULT_MAX_TURNS,
                        """
                        You are a command execution agent. Run the requested commands and report results.

                        Working directory: %s

                        Guidelines:
                        - Execute the commands as requested
                        - Report stdout/stderr output clearly
                        - If a command fails, explain what went wrong
                        """
                )
        );
    }

    // -- YAML frontmatter parser (reuses pattern from RuleEngine) ---------------

    /**
     * Parses a custom agent file with YAML frontmatter.
     * Returns null if the file has no valid frontmatter.
     */
    static SubAgentConfig parseAgentFile(Path file) throws IOException {
        String content = Files.readString(file);
        String[] lines = content.split("\n");

        // Find frontmatter boundaries (--- ... ---)
        int firstDelim = -1;
        int secondDelim = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                if (firstDelim == -1) {
                    firstDelim = i;
                } else {
                    secondDelim = i;
                    break;
                }
            }
        }

        if (firstDelim == -1 || secondDelim == -1) {
            log.debug("No frontmatter found in {}", file.getFileName());
            return null;
        }

        // Parse frontmatter fields
        String description = "";
        String model = null;
        var allowedTools = new ArrayList<String>();
        var disallowedTools = new ArrayList<String>();
        int maxTurns = SubAgentConfig.DEFAULT_MAX_TURNS;

        String currentList = null;
        for (int i = firstDelim + 1; i < secondDelim; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("description:")) {
                currentList = null;
                description = stripQuotes(trimmed.substring("description:".length()).trim());
            } else if (trimmed.startsWith("model:")) {
                currentList = null;
                model = stripQuotes(trimmed.substring("model:".length()).trim());
                if (model.isEmpty()) model = null;
            } else if (trimmed.startsWith("max_turns:")) {
                currentList = null;
                try {
                    maxTurns = Integer.parseInt(trimmed.substring("max_turns:".length()).trim());
                } catch (NumberFormatException e) {
                    // keep default
                }
            } else if (trimmed.startsWith("allowed_tools:")) {
                currentList = "allowed";
                String value = trimmed.substring("allowed_tools:".length()).trim();
                if (!value.isEmpty()) {
                    parseInlineList(value, allowedTools);
                    currentList = null;
                }
            } else if (trimmed.startsWith("disallowed_tools:")) {
                currentList = "disallowed";
                String value = trimmed.substring("disallowed_tools:".length()).trim();
                if (!value.isEmpty()) {
                    parseInlineList(value, disallowedTools);
                    currentList = null;
                }
            } else if (currentList != null && trimmed.startsWith("- ")) {
                String item = stripQuotes(trimmed.substring(2).trim());
                if (!item.isEmpty()) {
                    if ("allowed".equals(currentList)) {
                        allowedTools.add(item);
                    } else {
                        disallowedTools.add(item);
                    }
                }
            } else if (!trimmed.isEmpty() && currentList != null) {
                currentList = null;
            }
        }

        // Extract body (everything after second ---)
        var bodyBuilder = new StringBuilder();
        for (int i = secondDelim + 1; i < lines.length; i++) {
            bodyBuilder.append(lines[i]).append("\n");
        }
        String body = bodyBuilder.toString().strip();

        // Derive name from filename (without .md extension)
        String name = file.getFileName().toString().replaceAll("\\.md$", "");

        return new SubAgentConfig(name, description, model, allowedTools, disallowedTools, maxTurns, body);
    }

    private static void parseInlineList(String value, List<String> target) {
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            for (String part : inner.split(",")) {
                String item = stripQuotes(part.trim());
                if (!item.isEmpty()) {
                    target.add(item);
                }
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            if ((s.startsWith("\"") && s.endsWith("\"")) ||
                    (s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
