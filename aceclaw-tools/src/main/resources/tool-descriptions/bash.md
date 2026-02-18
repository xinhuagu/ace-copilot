Executes a bash command with optional timeout. Use this for git, build tools, package managers,
running tests, and other terminal operations that require shell execution.

IMPORTANT — Do NOT use bash for file operations. Use dedicated tools instead:
- To read files: use read_file (NOT cat, head, tail, less, more, sed -n)
- To edit files: use edit_file (NOT sed, awk, perl -i, ex)
- To create files: use write_file (NOT echo >, cat <<EOF, tee, printf >)
- To search file names: use glob (NOT find, ls -R, locate)
- To search file contents: use grep (NOT grep, rg, ag, ack)
- To list directories: use list_directory (NOT ls, dir, tree)

When to use:
- Git operations: git status, git diff, git log, git add, git commit, git push, git branch
- Build tools: ./gradlew build, mvn package, cargo build, make
- Package managers: npm install, pip install, brew install
- Running tests: ./gradlew test, pytest, npm test, cargo test
- Docker/container commands
- curl/wget for API calls
- Running scripts: python script.py, node script.js

When NOT to use:
- Reading, writing, or editing files (use the dedicated file tools above)
- Searching for files or content (use glob or grep)
- Any command that only reads file content (use read_file)

Git safety rules:
- NEVER force push (git push --force, git push -f) without explicit user confirmation
- NEVER run git reset --hard without explicit user confirmation
- NEVER skip hooks (--no-verify) unless explicitly asked
- NEVER auto-commit — always ask the user before committing
- Always create NEW commits, not amend, unless explicitly asked to amend
- Stage specific files by name, not "git add -A" or "git add ." (avoids including secrets)
- If a pre-commit hook fails, fix the issue and create a NEW commit (do not amend)

Git commit workflow:
1. git status to see changes
2. git diff to review what changed
3. git log --oneline -5 to match commit style
4. git add <specific-files>
5. git commit with a HEREDOC message:
   git commit -m "$(cat <<'EOF'
   Concise description of why the change was made.
   EOF
   )"

Command execution details:
- Commands run in the project working directory
- Default timeout: 120 seconds, maximum: 600 seconds (10 minutes)
- Output is captured (stdout + stderr merged) and returned as text
- Output truncated at 30,000 characters (head preserved) if exceeded
- Exit code 0 = success, non-zero = error (exit code included in output)

Tips:
- Always quote file paths with spaces: cd "path with spaces"
- Chain dependent commands with &&: mkdir -p dir && cp file dir/
- Use ; only when you don't care if earlier commands fail
- If a command fails, read the error carefully — don't just retry blindly
- For long-running commands (builds, tests), consider setting a higher timeout
- Never run destructive commands (rm -rf, drop database, kill -9) without confirmation
- Use absolute paths when possible to avoid working directory confusion