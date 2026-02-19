Invoke a skill to perform a specialized task.

Skills are structured knowledge packages with instructions, reference materials,
and supporting files. Each skill has a name and optional arguments.

Available skills are listed in the input schema. Use the `arguments` parameter
to pass context to the skill.

When to use this tool:
- The task matches a specific skill's description
- You need specialized instructions or templates for a task type
- The user explicitly requests a skill by name

When NOT to use this tool:
- For general-purpose tasks better handled directly with other tools
- When no matching skill exists

Skills run in two modes:
- Inline: the skill instructions are returned for you to follow
- Fork: a sub-agent executes the skill instructions independently