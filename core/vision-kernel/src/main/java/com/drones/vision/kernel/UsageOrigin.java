package com.drones.vision.kernel;

/**
 * How an {@code AssetUsage} session came to be open — what verb an operator or the runtime used to
 * start it (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md finding D2, wave R2). Mirrors {@link
 * DeviceOrigin}'s shape (a plain enum, no dependencies) but answers a different question: {@code
 * DeviceOrigin} is a property of a device (real hardware vs. a synthetic stand-in), while this is a
 * property of one session — how it was opened, which in turn decides who is allowed to close it.
 *
 * <p>Before this wave, a session could only ever open as a side effect of a video stream starting
 * ({@code UsageTracker#onStreamStarted}) — an operator preparing a telemetry-only aircraft, or one
 * being readied before streaming, had no way to say "this asset is in use." {@link #OPERATOR}
 * exists for exactly that: an explicit engage/disengage act with no video stream and no device
 * traffic involved. See {@code UsageTracker#engage}/{@code #disengage} for the collision rules this
 * origin governs once a stream and an operator both touch the same session.
 */
public enum UsageOrigin {

    /** Opened as a side effect of a device's video stream starting ({@code UsageTracker#onStreamStarted}). */
    STREAM,

    // A TELEMETRY value was considered and left out: nothing in the codebase can produce it. Wave R2
    // deleted onTelemetryDeviceDiscovered — the only code that would have — as dead code, so the value
    // would name a session that cannot exist. Add it back with its producer, not before.

    /**
     * Opened directly by an operator's explicit engage act ({@code UsageTracker#engage}), with no
     * video stream and no device traffic involved — the only origin a caller outside {@code
     * UsageTracker} itself can request.
     */
    OPERATOR
}
