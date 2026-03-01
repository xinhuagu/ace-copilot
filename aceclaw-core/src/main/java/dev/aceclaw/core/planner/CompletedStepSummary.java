package dev.aceclaw.core.planner;

import java.util.Objects;

/**
 * Lightweight summary of a completed plan step, used as context for replanning.
 *
 * @param stepName      human-readable step name
 * @param description   step description
 * @param success       whether the step completed successfully
 * @param outputSummary truncated output or error message (max 200 chars)
 */
public record CompletedStepSummary(
        String stepName,
        String description,
        boolean success,
        String outputSummary
) {

    public CompletedStepSummary {
        Objects.requireNonNull(stepName, "stepName");
        outputSummary = outputSummary != null && outputSummary.length() > 200
                ? outputSummary.substring(0, 200) + "..." : outputSummary;
    }

    /**
     * Creates a summary from a planned step and its execution result.
     */
    static CompletedStepSummary from(PlannedStep step, StepResult result) {
        return new CompletedStepSummary(
                step.name(),
                step.description(),
                result.success(),
                result.success() ? result.output() : result.error());
    }
}
