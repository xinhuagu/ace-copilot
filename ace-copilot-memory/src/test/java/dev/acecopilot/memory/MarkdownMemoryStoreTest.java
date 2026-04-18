package dev.acecopilot.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownMemoryStoreTest {

    @TempDir
    Path tempDir;

    private MarkdownMemoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        store = new MarkdownMemoryStore(memoryDir);
    }

    @Test
    void loadMemoryMdReturnsFirst200Lines() throws IOException {
        var lines = IntStream.rangeClosed(1, 250)
                .mapToObj(i -> "Line " + i)
                .toList();
        store.writeMemoryMd(String.join("\n", lines));

        String content = store.loadMemoryMd();
        assertThat(content).isNotNull();
        assertThat(content).contains("Line 1");
        assertThat(content).contains("Line 200");
        assertThat(content).doesNotContain("Line 201");
        assertThat(content).contains("Truncated at 200 lines");
    }

    @Test
    void loadMemoryMdReturnsNullWhenMissing() {
        assertThat(store.loadMemoryMd()).isNull();
    }

    @Test
    void writeAndReadMemoryMd() throws IOException {
        store.writeMemoryMd("# My Memory\n\nSome important notes.");

        String content = store.loadMemoryMd();
        assertThat(content).isNotNull();
        assertThat(content).contains("My Memory");
        assertThat(content).contains("Some important notes.");
    }

    @Test
    void writeAndReadTopicFile() throws IOException {
        store.writeTopicFile("debugging.md", "# Debugging Tips\n\n- Use breakpoints");

        String content = store.readTopicFile("debugging.md");
        assertThat(content).isNotNull();
        assertThat(content).contains("Debugging Tips");
        assertThat(content).contains("breakpoints");
    }

    @Test
    void listTopicFiles() throws IOException {
        store.writeTopicFile("debugging.md", "debug notes");
        store.writeTopicFile("patterns.md", "pattern notes");
        store.writeMemoryMd("main memory");

        var files = store.listTopicFiles();
        assertThat(files).containsExactly("debugging.md", "patterns.md");
        assertThat(files).doesNotContain("MEMORY.md");
    }

    @Test
    void fileSizeLimitEnforced() {
        // 50KB limit per file
        String largeContent = "x".repeat(51 * 1024);
        assertThatThrownBy(() -> store.writeMemoryMd(largeContent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds maximum size");
    }

    @Test
    void totalSizeLimitEnforced() throws IOException {
        // Write files up to near the 500KB total limit
        for (int i = 0; i < 10; i++) {
            store.writeTopicFile("topic" + i + ".md", "x".repeat(49 * 1024));
        }
        // Next file should push over the limit
        assertThatThrownBy(() -> store.writeTopicFile("overflow.md", "x".repeat(49 * 1024)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Total memory size");
    }

    @Test
    void workspaceIsolation() throws IOException {
        Path workspace1 = tempDir.resolve("ws1");
        Path workspace2 = tempDir.resolve("ws2");
        Files.createDirectories(workspace1);
        Files.createDirectories(workspace2);

        var store1 = new MarkdownMemoryStore(workspace1.resolve("memory"));
        var store2 = new MarkdownMemoryStore(workspace2.resolve("memory"));

        store1.writeMemoryMd("workspace 1 notes");
        store2.writeMemoryMd("workspace 2 notes");

        assertThat(store1.loadMemoryMd()).contains("workspace 1");
        assertThat(store2.loadMemoryMd()).contains("workspace 2");
    }

    @Test
    void readTopicFileReturnsNullWhenMissing() {
        assertThat(store.readTopicFile("nonexistent.md")).isNull();
    }

    @Test
    void fileNameValidation() {
        assertThatThrownBy(() -> store.writeTopicFile("../escape.md", "bad"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.writeTopicFile("notmd.txt", "bad"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.writeTopicFile("MEMORY.md", "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
