package dev.aceclaw.cli;

/**
 * Centralized color palette and ANSI styling for the AceClaw CLI.
 *
 * <p>All terminal colors and semantic styles are defined here so that
 * the rest of the CLI code references named constants rather than
 * raw escape sequences.
 */
public final class TerminalTheme {

    private TerminalTheme() {}

    // -- Reset ---------------------------------------------------------------

    public static final String RESET = "\u001B[0m";

    // -- Text attributes -----------------------------------------------------

    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";

    // -- Brand palette -------------------------------------------------------

    public static final String ACCENT = "\u001B[34m";       // blue
    public static final String SUCCESS = "\u001B[32m";      // green
    public static final String WARNING = "\u001B[33m";      // yellow
    public static final String ERROR = "\u001B[31m";        // red
    public static final String MUTED = "\u001B[90m";        // bright black / gray
    public static final String INFO = "\u001B[36m";         // cyan

    // -- Semantic combinations -----------------------------------------------

    /** H1 headings: bold cyan */
    public static final String HEADING_1 = BOLD + INFO;
    /** H2 headings: bold green */
    public static final String HEADING_2 = BOLD + SUCCESS;
    /** H3 headings: bold yellow */
    public static final String HEADING_3 = BOLD + WARNING;

    /** Active tool execution */
    public static final String TOOL_ACTIVE = WARNING;
    /** Completed tool */
    public static final String TOOL_DONE = SUCCESS;

    /** Thinking / extended thinking text */
    public static final String THINKING = DIM + ITALIC;

    /** Spinner frames */
    public static final String SPINNER = INFO;

    /** User prompt prefix */
    public static final String PROMPT = INFO;

    /** Permission request border and labels */
    public static final String PERMISSION = BOLD + WARNING;
    /** Approved action */
    public static final String APPROVED = SUCCESS;
    /** Denied action */
    public static final String DENIED = ERROR;

    /** Code content (fenced blocks, inline code) */
    public static final String CODE = INFO;
    /** Code fence markers (```) */
    public static final String CODE_FENCE = DIM;
    /** Inline code backtick markers */
    public static final String CODE_INLINE = MUTED;

    /** Links */
    public static final String LINK = UNDERLINE + "\u001B[35m"; // magenta

    // -- Box drawing helpers -------------------------------------------------

    public static final String BOX_TOP_LEFT = "\u2554";     // double top-left
    public static final String BOX_TOP_RIGHT = "\u2557";    // double top-right
    public static final String BOX_BOTTOM_LEFT = "\u255A";  // double bottom-left
    public static final String BOX_BOTTOM_RIGHT = "\u255D"; // double bottom-right
    public static final String BOX_HORIZONTAL = "\u2550";   // double horizontal
    public static final String BOX_VERTICAL = "\u2551";     // double vertical

    public static final String BOX_LIGHT_TOP_LEFT = "\u250C";
    public static final String BOX_LIGHT_BOTTOM_LEFT = "\u2514";
    public static final String BOX_LIGHT_HORIZONTAL = "\u2500";
    public static final String BOX_LIGHT_VERTICAL = "\u2502";

    /** Checkmark for completed items */
    public static final String CHECKMARK = "\u2713";

    // -- Utility methods -----------------------------------------------------

    /**
     * Builds a horizontal line of box-drawing characters.
     *
     * @param ch    the character to repeat
     * @param width number of repetitions
     * @return the repeated string
     */
    public static String hline(char ch, int width) {
        return String.valueOf(ch).repeat(Math.max(0, width));
    }

    /**
     * Builds a horizontal line using the double box-drawing character.
     *
     * @param width number of repetitions
     * @return the repeated string
     */
    public static String hline(int width) {
        return BOX_HORIZONTAL.repeat(Math.max(0, width));
    }

    /**
     * Builds a light horizontal line.
     *
     * @param width number of repetitions
     * @return the repeated string
     */
    public static String hlineLight(int width) {
        return BOX_LIGHT_HORIZONTAL.repeat(Math.max(0, width));
    }

    /**
     * Truncates a string to fit within the given width, appending ellipsis if needed.
     *
     * @param text  the text to fit
     * @param width maximum visible width
     * @return the possibly truncated text
     */
    public static String fitWidth(String text, int width) {
        if (text == null) return "";
        if (width <= 3) return text.length() <= width ? text : "...".substring(0, width);
        if (text.length() <= width) return text;
        return text.substring(0, width - 3) + "...";
    }

    /**
     * Pads a string to the right to the given width.
     *
     * @param text  the text to pad
     * @param width target width
     * @return right-padded text
     */
    public static String padRight(String text, int width) {
        if (text == null) text = "";
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }
}
