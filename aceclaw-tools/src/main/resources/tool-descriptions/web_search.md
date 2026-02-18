Searches the web using Brave Search and returns results with title, URL, and snippet.

Use this for finding up-to-date information, documentation, solutions, and resources
from the internet. Returns real-time search results.

When to use:
- Finding documentation or API references for a library or tool
- Looking up error messages or stack traces for solutions
- Finding current best practices or recommended approaches
- Discovering URLs for resources you need to read with web_fetch
- Getting up-to-date information beyond your training data

When NOT to use:
- You already know the URL — use web_fetch directly
- You need to read a known web page — use web_fetch
- You need to interact with a web page — use browser
- The information is in the local codebase — use grep or read_file

Parameter details:
- query: The search query string. Be specific and include relevant keywords.
  Include the current year for time-sensitive topics (e.g., "React best practices 2025").
- max_results: Number of results to return (default: 5, max: 20).
  Use more results for broad research, fewer for specific lookups.

Common workflows:
- Research: web_search for topic → web_fetch top results → summarize findings
- Error debugging: web_search for error message → find solution → apply fix
- Documentation: web_search for "library_name docs" → web_fetch the doc URL

Tips:
- Be specific in queries: "Jackson ObjectMapper custom deserializer Java" not just "Jackson"
- Include the programming language or framework in your query for better results.
- Use web_search to FIND the URL, then web_fetch to READ the content.
- If results are not helpful, try rephrasing the query with different keywords.