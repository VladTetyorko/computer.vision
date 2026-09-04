package com.drones.vision.flight.domain.port;

import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;

import java.util.List;

/**
 * Driven port: persist and retrieve telemetry samples recorded during an
 * asset usage.
 *
 * <p>Samples are appended per {@link UsageId} while a usage is open; the
 * corresponding {@code AssetUsage} row only holds a cheap summary (start/last
 * position, sample count), so the full trail is fetched from this port only
 * on demand (e.g. a map/trail view), keeping usage listing cheap. Append-only
 * and time-keyed (TimescaleDB-ready).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(UsageId, Telemetry)} appends a sample; samples are
 *       immutable historical records, never updated in place.</li>
 *   <li>{@link #findByUsage(UsageId, int)} returns samples for the given
 *       usage, bounded to at most {@code limit}; a snapshot, not a live
 *       view. Ordering is implementation-defined but must be consistent
 *       (typically chronological). It answers "the start of this flight,
 *       windowed" — {@code DefaultReplayService} relies on exactly that
 *       for its own windowing, so this method's semantics do not change.</li>
 *   <li>{@link #findLatestByUsage(UsageId, int)} answers a different
 *       question — "where is this flight right now" — the most recent
 *       {@code limit} samples, always returned in ascending {@code at}
 *       order (oldest of the window first) so every caller's chronological
 *       ordering assumption holds regardless of which method it called.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@code save} is called
 * from the collaborator driving the usage lifecycle, potentially once per
 * incoming telemetry sample for each open usage, while {@code findByUsage}
 * may be called concurrently from control-plane reads.
 */
public interface TelemetryRepositoryPort {

    /**
     * Appends a telemetry sample for the given usage.
     *
     * @param usageId   the usage this sample belongs to
     * @param telemetry the sample to persist
     */
    void save(UsageId usageId, Telemetry telemetry);

    /**
     * Lists telemetry samples recorded for a usage, earliest first.
     *
     * @param usageId the usage id
     * @param limit   maximum number of samples to return; must be positive
     * @return an immutable snapshot of samples for the usage
     */
    List<Telemetry> findByUsage(UsageId usageId, int limit);

    /**
     * Lists the most recently recorded telemetry samples for a usage — the
     * live-tail read a map/trail view actually wants for a long-running
     * flight, as opposed to {@link #findByUsage(UsageId, int)}'s
     * earliest-first window.
     *
     * @param usageId the usage id
     * @param limit   maximum number of samples to return; must be positive
     * @return an immutable snapshot of the latest {@code limit} samples for
     * the usage, in ascending {@code at} order (oldest of the window first,
     * most recent last)
     */
    List<Telemetry> findLatestByUsage(UsageId usageId, int limit);
}
