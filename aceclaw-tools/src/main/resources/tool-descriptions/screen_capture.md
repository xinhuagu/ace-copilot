Captures a screenshot on macOS using the screencapture command. macOS only.

Saves the screenshot as a PNG file. Optionally captures a specific screen region
and extracts text from the image using OCR (macOS Vision framework).

IMPORTANT: This is a LAST RESORT for information gathering. Always prefer text-based
tools (read_file, web_fetch, grep, get_text) over screenshots. Only use screen_capture
when you genuinely need visual information that cannot be obtained as text.

When to use:
- Debugging UI layout issues that require visual inspection
- Verifying visual appearance of an application
- Capturing error dialogs or visual glitches
- Extracting text from images when no text source is available (with ocr=true)

When NOT to use:
- Reading web page content — use web_fetch or browser get_text
- Reading file contents — use read_file
- Any task where text-based tools can get the same information

Parameter details:
- region: Screen region as 'x,y,width,height' (e.g., '0,0,800,600'). Optional.
  If omitted, captures the entire screen.
- ocr: If true, extract text from the screenshot using macOS Vision framework. Default: false.
- output_path: File path for the screenshot. Optional, defaults to a temp file.

Behavior:
- Captures silently (no shutter sound)
- Returns the file path of the saved screenshot
- OCR uses accurate recognition level with language correction
- 30-second timeout for the capture operation

Tips:
- Use region capture to focus on a specific area of the screen.
- Enable ocr=true when you need to read text from the captured image.
- The OCR output may not be perfect — treat it as approximate text extraction.