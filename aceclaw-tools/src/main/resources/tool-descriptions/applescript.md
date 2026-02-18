Executes AppleScript code on macOS using the osascript command. macOS only.

Use this for macOS-specific automation: controlling applications, showing system dialogs,
managing Finder operations, and sending notifications.

When to use:
- Controlling macOS applications (open, activate, quit)
- Showing system dialogs or notifications to the user
- Performing Finder operations (reveal files, move to trash)
- Interacting with macOS system features (clipboard, notifications)
- Automating macOS-specific workflows

When NOT to use:
- Running shell commands — use bash instead
- File operations — use read_file, write_file, edit_file
- Web operations — use web_fetch, web_search, browser
- Any task that can be done with platform-independent tools

Parameter details:
- script: The AppleScript code to execute. Can be multi-line.
  Each line is passed as a separate -e argument to osascript.

Common patterns:
- Open an app: tell application "Safari" to activate
- Show dialog: display dialog "Hello" with title "AceClaw"
- Reveal in Finder: tell application "Finder" to reveal POSIX file "/path/to/file"
- Get clipboard: the clipboard
- Send notification: display notification "Done" with title "AceClaw"

Safety & constraints:
- 30-second timeout for script execution
- Output capped at 30,000 characters
- Do not execute untrusted or user-provided AppleScript without review
- Some operations may require Accessibility permissions

Tips:
- Multi-line scripts are supported — write them naturally with line breaks.
- Use "tell application" blocks to control specific apps.
- If the script fails, check that the target application is installed and permissions are granted.