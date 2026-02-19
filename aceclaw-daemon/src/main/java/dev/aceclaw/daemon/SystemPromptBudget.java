package dev.aceclaw.daemon;

/**
 * Configuration for system prompt size limits.
 *
 * <p>Inspired by OpenClaw's two-tier character caps for bootstrap file injection.
 * Prevents the system prompt from consuming too much of the context window.
 *
 * <p>Use {@link #forContextWindow(int, int)} to create a budget scaled to the model's
 * context window. Small models (e.g. 32K context) get a proportionally smaller budget
 * so memory tiers don't consume an excessive share of available tokens.
 *
 * @param maxPerTierChars maximum characters per individual memory tier (default 20,000)
 * @param maxTotalChars   maximum total characters for all memory tiers combined (default 150,000)
 */
public record SystemPromptBudget(int maxPerTierChars, int maxTotalChars) {

    /** Default budget: 20K per tier, 150K total (suitable for 200K+ context models). */
    public static final SystemPromptBudget DEFAULT = new SystemPromptBudget(20_000, 150_000);

    /** Estimated base prompt + environment + git context size in tokens. */
    private static final int BASE_PROMPT_TOKENS = 6_000;

    public SystemPromptBudget {
        if (maxPerTierChars <= 0) {
            throw new IllegalArgumentException("maxPerTierChars must be positive");
        }
        if (maxTotalChars <= 0) {
            throw new IllegalArgumentException("maxTotalChars must be positive");
        }
    }

    /**
     * Creates a budget scaled to the model's context window.
     *
     * <p>Allocates up to 25% of the effective context window (context - maxOutput) for
     * the full system prompt (base + memory). Subtracts the base prompt overhead, then
     * converts the remaining token budget to a character cap for memory tiers.
     *
     * <p>Examples:
     * <ul>
     *   <li>200K context, 16K output → 150K chars total, 20K per tier (DEFAULT)</li>
     *   <li>32K context, 4K output   → ~8K chars total, ~2.6K per tier</li>
     *   <li>128K context, 8K output  → ~54K chars total, ~18K per tier</li>
     * </ul>
     *
     * @param contextWindowTokens the model's total context window in tokens
     * @param maxOutputTokens     the max output tokens configured
     * @return a budget appropriate for the context window size
     */
    public static SystemPromptBudget forContextWindow(int contextWindowTokens, int maxOutputTokens) {
        int effectiveWindow = contextWindowTokens - maxOutputTokens;
        // System prompt (base + memory + tools) should use at most 25% of effective window
        int systemPromptBudgetTokens = effectiveWindow / 4;
        // Subtract base prompt overhead to get memory-only budget
        int memoryBudgetTokens = systemPromptBudgetTokens - BASE_PROMPT_TOKENS;
        // Convert to chars (~4 chars/token)
        int maxTotalChars = Math.max(2_000, memoryBudgetTokens * 4);
        maxTotalChars = Math.min(150_000, maxTotalChars);
        int maxPerTierChars = Math.min(20_000, maxTotalChars / 3);
        maxPerTierChars = Math.max(1_000, maxPerTierChars);
        return new SystemPromptBudget(maxPerTierChars, maxTotalChars);
    }
}
