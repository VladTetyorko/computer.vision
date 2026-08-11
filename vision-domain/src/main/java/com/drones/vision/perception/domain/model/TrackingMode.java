package com.drones.vision.perception.domain.model;

/**
 * How the two perception loops cooperate for one stream (docs/plans/done/TRACKING-PLAN.md §1, §3.1).
 *
 * <p>{@code OFF} — detector only, no track ids; today's behavior, byte-identical. {@code
 * ASSOCIATE} (Mode A) — every detection carries a stable track id across sampled frames. {@code
 * FOLLOW} (Mode B) — a cheap per-frame tracker holds one locked target while the detector
 * duty-cycles down to a periodic verify pass. Pure marker, no behavior, no dedicated test (same
 * convention as {@code Capability}/{@code EventType}/{@code PixelFormat}).
 *
 * <p>{@link PipelineConfig#defaults()} ships {@link TrackingConfig#off()} — this mode — through
 * docs/plans/done/TRACKING-PLAN.md waves T2–T7; see that method's javadoc for why.
 */
public enum TrackingMode {
    OFF,
    ASSOCIATE,
    FOLLOW
}
