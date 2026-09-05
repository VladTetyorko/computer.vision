package com.drones.vision.perception.domain.model;

/**
 * Per-detection track facts (docs/plans/done/TRACKING-PLAN.md §4.B) — the identity, lifecycle state, and
 * motion a {@link Detection} carries when tracking is on for its stream.
 *
 * <p>{@code trackId} starts at 1 per stream, per session; {@code 0} is the wire's "untracked"
 * sentinel and must never reach this type — an untracked {@link Detection} carries {@code track =
 * null} instead of a {@code TrackRef} with {@code trackId == 0} (docs/extracts/TRACKING-ORCHESTRATION.md
 * §6 rule 2, "absent means untracked — exactly one spelling per layer"). {@code velocityX}/{@code
 * velocityY} are normalized frame-widths/heights per second, matching {@link BoundingBox}'s own
 * units.
 *
 * <p>{@code identityConfidence}/{@code dormantMillis} (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1,
 * D11) are the two wire facts that prove a bound track was <em>recovered</em> from cv-service's L4
 * follow-memory rather than freshly acquired: {@code identityConfidence > 0} means this bind came
 * back from memory, at that score; both fields read {@code 0}/{@code 0.0} for a fresh acquisition,
 * and — since a pre-L4 server never sets either wire field — for any response predating this
 * capability too. Neither field is ever guessed; the codec passes through exactly what the wire
 * says.
 *
 * @param trackId            stable identity of the track within its stream; must be at least 1
 * @param state              lifecycle state of the track this detection belongs to
 * @param source             which loop produced this particular box on this particular frame
 * @param velocityX          normalized frame-widths per second; must be finite
 * @param velocityY          normalized frame-heights per second; must be finite
 * @param ageFrames          frames since this track was born; must not be negative
 * @param reupdated          whether this track's gap was reconstructed by ORU on this frame
 *                           (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2)
 * @param identityConfidence &gt;0 ⟺ this bind was recovered from cv-service's follow memory, at this
 *                           score; {@code 0} = a fresh acquisition. Range [0,1]
 * @param dormantMillis      how long this identity was dormant before being recovered; {@code 0} =
 *                           not a recovery. Must not be negative
 */
public record TrackRef(long trackId, TrackState state, DetectionSource source, double velocityX, double velocityY,
                        int ageFrames, boolean reupdated, double identityConfidence, long dormantMillis) {

    public TrackRef {
        if (trackId < 1) {
            throw new IllegalArgumentException(
                    "TrackRef trackId must be at least 1 (0 is the wire's untracked sentinel): " + trackId);
        }
        if (state == null) {
            throw new IllegalArgumentException("TrackRef state must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("TrackRef source must not be null");
        }
        if (!Double.isFinite(velocityX)) {
            throw new IllegalArgumentException("TrackRef velocityX must be finite: " + velocityX);
        }
        if (!Double.isFinite(velocityY)) {
            throw new IllegalArgumentException("TrackRef velocityY must be finite: " + velocityY);
        }
        if (ageFrames < 0) {
            throw new IllegalArgumentException("TrackRef ageFrames must not be negative: " + ageFrames);
        }
        if (Double.isNaN(identityConfidence) || identityConfidence < 0.0 || identityConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "TrackRef identityConfidence must be within [0,1]: " + identityConfidence);
        }
        if (dormantMillis < 0) {
            throw new IllegalArgumentException("TrackRef dormantMillis must not be negative: " + dormantMillis);
        }
    }

    /**
     * Convenience constructor for callers that don't care about {@link #reupdated()}/
     * {@link #identityConfidence()}/{@link #dormantMillis()} — defaults {@code reupdated} to
     * {@code false} and the two recovery facts to {@code 0}/{@code 0.0} (the honest "not a
     * recovery" answer). This was the canonical constructor before
     * docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md added {@link #reupdated()}; every pre-existing
     * 6-arg call site compiles <em>and behaves</em> unchanged.
     */
    public TrackRef(long trackId, TrackState state, DetectionSource source, double velocityX, double velocityY,
                     int ageFrames) {
        this(trackId, state, source, velocityX, velocityY, ageFrames, false, 0.0, 0L);
    }

    /**
     * Convenience constructor for a freshly observed track with no motion/age history yet —
     * defaults {@link #velocityX()}/{@link #velocityY()}/{@link #ageFrames()} to {@code 0}.
     */
    public TrackRef(long trackId, TrackState state, DetectionSource source) {
        this(trackId, state, source, 0.0, 0.0, 0);
    }
}
