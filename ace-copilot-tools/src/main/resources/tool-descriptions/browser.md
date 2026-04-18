Controls a headless browser (Chromium) for web automation, testing, and JavaScript-rendered pages.

Manages a single browser session with a set of actions. The browser is headless Chromium
powered by Playwright. You MUST call 'launch' before using any other action.

Actions:
- launch: Start a new browser session. Call this first before any other action.
- navigate: Go to a URL. Params: url (required).
- click: Click an element by CSS selector. Params: selector (required).
- type: Type text into a form field. Params: selector (required), text (required).
- screenshot: Capture the current page as PNG. Params: output_path (optional, defaults to temp file).
- get_text: Extract text content from the page or a specific element. Params: selector (optional).
- evaluate: Execute JavaScript code in the browser. Params: script (required).
- close: Close the browser session and free resources.

When to use:
- Pages that require JavaScript rendering (SPAs, dynamic content)
- Form interactions: filling fields, clicking buttons, submitting forms
- Testing web applications (navigate, interact, verify)
- Pages requiring login or authentication flows
- Extracting content that web_fetch cannot handle

When NOT to use:
- Static web pages — use web_fetch instead (much faster, no browser overhead)
- Searching the web — use web_search instead
- Simple API calls — use bash with curl instead
- Reading local files — use read_file

Priority chain for web information:
1. web_search — find relevant URLs
2. web_fetch — get content from static pages
3. browser — last resort for JS-rendered or interactive pages

Parameter details:
- action: The browser action to perform (required). One of the actions listed above.
- selector: CSS selector for element targeting. Examples: "#login-btn", ".submit", "input[name=email]"
- url: Full URL for navigate action
- text: Text string for type action
- script: JavaScript code for evaluate action
- output_path: File path for screenshot (optional, defaults to temp file)

Common workflows:
- Read JS page: launch → navigate → get_text → close
- Fill form: launch → navigate → type (fields) → click (submit) → get_text (result) → close
- Test page: launch → navigate → screenshot → evaluate (assertions) → close

Safety & constraints:
- Always call 'launch' before any other action.
- Always call 'close' when done to free resources (browser process stays running otherwise).
- Prefer get_text over screenshot for extracting information — text is more useful than images.
- 30-second default timeout for each action.

Tips:
- Use get_text with a specific CSS selector to extract just the content you need.
- Use evaluate to run JavaScript for complex interactions or data extraction.
- If Chromium is not installed, the error message will suggest: npx playwright install chromium