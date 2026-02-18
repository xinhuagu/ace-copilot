package dev.aceclaw.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads tool descriptions from classpath resource files.
 *
 * <p>Descriptions are loaded from {@code tool-descriptions/{toolName}.md}
 * and cached for the lifetime of the JVM.
 */
final class ToolDescriptionLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolDescriptionLoader.class);

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private ToolDescriptionLoader() {}

    /**
     * Loads the description for the given tool name from the classpath.
     *
     * @param toolName the tool name (e.g. "read_file", "bash")
     * @return the description text, or a brief fallback if the resource is not found
     */
    static String load(String toolName) {
        return CACHE.computeIfAbsent(toolName, ToolDescriptionLoader::loadFromClasspath);
    }

    private static String loadFromClasspath(String toolName) {
        var resourcePath = "tool-descriptions/" + toolName + ".md";
        try (InputStream is = ToolDescriptionLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Tool description resource not found: {}", resourcePath);
                return toolName + " tool (no detailed description available)";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            log.error("Failed to load tool description: {}", resourcePath, e);
            return toolName + " tool (failed to load description)";
        }
    }
}
