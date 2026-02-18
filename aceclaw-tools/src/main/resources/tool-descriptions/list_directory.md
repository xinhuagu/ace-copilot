Lists the contents of a directory in a formatted table.

Returns type (dir/file), size, modified date, and name for each entry.
Directories are listed first, then files, both sorted alphabetically.

IMPORTANT — Use this tool INSTEAD of:
- ls, dir, tree (directory listing via bash)

When to use:
- Exploring an unknown directory structure (what files and subdirs exist here?)
- Checking what was created after a build or generation step
- Getting an overview of a project's top-level structure

When NOT to use:
- Finding files recursively across many directories — use glob instead
- Searching for a specific file by name pattern — use glob instead
- Reading file contents — use read_file instead
- Searching inside files — use grep instead

Parameter details:
- path: Directory to list. Defaults to the working directory. Can be absolute or relative.

Behavior:
- Directories appear first, then files
- Both groups sorted alphabetically (case-insensitive)
- Capped at 1000 entries
- Shows: type (dir/file), human-readable size, last modified date, name

Tips:
- For recursive file discovery, use glob with ** patterns instead.
- Combine with read_file: list_directory to see what's there, then read specific files.