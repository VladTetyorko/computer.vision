package com.drones.vision.warehouse.application.usage;

/**
 * Closes every open {@link com.drones.vision.warehouse.domain.model.AssetUsage} that has gone idle
 * for longer than {@link IdleUsageCloseSettings#idleThreshold()}
 * (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1) — the backend half of "usages close
 * themselves".
 *
 * <h2>Why a usage needs this at all</h2>
 * A usage is opened by a stream start ({@code UsageTracker#onStreamStarted}) or an operator {@code
 * engage} call, and is meant to be closed the same way — a stream stop or a {@code disengage}. But
 * {@code DefaultStreamService#stop} (vision-perception) is the <b>only</b> path that ever ends a
 * stream, and neither a video-source failure nor a telemetry-source failure alone ever calls it (its
 * own javadoc: "a source failure alone never does, see {@code #start}") — both retry forever via
 * {@code SupervisedPublisher} instead. A crashed process, a killed station, or a lost MAVLink link on
 * an offline rover therefore leaves a usage open with no code path left to close it: {@code GET
 * /api/usages} reports it "Flying now" forever, days after the aircraft went dark.
 *
 * <p>{@code vision-app}'s runner (following the {@code TrackProjectionRunner} template — this
 * codebase never uses {@code @Scheduled}/{@code @EnableScheduling}) drives this sweep once at
 * startup, so a crashed station never restarts with ghosts, and on a fixed cadence thereafter.
 *
 * <p>Unscoped, like {@link UsageSessionService}: this is an internal background job with no acting
 * user, not a caller-facing read or write behind a {@link com.drones.vision.platform.VisibilityScope}.
 */
public interface UsageIdleCloseService {

    /**
     * Sweeps every currently open usage, closing any whose last activity is at least {@link
     * IdleUsageCloseSettings#idleThreshold()} old.
     *
     * <p>"Last activity" is the asset's freshest telemetry sample ({@link
     * com.drones.vision.warehouse.domain.port.AssetLiveStatePort#latestTelemetry}), or the usage's
     * own {@code startedAt} when none is known — which is also what a fresh-process restart falls
     * back to, since that fact lives only in perception's in-memory tracker and does not survive a
     * crash. A closed usage's {@code endedAt} is stamped at exactly that instant, never "now"
     * (CLAUDE.md rule 9): the record must not claim minutes nobody actually observed.
     *
     * @return the number of usages this sweep closed
     */
    int closeIdleUsages();
}
