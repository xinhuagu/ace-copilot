Fetches content from a URL and returns it as text.

For HTML pages, converts to readable text by stripping tags and preserving structure
(headings, paragraphs, lists). For non-HTML content (JSON, plain text, XML), returns
raw content. Uses Jsoup for HTML-to-text conversion.

When to use:
- Reading documentation pages or API references
- Fetching JSON API responses (use raw=true for structured data)
- Reading web-hosted files (READMEs, changelogs, release notes)
- Getting content from a known URL found via web_search
- Fetching configuration examples or code snippets from the web

When NOT to use:
- You need to search for something first — use web_search to find URLs, then web_fetch
- The page requires JavaScript rendering — use browser tool instead
- The page requires authentication or login — use browser tool instead
- You need to interact with the page (click, type) — use browser tool instead

Parameter details:
- url: Full URL including http:// or https://. Required.
- raw: If true, return raw HTML/content without text conversion. Useful for JSON APIs
  or when you need the exact HTML structure. Default: false.

Behavior:
- Follows redirects automatically
- 30-second timeout for the request
- Output capped at 30,000 characters (truncated if longer)
- Removes script, style, nav, footer elements from HTML
- Prefers main/article content over full page body

Priority chain for web information:
1. web_search — find relevant URLs
2. web_fetch — get content from a known URL
3. browser — last resort for JS-rendered or interactive pages

Tips:
- For API endpoints, use raw=true to get JSON/XML without HTML processing.
- If the page returns garbled content, try raw=true — it might not be HTML.
- If web_fetch fails or returns poor content, fall back to the browser tool.
- Combine with web_search: search for a topic → fetch the most relevant URL.