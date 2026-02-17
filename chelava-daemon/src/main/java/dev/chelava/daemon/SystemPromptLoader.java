package dev.chelava.daemon;

import dev.chelava.memory.AutoMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and assembles the system prompt for the agent.
 *
 * <p>The system prompt is composed of:
 * <ol>
 *   <li>A built-in base prompt describing the agent's capabilities and behavior</li>
 *   <li>Optional project-specific instructions from {@code CHELAVA.md} in the project root</li>
 * </ol>
 *
 * <p>If a {@code CHELAVA.md} file exists in the project directory, its contents are
 * appended to the base prompt under a "Project Instructions" section.
 */
public final class SystemPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptLoader.class);

    private static final String CHELAVA_MD = "CHELAVA.md";

    private SystemPromptLoader() {}

    /** Global chelava config directory. */
    private static final Path GLOBAL_CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".chelava");

    /**
     * Loads the full system prompt for the given project directory.
     *
     * <p>Memory hierarchy (all loaded, later sources override earlier):
     * <ol>
     *   <li>Built-in base prompt (agent capabilities, guidelines)</li>
     *   <li>Global user instructions from {@code ~/.chelava/CHELAVA.md}</li>
     *   <li>Project-specific instructions from {@code {project}/CHELAVA.md}</li>
     *   <li>Project .chelava instructions from {@code {project}/.chelava/CHELAVA.md}</li>
     * </ol>
     *
     * @param projectPath the project working directory
     * @return the assembled system prompt
     */
    public static String load(Path projectPath) {
        return load(projectPath, null);
    }

    /**
     * Loads the full system prompt with auto-memory injection.
     *
     * @param projectPath the project working directory
     * @param memoryStore optional auto-memory store (may be null)
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore) {
        var sb = new StringBuilder();
        sb.append(basePrompt());

        // 1. Global user instructions (~/.chelava/CHELAVA.md)
        appendInstructions(sb, GLOBAL_CONFIG_DIR.resolve(CHELAVA_MD),
                "User Instructions", "your global ~/.chelava/CHELAVA.md file");

        // 2. Project root instructions ({project}/CHELAVA.md)
        appendInstructions(sb, projectPath.resolve(CHELAVA_MD),
                "Project Instructions", "the project's CHELAVA.md file");

        // 3. Project .chelava dir instructions ({project}/.chelava/CHELAVA.md)
        appendInstructions(sb, projectPath.resolve(".chelava").resolve(CHELAVA_MD),
                "Project Configuration Instructions", "the project's .chelava/CHELAVA.md file");

        // 4. Auto-memory (learned insights from previous sessions)
        if (memoryStore != null && memoryStore.size() > 0) {
            String memorySection = memoryStore.formatForPrompt(projectPath, 50);
            if (!memorySection.isEmpty()) {
                sb.append(memorySection);
                log.info("Injected {} auto-memory entries into system prompt", memoryStore.size());
            }
        }

        return sb.toString();
    }

    /**
     * Appends instructions from a CHELAVA.md file if it exists.
     */
    private static void appendInstructions(StringBuilder sb, Path file,
                                           String sectionTitle, String sourceDesc) {
        if (!Files.isRegularFile(file)) return;
        try {
            var content = Files.readString(file);
            if (!content.isBlank()) {
                sb.append("\n\n# ").append(sectionTitle).append("\n\n");
                sb.append("The following instructions are from ").append(sourceDesc).append(". ");
                sb.append("Follow them carefully.\n\n");
                sb.append(content.strip());
                log.info("Loaded {} from {}", sectionTitle.toLowerCase(), file);
            }
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
        }
    }

    /**
     * Returns the built-in base system prompt.
     */
    private static String basePrompt() {
        return """
                You are Chelava, an AI coding assistant that helps users with software engineering tasks.

                # Capabilities

                You have access to the following tools:
                - read_file: Read file contents with line numbers
                - write_file: Create or overwrite files
                - edit_file: Make targeted edits to existing files (find and replace)
                - bash: Execute shell commands
                - glob: Search for files by glob pattern
                - grep: Search file contents by regex pattern

                # Guidelines

                - Always read a file before editing it to understand the existing code
                - Prefer editing existing files over creating new ones
                - Keep changes minimal and focused on what was requested
                - Do not introduce security vulnerabilities (command injection, XSS, SQL injection, etc.)
                - When executing bash commands, be careful with destructive operations
                - Explain what you are doing and why before making changes
                - If you are unsure about something, ask for clarification
                - Write clean, idiomatic code that follows the project's existing conventions
                - Do not add unnecessary comments, docstrings, or type annotations to code you did not change

                # Behavior

                - You communicate in natural language only. No slash commands.
                - You autonomously decide which tools to use based on the user's request.
                - You handle file operations, code modifications, and shell commands as needed.
                - When multiple independent operations are needed, you may request parallel tool execution.
                - You are thorough but concise in your responses.
                """;
    }
}
