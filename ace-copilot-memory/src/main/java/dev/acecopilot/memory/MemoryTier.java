package dev.acecopilot.memory;

/**
 * The 8-tier memory hierarchy, ordered by priority (highest first).
 *
 * <p>Each tier represents a different source and scope of memory:
 * <ol>
 *   <li><strong>Soul</strong> (100) — Immutable core identity from SOUL.md</li>
 *   <li><strong>ManagedPolicy</strong> (90) — Organization-managed policies</li>
 *   <li><strong>WorkspaceMemory</strong> (80) — Project-specific ACE_COPILOT.md instructions</li>
 *   <li><strong>UserMemory</strong> (70) — Global user preferences from ~/.ace-copilot/ACE_COPILOT.md</li>
 *   <li><strong>LocalMemory</strong> (65) — Per-developer gitignored ACE_COPILOT.local.md</li>
 *   <li><strong>AutoMemory</strong> (60) — Agent-learned insights (JSONL + HMAC)</li>
 *   <li><strong>MarkdownMemory</strong> (55) — Persistent MEMORY.md + topic files</li>
 *   <li><strong>DailyJournal</strong> (50) — Append-only daily activity log</li>
 * </ol>
 */
public sealed interface MemoryTier {

    /** Display name for prompt assembly. */
    String displayName();

    /** Priority (higher = loaded earlier). */
    int priority();

    /** Immutable core identity loaded from SOUL.md. */
    record Soul() implements MemoryTier {
        @Override public String displayName() { return "Soul"; }
        @Override public int priority() { return 100; }
    }

    /** Organization-managed policies (reserved for future enterprise use). */
    record ManagedPolicy() implements MemoryTier {
        @Override public String displayName() { return "Managed Policy"; }
        @Override public int priority() { return 90; }
    }

    /** Project-specific instructions from workspace ACE_COPILOT.md files. */
    record WorkspaceMemory() implements MemoryTier {
        @Override public String displayName() { return "Workspace Memory"; }
        @Override public int priority() { return 80; }
    }

    /** Global user preferences from ~/.ace-copilot/ACE_COPILOT.md. */
    record UserMemory() implements MemoryTier {
        @Override public String displayName() { return "User Memory"; }
        @Override public int priority() { return 70; }
    }

    /** Local developer instructions from ACE_COPILOT.local.md (gitignored). */
    record LocalMemory() implements MemoryTier {
        @Override public String displayName() { return "Local Memory"; }
        @Override public int priority() { return 65; }
    }

    /** Agent-learned insights from auto-memory store. */
    record AutoMemory() implements MemoryTier {
        @Override public String displayName() { return "Auto-Memory"; }
        @Override public int priority() { return 60; }
    }

    /** Persistent markdown memory files (MEMORY.md + topic files). */
    record MarkdownMemory() implements MemoryTier {
        @Override public String displayName() { return "Markdown Memory"; }
        @Override public int priority() { return 55; }
    }

    /** Append-only daily activity journal. */
    record Journal() implements MemoryTier {
        @Override public String displayName() { return "Daily Journal"; }
        @Override public int priority() { return 50; }
    }
}
