Performs exact string replacements in files. This is the PRIMARY tool for modifying code.

Searches for old_string in the file and replaces it with new_string. The match must be
exact — including whitespace, indentation, and line breaks. This is NOT regex replacement.

IMPORTANT — Use this tool INSTEAD of:
- sed, awk, perl -i, ex (editing files via bash)
- Any bash command whose purpose is to modify file contents

CRITICAL RULES:
- You MUST read the file first with read_file before editing. This tool errors on unread files.
- old_string must EXACTLY match text in the file, including all whitespace and indentation.
- The edit FAILS if old_string is not found or matches multiple locations.
- old_string and new_string must be different — no-op edits are rejected.
- NEVER include line numbers from read_file output in old_string.

When to use:
- Modifying existing code: fixing bugs, updating functions, changing imports
- Renaming variables/functions/classes across the file (use replace_all=true)
- Adding new code at a specific location (include surrounding context in old_string)
- Removing code (set new_string to empty or to surrounding code without the deleted part)

When NOT to use:
- Creating a brand new file — use write_file instead
- Replacing the entire file content — use write_file instead
- The file has not been read yet — read it first

Parameter details:
- file_path: Absolute or relative path to the file to edit.
- old_string: The exact text to find. Must be unique in the file unless replace_all=true.
  Include 2-3 lines of surrounding context to ensure uniqueness.
- new_string: The replacement text. Must differ from old_string.
- replace_all: If true, replaces ALL occurrences. Default false (requires unique match).

Common failure modes and fixes:
- "old_string not found": Content changed since you read it. Re-read the file and try again.
- "not unique (found N occurrences)": Add more surrounding context to old_string, or use replace_all.
- Wrong indentation: Copy the exact whitespace from read_file output (after the line number prefix).
- Stale content: Always re-read the file if a previous edit might have changed it.

Common workflows:
- Modify code: read_file → identify the section → edit_file → verify with read_file
- Rename: read_file → edit_file with replace_all=true → verify
- Multi-edit: read_file → edit_file (first change) → edit_file (second change) → verify

Safety & constraints:
- Preserves file encoding and permissions.
- Each edit is atomic — if it fails, the file is unchanged.
- For large-scale refactoring across multiple files, edit each file separately.

Tips:
- Include enough context in old_string to make it unique (2-3 surrounding lines).
- After editing, you can read_file to verify the change was applied correctly.
- For multiple edits to the same file, apply them sequentially (each edit changes the file).
- When adding new code, include the line above and below your insertion point in old_string.