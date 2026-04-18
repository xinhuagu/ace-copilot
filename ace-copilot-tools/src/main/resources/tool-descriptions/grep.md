Searches file contents for lines matching a regex pattern.
Use this to find code definitions, usages, errors, imports, or any text pattern in files.

IMPORTANT — Use this tool INSTEAD of:
- grep, rg, ag, ack, git grep (searching via bash)

Supports full regex syntax:
- "log.*Error"          — lines containing "log" followed by "Error"
- "function\\s+\\w+"    — function declarations
- "TODO|FIXME|HACK"     — multiple patterns with alternation
- "import.*jackson"     — import statements for a library
- "class\\s+\\w+\\s+implements" — class declarations with implements

When to use:
- Finding where a function, class, or variable is defined
- Finding all usages/references of a symbol
- Searching for error messages, config keys, or string literals
- Finding all files that import a specific module
- Locating TODO/FIXME comments
- Verifying a rename was applied consistently

When NOT to use:
- Finding files by name/path — use glob instead
- Reading a known file — use read_file instead
- You need to modify the matches — use edit_file after finding them

Parameter details:
- pattern: Java regex pattern to search for in file contents.
- path: Directory or file to search in. Defaults to the working directory.
- include: Glob pattern to filter which files to search (e.g., "*.java", "*.{ts,tsx}").
  Use this to limit search to specific file types for faster, more relevant results.
- context: Number of lines to show before and after each match (0-10, default: 0).
  Set to 2-3 when you need to understand the surrounding code.

Behavior:
- Skips: hidden directories (.), node_modules, build, target, __pycache__
- Skips binary files and files larger than 1MB
- Maximum: 50 matching files, 500 total match lines
- Returns file paths, line numbers, and matching lines

Common workflows:
- Find definition: grep "class MyClass" with include="*.java"
- Find all usages: grep "myFunction" → review each occurrence
- Targeted search: glob to find candidate files → grep within those files
- Multi-pattern: run parallel grep calls for different patterns

Tips:
- Use the include parameter to limit file types — much faster than searching everything.
- Run multiple grep calls in parallel when searching for different patterns.
- Set context=2 to see surrounding code for better understanding.
- Escape special regex characters: use \\. for literal dots, \\( for literal parens.
- Combine with glob: first find files by name pattern, then grep within them for precision.
- If you get too many results, narrow with a more specific include pattern.