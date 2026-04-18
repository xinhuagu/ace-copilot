package dev.acecopilot.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolDescriptionLoader}.
 */
class ToolDescriptionLoaderTest {

    /** All tool names that must have description resource files. */
    private static final String[] ALL_TOOL_NAMES = {
            "read_file", "write_file", "edit_file", "bash",
            "glob", "grep", "list_directory",
            "web_fetch", "web_search", "browser",
            "screen_capture", "applescript", "memory"
    };

    @Test
    void allToolDescriptionsLoadSuccessfully() {
        for (var toolName : ALL_TOOL_NAMES) {
            var desc = ToolDescriptionLoader.load(toolName);
            assertNotNull(desc, "Description for " + toolName + " should not be null");
            assertFalse(desc.isBlank(), "Description for " + toolName + " should not be blank");
            assertFalse(desc.contains("no detailed description available"),
                    "Description for " + toolName + " should not be the fallback text");
        }
    }

    @Test
    void eachDescriptionContainsRelevantKeyword() {
        // Each description should mention something relevant to the tool
        assertContains("read_file", "file");
        assertContains("write_file", "write");
        assertContains("edit_file", "replace");
        assertContains("bash", "bash");
        assertContains("glob", "pattern");
        assertContains("grep", "regex");
        assertContains("list_directory", "directory");
        assertContains("web_fetch", "URL");
        assertContains("web_search", "search");
        assertContains("browser", "browser");
        assertContains("screen_capture", "screenshot");
        assertContains("applescript", "AppleScript");
        assertContains("memory", "memory");
    }

    @Test
    void fallbackForMissingResource() {
        var desc = ToolDescriptionLoader.load("nonexistent_tool_xyz");
        assertNotNull(desc);
        assertTrue(desc.contains("nonexistent_tool_xyz"),
                "Fallback should contain the tool name");
    }

    @Test
    void descriptionsAreCached() {
        // Load twice — should get the exact same String instance (identity check)
        var first = ToolDescriptionLoader.load("read_file");
        var second = ToolDescriptionLoader.load("read_file");
        assertSame(first, second, "Cached descriptions should be the same instance");
    }

    @Test
    void noDescriptionExceedsMaxLength() {
        int maxChars = 8000;
        for (var toolName : ALL_TOOL_NAMES) {
            var desc = ToolDescriptionLoader.load(toolName);
            assertTrue(desc.length() <= maxChars,
                    "Description for " + toolName + " exceeds " + maxChars +
                    " chars (actual: " + desc.length() + ")");
        }
    }

    private static void assertContains(String toolName, String keyword) {
        var desc = ToolDescriptionLoader.load(toolName);
        assertTrue(desc.toLowerCase().contains(keyword.toLowerCase()),
                "Description for " + toolName + " should contain '" + keyword + "'");
    }
}
