package dev.acecopilot.infra.event;

/**
 * Functional interface for handling events from the {@link EventBus}.
 *
 * @param <T> the event type
 */
@FunctionalInterface
public interface EventHandler<T extends AceCopilotEvent> {

    /**
     * Handles an event. Called on a dedicated virtual thread.
     *
     * <p>Implementations should not block for extended periods.
     * Exceptions are caught and logged by the event bus.
     *
     * @param event the event to handle
     */
    void handle(T event);
}
