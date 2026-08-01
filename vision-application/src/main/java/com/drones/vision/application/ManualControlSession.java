package com.drones.vision.application;

import com.drones.vision.domain.model.ChannelMap;

import java.util.List;

/**
 * One live relay session opened by {@link ManualControlService#engage}
 * (docs/RC-CONTROL-PHASE1-PLAN.md, RC-CONTROL Phase 1 R2) — the stateful counterpart to {@code
 * ManualControlPort}'s streaming, ack-less relay link.
 *
 * <h2>Threading</h2>
 * {@link #onChannels}, {@link #release}, and the session's own internal watchdog check may all run
 * on different threads (a WebSocket connection's read thread, whatever thread tears the connection
 * down, and the shared watchdog scheduler thread, respectively). Every method here is safe to call
 * concurrently with every other; see {@code DefaultManualControlService}'s implementation javadoc
 * for the exact guard.
 */
public interface ManualControlSession {

    /**
     * Maps {@code axes}/{@code buttons} (raw gamepad values: axes -1..1, buttons 0..1) through this
     * session's {@link #channelMap()} and forwards the result to the {@code ManualControlPort} as
     * the newest frame — latest-wins, no per-frame throttle here; the fixed-rate wire cadence is the
     * adapter's job. Resets the watchdog deadline. {@code seq}/{@code tSent} are accepted only to
     * satisfy the frozen client-frame shape (docs/RC-CONTROL-PHASE1-PLAN.md §4) — the caller already
     * holds both to build its own {@code ack} and does not need them echoed back here. A no-op once
     * {@link #active()} is {@code false}.
     */
    void onChannels(List<Double> axes, List<Double> buttons, long seq, long tSent);

    /**
     * Explicit release: stops the relay ({@code ManualControlPort#release}), cancels the pending
     * watchdog check, and audits {@code RELEASE}. Idempotent — a second call, or a call racing a
     * concurrent watchdog trip, is a safe no-op and never a second audit line.
     */
    void release();

    /** The channel map this session was engaged with — echoed to the client in its {@code engaged} frame. */
    ChannelMap channelMap();

    /** {@code false} once released, whether explicitly or by the watchdog. */
    boolean active();
}
