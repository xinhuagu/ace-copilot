package dev.aceclaw.memory;

import java.util.List;

/**
 * A conditional rule with file glob pattern matching.
 *
 * <p>Rules are loaded from {@code {project}/.aceclaw/rules/*.md} files and
 * matched against file paths during agent operation to inject contextual
 * instructions into the system prompt.
 *
 * @param name     rule file name (e.g. "test-conventions")
 * @param patterns glob patterns (e.g. ["*.test.java", "**&#47;*Test.java"])
 * @param content  markdown rule content
 */
public record PathBasedRule(
        String name,
        List<String> patterns,
        String content
) {}
