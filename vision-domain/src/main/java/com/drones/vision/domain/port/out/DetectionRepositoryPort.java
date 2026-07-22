package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DetectionQuery;

import java.util.List;

/**
 * Driven port: persist and query detection results.
 *
 * <p>{@link com.drones.vision.domain.model.VideoFrame VideoFrame}s
 * themselves are never stored here — {@link DetectionResult} references its
 * source frame by {@code (streamId, frameSequence, capturedAt)}, which is
 * exactly what allows detections to be persisted and later queried/replayed
 * independently of frame lifetime.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(DetectionResult)} appends a result; results are
 *       immutable historical records, never updated in place.</li>
 *   <li>{@link #query(DetectionQuery)} applies the
 *       given filters (all optional except {@code limit}, see {@link
 *       DetectionQuery}) and returns a snapshot list,
 *       not a live view.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@code save} is called
 * from the per-stream pipeline thread for every stream that produces
 * non-empty detections, potentially many streams at once, while {@code
 * query} may be called concurrently from control-plane reads.
 */
public interface DetectionRepositoryPort {

    /**
     * Persists a detection result.
     *
     * @param result the result to persist
     */
    void save(DetectionResult result);

    /**
     * Queries persisted detection results.
     *
     * @param query filter/limit criteria
     * @return matching results
     */
    List<DetectionResult> query(DetectionQuery query);
}
