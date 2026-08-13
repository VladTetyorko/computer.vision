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
 *       (typically chronological).</li>
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
     * Lists telemetry samples recorded for a usage.
     *
     * @param usageId the usage id
     * @param limit   maximum number of samples to return; must be positive
     * @return an immutable snapshot of samples for the usage
     */
    List<Telemetry> findByUsage(UsageId usageId, int limit);
}
