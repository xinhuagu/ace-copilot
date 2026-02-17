package dev.aceclaw.cli;

import java.io.PrintWriter;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * A braille-character spinner for showing tool execution progress.
 *
 * <p>Runs on a virtual thread, updating a single terminal line at 80ms intervals.
 * Use {@link #start(String)} to begin spinning and {@link #stop(String)} to
 * replace the spinner with a final status message.
 */
public final class TerminalSpinner {

    private static final String[] FRAMES = {
            "\u280B", "\u2819", "\u2839", "\u2838",
            "\u283C", "\u2834", "\u2826", "\u2827",
            "\u2807", "\u280F"
    };

    private static final long FRAME_INTERVAL_MS = 80;

    private final PrintWriter out;
    private volatile String message = "";
    private volatile boolean spinning = false;
    private volatile Thread spinThread;

    public TerminalSpinner(PrintWriter out) {
        this.out = out;
    }

    /**
     * Starts the spinner with the given message. If already spinning, updates the message.
     *
     * @param message the status message to display next to the spinner
     */
    public void start(String message) {
        this.message = message;
        if (spinning) return;
        spinning = true;
        spinThread = Thread.ofVirtual().name("aceclaw-spinner").start(this::animate);
    }

    /**
     * Stops the spinner and prints a final status line.
     *
     * @param finalMessage the message to display after stopping (e.g., checkmark + tool name)
     */
    public void stop(String finalMessage) {
        spinning = false;
        var t = spinThread;
        if (t != null) {
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spinThread = null;
        }
        // Clear the spinner line and print the final message
        out.print("\r\u001B[K");
        if (finalMessage != null && !finalMessage.isEmpty()) {
            out.println(finalMessage);
        }
        out.flush();
    }

    /**
     * Stops the spinner without printing a final message, just clears the line.
     */
    public void clear() {
        stop(null);
    }

    /**
     * Returns whether the spinner is currently active.
     */
    public boolean isSpinning() {
        return spinning;
    }

    private void animate() {
        int frameIdx = 0;
        while (spinning) {
            String frame = FRAMES[frameIdx % FRAMES.length];
            out.print("\r\u001B[K" + SPINNER + frame + RESET + " " + message);
            out.flush();
            frameIdx++;
            try {
                Thread.sleep(FRAME_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Returns the human-friendly verb for a tool name.
     *
     * @param toolName the tool identifier
     * @return a present-participle verb (e.g., "Reading", "Running")
     */
    public static String verbForTool(String toolName) {
        return switch (toolName) {
            case "read_file" -> "Reading";
            case "write_file" -> "Writing";
            case "edit_file" -> "Editing";
            case "bash" -> "Running";
            case "glob" -> "Searching";
            case "grep" -> "Searching";
            default -> "Running";
        };
    }
}
