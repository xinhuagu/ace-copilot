Writes content to a file. WARNING: This tool completely OVERWRITES the existing file.

Creates the file if it does not exist. Creates parent directories automatically if needed.
This is a destructive operation for existing files — the entire previous content is replaced.

IMPORTANT — Use this tool INSTEAD of:
- echo > file, cat <<EOF > file, tee, printf > file (writing via bash)
- Any bash command whose purpose is to create or overwrite a file

CRITICAL RULES:
- If the file already exists, you MUST read it first with read_file.
- ALWAYS prefer edit_file over write_file for modifying existing files.
- Only use write_file when creating a brand new file or replacing entire file content.
- Do NOT create files that are not absolutely necessary — avoid file bloat.
- Do NOT create documentation files (README, docs, .md) unless explicitly asked.

When to use:
- Creating a brand new file that does not exist yet
- Replacing the entire content of a file (rare — prefer edit_file for partial changes)
- Writing generated output (test fixtures, config files, scripts)

When NOT to use:
- Making small changes to an existing file — use edit_file instead
- Appending to a file — use edit_file to add content at the right location
- Running sed/awk replacements — use edit_file instead

Parameter details:
- file_path: Absolute or relative path. Parent directories are created automatically.
- content: The full content to write. This replaces everything in the file.

Common workflows:
- New file: decide on path → write_file with complete content
- Replace file: read_file first → verify you need full replacement → write_file

Safety & constraints:
- Overwrites without confirmation — be certain you want to replace the entire file.
- Never write files containing secrets, credentials, or API keys.
- Avoid creating unnecessary files. Edit existing files when possible.

Tips:
- For existing files, edit_file is almost always the better choice.
- If you are unsure whether the file exists, try read_file first.
- Include proper file encoding (UTF-8) and line endings in the content.