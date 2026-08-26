package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import java.time.Instant;

/**
 * A "flight"/session for an {@link Asset}: opened when the asset starts
 * streaming, closed on stop.
 *
 * <p>Holds a cheap summary of the usage — start/last known position and
 * sample count — so lists render without touching the underlying telemetry
 * trail; individual samples persist separately, keyed by this usage's id,
 * via {@code TelemetryRepositoryPort} and are fetched only on demand. Usages
 * are append-only and time-keyed (TimescaleDB-ready). {@code endedAt}, the
 * position fields, and {@code streamId} are nullable — an open usage has no
 * end time and may not yet have received a telemetry sample.
 *
 * <p>{@code streamId} (docs/plans/done/MVP2-PLAN.md §R, R-a2) is the {@link StreamId} of
 * the video stream whose start opened this usage — recorded once, at open
 * time, by {@code UsageTracker} (vision-application), and never changed
 * afterward for the life of the usage. It exists purely so a finished usage
 * can be joined back to its {@link DetectionResult}s (keyed by {@code
 * StreamId}) for flight replay ({@code ReplayService}); a usage opened
 * before this field existed, or opened by an asset with no video device,
 * carries {@code null} here — honestly, not as an error.
 *
 * <p>{@code origin} (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2) records
 * <em>which verb</em> opened this session — see {@link UsageOrigin} for the three values and
 * {@code UsageTracker#engage}/{@code #disengage} for the collision rules it governs once a stream
 * and an operator both touch the same session. Persisted (not just an in-memory fact) so
 * engagement survives a process restart.
 *
 * @param id            typed usage identity
 * @param assetId       the asset this usage belongs to
 * @param startedAt     when the usage was opened
 * @param endedAt       when the usage was closed, or {@code null} if still open; if present, must not be before {@code startedAt}
 * @param startPosition position at the first received sample, or {@code null} if none yet
 * @param lastPosition  position at the most recently received sample, or {@code null} if none yet
 * @param sampleCount   number of telemetry samples received during this usage; must not be negative
 * @param streamId      the stream whose start opened this usage, or {@code null} for a legacy/streamless usage
 * @param phase         this usage's aircraft-state phase (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3,
 *                      Wave O7); driven by {@code UsageTracker} in vision-perception, see {@link UsagePhase}
 * @param origin        which verb opened this session — see {@link UsageOrigin}
 */
public record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition,
                          GeoPosition lastPosition, long sampleCount, StreamId streamId, UsagePhase phase,
                          UsageOrigin origin) {

    public AssetUsage {
        if (id == null) {
            throw new IllegalArgumentException("AssetUsage id must not be null");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("AssetUsage assetId must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("AssetUsage startedAt must not be null");
        }
        if (endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "AssetUsage endedAt must not be before startedAt: " + endedAt + " < " + startedAt);
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("AssetUsage sampleCount must not be negative: " + sampleCount);
        }
        if (phase == null) {
            throw new IllegalArgumentException("AssetUsage phase must not be null");
        }
        if (origin == null) {
            throw new IllegalArgumentException("AssetUsage origin must not be null");
        }
    }

    /**
     * Convenience constructor for the pre-R2 shape — defaults {@link #origin()} to {@link
     * UsageOrigin#STREAM}, keeping every pre-existing call site (including the two convenience
     * overloads below, which route through this one) compiling unchanged. Every call site within
     * this wave's file scope passes {@code origin} explicitly instead; this overload exists only
     * because callers in vision-flight/vision-events/vision-learning (outside this wave's scope)
     * still construct an {@code AssetUsage} through it directly — see this class's MODULE.md entry
     * for the precedent ({@code phase}'s own pre-O7 default) this follows.
     */
    public AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition,
                       GeoPosition lastPosition, long sampleCount, StreamId streamId, UsagePhase phase) {
        this(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId, phase,
                UsageOrigin.STREAM);
    }

    /**
     * Convenience constructor for the pre-O7 shape — defaults {@link #phase()} to {@link
     * UsagePhase#PREFLIGHT} (and, as of wave R2, {@link #origin()} to {@link UsageOrigin#STREAM}),
     * keeping every pre-existing call site (including the streamless 7-arg overload below, which
     * routes through this one) compiling unchanged.
     */
    public AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition,
                       GeoPosition lastPosition, long sampleCount, StreamId streamId) {
        this(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
                UsagePhase.PREFLIGHT);
    }

    /**
     * Convenience constructor for a usage with no recorded stream (legacy rows, or callers that
     * predate {@code streamId} — see the class javadoc).
     */
    public AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition,
                       GeoPosition lastPosition, long sampleCount) {
        this(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, null);
    }

    /**
     * Returns a copy of this usage closed at the given instant.
     *
     * @param endedAt when the usage was closed; must not be before {@code startedAt}
     * @return a new {@code AssetUsage} with {@code endedAt} set
     */
    public AssetUsage closed(Instant endedAt) {
        return new AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
                phase, origin);
    }

    /**
     * Returns a copy of this usage with updated start/last positions.
     *
     * @param startPosition position at the first received sample, or {@code null} if none yet
     * @param lastPosition  position at the most recently received sample, or {@code null} if none yet
     * @return a new {@code AssetUsage} with the positions replaced
     */
    public AssetUsage withPositions(GeoPosition startPosition, GeoPosition lastPosition) {
        return new AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
                phase, origin);
    }

    /**
     * Returns a copy of this usage with a different sample count.
     *
     * @param sampleCount number of telemetry samples received during this usage; must not be negative
     * @return a new {@code AssetUsage} with {@code sampleCount} replaced
     */
    public AssetUsage withSampleCount(long sampleCount) {
        return new AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
                phase, origin);
    }

    /**
     * Returns a copy of this usage with a different phase.
     *
     * @param phase the replacement phase
     * @return a new {@code AssetUsage} with {@code phase} replaced
     */
    public AssetUsage withPhase(UsagePhase phase) {
        return new AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
                phase, origin);
    }

    /**
     * Returns a copy of this usage with a different origin — used to <em>promote</em> a
     * stream-opened session to operator-owned once an operator engages an already-streaming asset
     * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2; see {@code
     * UsageTracker#engage}'s javadoc for the collision rule this exists for).
     *
     * @param origin the replacement origin
     * @return a new {@code AssetUsage} with {@code origin} replaced
     */
    public AssetUsage withOrigin(UsageOrigin origin) {
        return new AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
                phase, origin);
    }
}
