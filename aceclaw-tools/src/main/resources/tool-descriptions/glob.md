Fast file pattern matching tool that works with any codebase size.
Use this when you need to find files by name or path pattern.

IMPORTANT — Use this tool INSTEAD of:
- find, ls -R, locate, dir /s (file discovery via bash)

Pattern syntax:
- **/*.java        — all Java files recursively
- src/**/*.ts      — TypeScript files under src/
- *.md             — markdown files in current directory only
- **/test*/**/*.java — test files in any test directory
- **/*Config*.{java,yaml,yml} — config files with multiple extensions
- **/README*       — all README files anywhere in the tree

When to use:
- Discovering files in an unfamiliar codebase
- Finding all files of a certain type (e.g., all .java, all .yaml)
- Locating a file when you know part of its name but not the full path
- Finding related files (tests, configs, interfaces)
- Exploring project structure before making changes

When NOT to use:
- You know the exact file path — use read_file directly (faster)
- Searching inside files for a text pattern — use grep instead
- Listing a single directory's contents — use list_directory instead

Parameter details:
- pattern: Glob pattern to match file paths. Relative to the search directory.
  Use ** for recursive matching, * for single-level matching.
- path: Directory to search in. Defaults to the working directory.

Behavior:
- Returns matching file paths sorted by modification time (most recent first)
- Automatically skips: hidden directories (.), node_modules, build, target, __pycache__, .git
- Maximum 200 results returned
- Searches up to 20 directory levels deep

Common workflows:
- Explore project: glob **/*.java → read interesting files
- Find config: glob **/*config*.{json,yaml,yml,toml}
- Find tests: glob **/test*/**/*Test*.java or **/*_test.go

Tips:
- Run multiple glob calls in parallel when searching for different file types.
- If no results found, try a broader pattern (e.g., **/* instead of src/**/*).
- Check that the search directory exists and is correct if results seem wrong.
- Combine glob (find file) then read_file (read it) for a targeted exploration workflow.