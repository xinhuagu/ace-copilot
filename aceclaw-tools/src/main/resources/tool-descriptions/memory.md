Manages persistent memory that survives across sessions. Memories are HMAC-signed
and stored as JSONL files. Use this to learn from experience and avoid repeating mistakes.

Three actions:
- save: Store a new memory. Requires content and category.
- search: Find relevant memories using a natural language query. Uses hybrid TF-IDF + recency ranking.
- list: Browse memories, optionally filtered by category.

When to save (IMPORTANT — actively save learnings):
- After discovering a bug pattern, gotcha, or non-obvious behavior
- When the user corrects you — save as CORRECTION or MISTAKE
- When learning a project convention, naming pattern, or coding style preference
- After finding a strategy that worked well for a particular type of task
- When noting environment-specific configuration (paths, versions, tools)
- After a failed approach — save what went wrong and what worked instead
- When discovering relationships between components or systems

When to search:
- Before starting a complex or unfamiliar task — check if past sessions have relevant insights
- When encountering an error that might have been seen before
- When unsure about project conventions or preferences
- When working on a component you've previously modified

When to list:
- To review what has been learned about the project
- To check if a memory already exists before saving a duplicate
- To get an overview of accumulated knowledge

Anti-patterns — do NOT:
- Save trivial or obvious information (basic language syntax, common patterns)
- Search on every single turn — only search when genuinely useful
- Save information already present in the system prompt or CLAUDE.md
- Save raw file contents — summarize the INSIGHT instead
- Save duplicate memories — list/search first if you think it might exist already

Writing good memory content:
- Be concise and actionable — one clear insight per memory, not a wall of text
- Focus on the WHY and the FIX, not just the symptom
- Include enough context that future-you can understand without re-reading the source
- Good: "Gradle 8.14 removed javaPlatform.allowDependenciesInConstraints() — just omit the call"
- Good: "User prefers no Co-Authored-By lines in commit messages"
- Bad: "There was an error in the build" (too vague, no actionable insight)
- Bad: entire file contents copy-pasted (use a summary instead)

Categories:
- mistake: Errors made and lessons learned
- pattern: Code patterns, naming conventions, architectural patterns
- preference: User preferences for code style, tools, workflows
- codebase_insight: Knowledge about the specific codebase structure
- strategy: Approaches that work well for certain tasks
- workflow: Multi-step processes and procedures
- environment: System config, paths, versions, tool setup
- relationship: How components, modules, or systems connect
- terminology: Project-specific terms and their meanings
- constraint: Limitations, rules, or requirements to follow
- decision: Architectural or design decisions and their rationale
- tool_usage: Tips for using specific tools or commands effectively
- communication: User communication preferences
- context: Background information about the project or task
- correction: User corrections to agent behavior
- bookmark: Important file paths or references to remember
- session_summary: Summary of a session's key actions and outcomes
- error_recovery: An error encountered and how it was resolved
- successful_strategy: A strategy that proved successful for a specific task type
- anti_pattern: An approach that failed or should be avoided
- user_feedback: User feedback about agent behavior (positive or negative)

Parameter details:
- action: save, search, or list (required)
- content: The memory text (required for save). Write concise, actionable insights.
- category: One of the categories above (required for save, optional filter for search/list)
- tags: Comma-separated tags for categorization (optional, for save). Helps narrow future searches.
- query: Natural language search query (required for search). Be specific for better results.
- limit: Max results (default: 10 for search, 20 for list, max: 50)
- global: If true, memory is cross-project. Default: false (project-scoped).

Common workflows:
- Learn from mistake: encounter error → fix it → save with category=mistake and tags
- Pre-task check: search for relevant memories → review insights → start the task informed
- Convention capture: user states a preference → save with category=preference → apply in future
- Dedup check: search or list by category → verify no similar memory exists → save if new

Search scoring:
- Results are ranked by a hybrid score: TF-IDF text relevance + recency boost.
- Recent memories score higher when relevance is similar — fresh insights beat stale ones.
- Use specific, descriptive queries for better ranking (not single keywords).
- Category filter narrows results before scoring, improving precision.

How memory works behind the scenes:
- Memories you save via this tool are "Auto-Memory" (tier 6 of 8 in the memory hierarchy).
- Higher-priority tiers are loaded automatically and are NOT managed by this tool:
  Tier 1 (Soul): Agent identity from SOUL.md — defines core behavior and values.
  Tier 2 (Managed Policy): Enterprise rules from managed-policy.md (optional).
  Tier 3 (Workspace): Project instructions from ACECLAW.md in the project root.
  Tier 4 (User): Personal preferences from ~/.aceclaw/ACECLAW.md.
  Tier 5 (Local): Per-developer settings from ACECLAW.local.md (gitignored).
  Tier 6 (Auto-Memory): What THIS tool manages — agent-learned insights (JSONL).
  Tier 7 (Markdown Memory): Persistent MEMORY.md + topic files (first 200 lines injected).
  Tier 8 (Journal): Daily session log — appended automatically after each turn.
- The system prompt already contains content from tiers 1-5 and recent journal entries.
  This tool lets you actively manage tier 6 (auto-memory) to persist learnings.
  You can also write MEMORY.md / topic files via standard file tools (tier 7).
- During context compaction, the system automatically extracts file paths, commands, and
  errors from the conversation and saves them as auto-memories — you do not need to
  manually save these routine context items.
- The daily journal (tier 8) records each turn automatically. You do not need to save
  session activity manually — focus on saving non-obvious INSIGHTS instead.
- Path-based rules from {project}/.aceclaw/rules/*.md are injected when matching files
  are being worked on — these provide contextual instructions per file type.

Safety & constraints:
- Memory files are HMAC-SHA256 signed — tampered entries are rejected on load.
- Project-scoped memories (global=false) are stored per working directory (SHA-256 hash).
- Global memories (global=true) are shared across all projects — use sparingly.
- Never save secrets, API keys, passwords, or credentials as memories.
- Memory storage is append-only JSONL — entries accumulate over time.
- Do not save information that tiers 0-3 already provide (SOUL.md, ACECLAW.md, etc.).

Tips:
- Use tags liberally — they make future searches much more precise (e.g., "gradle, java, build").
- Save immediately after learning something, not at the end of the session.
- Search with natural phrases, not keywords: "how to fix Gradle annotation processor" not "gradle".
- When in doubt about whether to save, save it — a slightly redundant memory costs less than
  re-discovering the same insight from scratch in a future session.
- Use category filter in search when you know the type of insight you need.
- You do NOT need to save routine context (file paths, commands run) — context compaction
  handles that automatically. Save higher-level insights that compaction cannot infer.