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
 * @param trackId   stable identity of the track within its stream; must be at least 1
 * @param state     lifecycle state of the track this detection belongs to
 * @param source    which loop produced this particular box on this particular frame
 * @param velocityX normalized frame-widths per second; must be finite
 * @param velocityY normalized frame-heights per second; must be finite
 * @param ageFrames frames since this track was born; must not be negative
 */
public record TrackRef(long trackId, TrackState state, DetectionSource source, double velocityX, double velocityY,
                        int ageFrames) {

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
    }

    /**
     * Convenience constructor for a freshly observed track with no motion/age history yet —
     * defaults {@link #velocityX()}/{@link #velocityY()}/{@link #ageFrames()} to {@code 0}.
     */
    public TrackRef(long trackId, TrackState state, DetectionSource source) {
        this(trackId, state, source, 0.0, 0.0, 0);
    }
}
