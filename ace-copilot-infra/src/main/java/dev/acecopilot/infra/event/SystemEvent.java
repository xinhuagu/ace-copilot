package dev.acecopilot.infra.event;

import java.time.Instant;

/** Daemon-level system events. */
public sealed interface SystemEvent extends AceCopilotEvent {

    record DaemonStarted(long bootMs, Instant timestamp) implements SystemEvent {}

    record DaemonShutdownInitiated(String reason, Instant timestamp) implements SystemEvent {}

    record ConfigReloaded(Instant timestamp) implements SystemEvent {}
}
