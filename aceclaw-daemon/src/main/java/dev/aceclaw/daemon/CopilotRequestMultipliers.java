package dev.aceclaw.daemon;

import java.util.Locale;
import java.util.Map;

/**
 * Normalized cost multipliers for GitHub Copilot premium requests, keyed by model.
 *
 * <p>Copilot charges differ per underlying model — e.g. Claude Opus runs at 10x while
 * Claude Sonnet and GPT-4o run at 1x — so raw request counts across models aren't
 * directly comparable. Exporting a multiplier alongside the per-source attribution
 * lets offline analysis translate request counts into normalized cost units for
 * apples-to-apples comparison.
 *
 * <p>Values reflect the GitHub Copilot premium-request rate card current as of
 * {@value #RATE_CARD_DATE}. Rates change as GitHub tunes their pricing; this table
 * is intentionally small and named-literal rather than scraped — update via PR when
 * the rate card shifts.
 *
 * <p>Unknown Copilot model → {@code 1.0} (the common-case rate). Non-Copilot
 * provider → {@code null} (the multiplier concept doesn't apply to token-priced
 * providers like direct Anthropic or OpenAI).
 */
final class CopilotRequestMultipliers {

    static final String RATE_CARD_DATE = "2026-04";
    static final double DEFAULT_COPILOT_MULTIPLIER = 1.0;

    private static final Map<String, Double> COPILOT = Map.ofEntries(
            // Anthropic through Copilot
            Map.entry("claude-sonnet-4-5", 1.0),
            Map.entry("claude-sonnet-4-6", 1.0),
            Map.entry("claude-opus-4", 5.0),
            Map.entry("claude-opus-4-5", 5.0),
            Map.entry("claude-opus-4-6", 10.0),
            Map.entry("claude-opus-4-7", 10.0),
            Map.entry("claude-3-5-sonnet", 1.0),
            // OpenAI through Copilot
            Map.entry("gpt-4o", 1.0),
            Map.entry("gpt-4.1", 0.0),
            Map.entry("gpt-5", 1.0),
            Map.entry("o1", 10.0),
            Map.entry("o1-preview", 10.0),
            Map.entry("o3-mini", 0.33),
            // Gemini through Copilot
            Map.entry("gemini-2.0-flash", 0.25),
            Map.entry("gemini-2.5-pro", 1.0)
    );

    private CopilotRequestMultipliers() {}

    /**
     * Returns the normalized premium-request multiplier for a given provider + model,
     * or {@code null} if the provider is not Copilot.
     *
     * <p>Models with a trailing date suffix (e.g. {@code claude-opus-4-6-20250929})
     * are normalized to their base identifier before lookup.
     */
    static Double forProviderAndModel(String provider, String model) {
        if (provider == null || !"copilot".equalsIgnoreCase(provider)) {
            return null;
        }
        if (model == null || model.isBlank()) {
            return DEFAULT_COPILOT_MULTIPLIER;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        // Strip trailing 8-digit date suffix: "claude-opus-4-6-20250929" -> "claude-opus-4-6"
        int dashDate = normalized.lastIndexOf('-');
        if (dashDate > 0 && dashDate < normalized.length() - 1) {
            String tail = normalized.substring(dashDate + 1);
            if (tail.length() == 8 && tail.chars().allMatch(Character::isDigit)) {
                normalized = normalized.substring(0, dashDate);
            }
        }
        return COPILOT.getOrDefault(normalized, DEFAULT_COPILOT_MULTIPLIER);
    }
}
