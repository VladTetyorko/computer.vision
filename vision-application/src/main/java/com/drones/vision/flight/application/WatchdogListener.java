package com.drones.vision.flight.application;

/**
 * Async signal from a {@link ManualControlSession} that it auto-released itself because inbound
 * input stalled past the watchdog timeout (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md, RC-CONTROL Phase 1 R2).
 * By the time this fires the session is already released ({@code ManualControlPort#release} has
 * already run) and the release is already audited ({@code WATCHDOG}) — the listener only needs to
 * notify whatever cares (e.g. push a {@code watchdog} frame to the WebSocket client), not release
 * anything itself.
 */
public interface WatchdogListener {

    /** Called at most once per session, on whatever thread the watchdog scheduler runs on. */
    void watchdogTripped();
}
