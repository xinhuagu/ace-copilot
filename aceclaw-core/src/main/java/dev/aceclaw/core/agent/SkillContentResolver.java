package dev.aceclaw.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Resolves the full content of a skill at invocation time.
 *
 * <p>Resolution involves three steps:
 * <ol>
 *   <li><b>Argument substitution</b> — {@code $ARGUMENTS}, {@code $1}, {@code $2}, etc.</li>
 *   <li><b>Command preprocessing</b> — lines matching {@code !`command`} are executed
 *       via ProcessBuilder and replaced with their stdout</li>
 *   <li><b>Environment variable substitution</b> — {@code ${VAR_NAME}} for a limited set</li>
 * </ol>
 */
public final class SkillContentResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillContentResolver.class);

    /** Matches !`command` patterns anywhere in text (the command is in group 1). */
    private static final Pattern COMMAND_PATTERN = Pattern.compile("!`([^`]+)`");

    /** Timeout for command execution in seconds. */
    private static final int COMMAND_TIMEOUT_SECONDS = 10;

    private final Path workingDir;

    /**
     * Creates a content resolver that executes commands relative to the given working directory.
     *
     * @param workingDir the working directory for command execution
     */
    public SkillContentResolver(Path workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * Resolves a skill's full content by applying argument substitution and command preprocessing.
     *
     * @param config    the skill configuration
     * @param arguments the raw arguments string (may be null or empty)
     * @return the fully resolved skill content
     */
    public String resolve(SkillConfig config, String arguments) {
        String body = config.body();
        if (body == null || body.isEmpty()) {
            return "";
        }

        // Step 1: Argument substitution
        body = substituteArguments(body, arguments);

        // Step 2: Environment variable substitution
        body = substituteEnvironmentVars(body);

        // Step 3: Command preprocessing
        body = executeCommands(body);

        return body;
    }

    /**
     * Substitutes argument placeholders in the body.
     *
     * <p>Supported placeholders:
     * <ul>
     *   <li>{@code $ARGUMENTS} — the full argument string</li>
     *   <li>{@code $1}, {@code $2}, ... {@code $N} — positional arguments (whitespace-split)</li>
     * </ul>
     */
    String substituteArguments(String body, String arguments) {
        if (arguments == null) {
            arguments = "";
        }
        String safeArgs = arguments.strip();

        // Replace $ARGUMENTS with the full argument string
        body = body.replace("$ARGUMENTS", safeArgs);

        // Split into positional arguments and replace $1, $2, etc.
        if (!safeArgs.isEmpty()) {
            String[] parts = safeArgs.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                body = body.replace("$" + (i + 1), parts[i]);
            }
        }

        return body;
    }

    /**
     * Substitutes a limited set of environment-style variables.
     */
    private String substituteEnvironmentVars(String body) {
        body = body.replace("${ACECLAW_PROJECT_DIR}", workingDir.toAbsolutePath().toString());

        String sessionId = System.getProperty("aceclaw.session.id", "");
        body = body.replace("${ACECLAW_SESSION_ID}", sessionId);

        return body;
    }

    /**
     * Executes inline commands matching the {@code !`command`} pattern.
     * Each matching line is replaced with the command's stdout output.
     * On failure, the line is replaced with an error message.
     */
    String executeCommands(String body) {
        var matcher = COMMAND_PATTERN.matcher(body);
        var result = new StringBuilder();

        while (matcher.find()) {
            String command = matcher.group(1);
            String output = executeCommand(command);
            // Escape backslashes and dollar signs for Matcher.appendReplacement
            matcher.appendReplacement(result, output.replace("\\", "\\\\").replace("$", "\\$"));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Executes a single shell command and returns its stdout output.
     * Returns an error message on failure or timeout.
     */
    private String executeCommand(String command) {
        Process process = null;
        try {
            var pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Skill command timed out ({}s): {}", COMMAND_TIMEOUT_SECONDS, command);
                return "[Command timed out after " + COMMAND_TIMEOUT_SECONDS + "s: " + command + "]";
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.debug("Skill command failed (exit {}): {}", exitCode, command);
                return "[Command failed: exit code " + exitCode + "]";
            }

            return output;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Skill command execution error: {} - {}", command, e.getMessage());
            return "[Command failed: " + e.getMessage() + "]";
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
