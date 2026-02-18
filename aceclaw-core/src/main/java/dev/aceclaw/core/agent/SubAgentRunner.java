package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Creates and runs sub-agent loops for delegated tasks.
 *
 * <p>Each sub-agent gets a fresh {@link StreamingAgentLoop} with a filtered
 * {@link ToolRegistry} (always excluding "task" to prevent nesting),
 * an empty conversation history, and no compaction.
 */
public final class SubAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRunner.class);

    private final LlmClient llmClient;
    private final ToolRegistry parentRegistry;
    private final String parentModel;
    private final Path workingDir;
    private final int maxTokens;
    private final int thinkingBudget;

    public SubAgentRunner(LlmClient llmClient, ToolRegistry parentRegistry, String parentModel,
                          Path workingDir, int maxTokens, int thinkingBudget) {
        this.llmClient = llmClient;
        this.parentRegistry = parentRegistry;
        this.parentModel = parentModel;
        this.workingDir = workingDir;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
    }

    /**
     * Runs a sub-agent with the given configuration and prompt.
     * Blocks until the sub-agent completes all iterations.
     *
     * @param config  the sub-agent type configuration
     * @param prompt  the task prompt for the sub-agent
     * @param handler optional stream event handler (may be null for silent execution)
     * @return the sub-agent's final text response
     * @throws LlmException if the LLM call fails
     */
    public String run(SubAgentConfig config, String prompt, StreamEventHandler handler) throws LlmException {
        String resolvedModel = config.inheritsModel() ? parentModel : config.model();
        var filteredRegistry = createFilteredRegistry(config);
        String systemPrompt = buildSystemPrompt(config);

        log.info("Starting sub-agent '{}': model={}, tools={}, maxTurns={}",
                config.name(), resolvedModel, filteredRegistry.size(), config.maxTurns());

        // Sub-agents use no compaction (short-lived, fresh context)
        var loop = new StreamingAgentLoop(
                llmClient, filteredRegistry, resolvedModel, systemPrompt,
                maxTokens, thinkingBudget, null);

        var effectiveHandler = handler != null ? handler : new StreamEventHandler() {};

        var turn = loop.runTurn(prompt, new ArrayList<>(), effectiveHandler);

        log.info("Sub-agent '{}' completed: stopReason={}, usage=({} in, {} out)",
                config.name(), turn.finalStopReason(),
                turn.totalUsage().inputTokens(), turn.totalUsage().outputTokens());

        return turn.text();
    }

    /**
     * Creates a filtered tool registry for a sub-agent.
     * Always excludes "task" to prevent infinite nesting.
     *
     * @param config the sub-agent configuration
     * @return a new registry with only the permitted tools
     */
    ToolRegistry createFilteredRegistry(SubAgentConfig config) {
        var filtered = new ToolRegistry();

        // Collect tools to exclude (always include "task" for no-nesting)
        var excluded = new HashSet<>(config.disallowedTools());
        excluded.add("task");

        List<String> allowed = config.allowedTools();
        boolean hasAllowList = !allowed.isEmpty();

        for (var tool : parentRegistry.all()) {
            if (excluded.contains(tool.name())) {
                continue;
            }
            if (hasAllowList && !allowed.contains(tool.name())) {
                continue;
            }
            filtered.register(tool);
        }

        return filtered;
    }

    private String buildSystemPrompt(SubAgentConfig config) {
        String template = config.systemPromptTemplate();
        if (template.isBlank()) {
            return "You are a sub-agent. Complete the delegated task. Working directory: " + workingDir;
        }
        // Replace %s placeholder with working directory if present
        if (template.contains("%s")) {
            return String.format(template, workingDir);
        }
        return template;
    }
}
