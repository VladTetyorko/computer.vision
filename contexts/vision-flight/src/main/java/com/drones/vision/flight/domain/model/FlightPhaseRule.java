package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.FlightState;

import java.time.Duration;

/**
 * The pure function {@code (phase, FlightState, linkAge, streamCount) -> phase} docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §2.2/§2.3 assigns to this context: phases are decided by flight facts
 * (arming, link silence), so the rule lives here even though the {@code AssetUsage} record it drives
 * lives in {@code vision-warehouse} and the driver that calls it is perception's {@code
 * UsageTracker} (O7). This type holds no state of its own beyond its two configured windows —
 * every call is independent and deterministic, so a single instance is safe to share across every
 * asset and thread.
 *
 * <p><b>Two entry points, not one</b>: the plan's diagram has one transition — {@code IN_FLIGHT ->
 * ABANDONED: session closed while armed} — that no telemetry sample can carry, because it fires when
 * the platform stops observing (the stream/session itself ends), not when a sample arrives. {@link
 * #nextPhase} is the sample-driven half (called on every telemetry tick / stream-count change);
 * {@link #onSessionClosed} is the explicit-close half (called once, when whatever is driving the
 * session decides to stop). Splitting them keeps both pure and keeps the frozen 4-argument shape the
 * plan names for the sample-driven case honest, rather than overloading it with a synthetic "closing"
 * flag the plan never asks for.
 *
 * <p><b>{@code armed == null} is never treated as {@code false}</b> (C7): every branch below tests
 * {@code Boolean.TRUE.equals(armed)}/{@code Boolean.FALSE.equals(armed)} rather than unboxing, so an
 * unknown arming state is neither "flying" nor "landed" — it simply does not satisfy either
 * transition guard, and the phase holds.
 *
 * <p><b>{@code failsafe} never appears here</b>, deliberately (§2.3): it is rendered and audited on
 * the {@code IN_FLIGHT} phase, not modelled as a transition — an aircraft in failsafe is still
 * flying, and a state machine that transitioned on it would misreport what the aircraft is doing.
 */
public final class FlightPhaseRule {

    private final Duration silenceWindow;
    private final Duration abandonWindow;

    /**
     * @param silenceWindow how long telemetry may go quiet before a live session is presumed
     *                      link-lost ({@code vision.flight.phase.silence-window}, default 10s per
     *                      docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1); must be positive
     * @param abandonWindow how long a link-lost session may stay silent before it is presumed
     *                      abandoned ({@code vision.flight.phase.abandon-window}, default 120s);
     *                      must be positive and not shorter than {@code silenceWindow} — abandonment
     *                      is defined as "link-lost for longer still", not an independent clock
     */
    public FlightPhaseRule(Duration silenceWindow, Duration abandonWindow) {
        if (silenceWindow == null || silenceWindow.isNegative() || silenceWindow.isZero()) {
            throw new IllegalArgumentException("silenceWindow must be positive: " + silenceWindow);
        }
        if (abandonWindow == null || abandonWindow.isNegative() || abandonWindow.isZero()) {
            throw new IllegalArgumentException("abandonWindow must be positive: " + abandonWindow);
        }
        if (abandonWindow.compareTo(silenceWindow) < 0) {
            throw new IllegalArgumentException(
                    "abandonWindow must be >= silenceWindow: " + abandonWindow + " < " + silenceWindow);
        }
        this.silenceWindow = silenceWindow;
        this.abandonWindow = abandonWindow;
    }

    /**
     * The sample-driven half of the state machine (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3's
     * diagram, every arrow except the explicit-close one — see {@link #onSessionClosed}).
     *
     * @param currentPhase the usage's phase before this sample
     * @param state        the freshest {@link FlightState} known for the asset ({@code null} when
     *                     nothing has ever been decoded); only {@link FlightState#armed()} is read —
     *                     {@code null} counts as unknown, never as {@code false}
     * @param linkAge      how long it has been since the last telemetry sample arrived; zero for
     *                     "a sample just arrived"
     * @param streamCount  how many video streams are currently open for the asset (0 if none) — used
     *                     only to decide whether a never-armed {@code PREFLIGHT}/{@code POSTFLIGHT}
     *                     session has gone quiet on every channel at once, never to gate {@code
     *                     IN_FLIGHT}/{@code LINK_LOST} (a telemetry-only flight has {@code
     *                     streamCount == 0} throughout and must not be mistaken for "nothing
     *                     happening")
     * @return the phase the usage should hold after this sample
     */
    public FlightPhase nextPhase(FlightPhase currentPhase, FlightState state, Duration linkAge, int streamCount) {
        if (currentPhase == null) {
            throw new IllegalArgumentException("currentPhase must not be null");
        }
        if (linkAge == null || linkAge.isNegative()) {
            throw new IllegalArgumentException("linkAge must not be null or negative: " + linkAge);
        }
        Boolean armed = state == null ? null : state.armed();
        boolean silent = linkAge.compareTo(silenceWindow) >= 0;
        boolean silentPastAbandon = linkAge.compareTo(abandonWindow) >= 0;

        return switch (currentPhase) {
            case PREFLIGHT -> {
                if (Boolean.TRUE.equals(armed)) {
                    yield FlightPhase.IN_FLIGHT;
                }
                if (streamCount == 0 && silent) {
                    yield FlightPhase.CLOSED; // stream stops / silence, never armed -- no flight
                }
                yield FlightPhase.PREFLIGHT;
            }
            case IN_FLIGHT -> {
                if (Boolean.FALSE.equals(armed)) {
                    yield FlightPhase.POSTFLIGHT;
                }
                if (silent) {
                    yield FlightPhase.LINK_LOST;
                }
                yield FlightPhase.IN_FLIGHT; // armed==true, or armed==null while still hearing telemetry
            }
            case LINK_LOST -> {
                if (silentPastAbandon) {
                    yield FlightPhase.ABANDONED;
                }
                if (!silent) {
                    // Re-heard within the window. Rule 9 (newest wins): a fresh sample with known
                    // arming decides the next phase; a fresh sample that still cannot say (armed ==
                    // null) is not evidence of anything and leaves the phase link-lost rather than
                    // guessing it is safely back in flight.
                    if (Boolean.FALSE.equals(armed)) {
                        yield FlightPhase.POSTFLIGHT;
                    }
                    if (Boolean.TRUE.equals(armed)) {
                        yield FlightPhase.IN_FLIGHT;
                    }
                }
                yield FlightPhase.LINK_LOST;
            }
            case POSTFLIGHT -> {
                if (Boolean.TRUE.equals(armed)) {
                    yield FlightPhase.IN_FLIGHT; // re-armed before close -- second takeoff
                }
                if (streamCount == 0 && silent) {
                    yield FlightPhase.CLOSED; // stream stops / telemetry silence
                }
                yield FlightPhase.POSTFLIGHT;
            }
            case ABANDONED, CLOSED -> currentPhase; // terminal from this function's point of view
        };
    }

    /**
     * The explicit-close half: whatever is driving the session (perception's {@code UsageTracker},
     * O7) decided to stop observing right now, independent of any single sample. {@code IN_FLIGHT}
     * and {@code LINK_LOST} both close to {@link FlightPhase#ABANDONED} — in both, the last known
     * fact is "armed" or "unresolved since armed", so closing now means the platform stopped
     * watching while the aircraft was, so far as it knew, airborne (§2.3's "a real outcome, not an
     * error"). Every other phase closes normally.
     *
     * @param currentPhase the usage's phase at the moment the session is closed
     * @return the terminal phase to record
     */
    public FlightPhase onSessionClosed(FlightPhase currentPhase) {
        if (currentPhase == null) {
            throw new IllegalArgumentException("currentPhase must not be null");
        }
        return switch (currentPhase) {
            case IN_FLIGHT, LINK_LOST -> FlightPhase.ABANDONED;
            case PREFLIGHT, POSTFLIGHT, ABANDONED, CLOSED -> FlightPhase.CLOSED;
        };
    }
}
