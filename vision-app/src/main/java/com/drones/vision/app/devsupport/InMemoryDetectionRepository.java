package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link DetectionRepositoryPort}: dev/Phase-0 fallback with no
 * durability across restarts and a naive linear-scan query.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres), planned for
 * Phase 2.
 */
public final class InMemoryDetectionRepository implements DetectionRepositoryPort {

    private final List<DetectionResult> results = new CopyOnWriteArrayList<>();

    @Override
    public void save(DetectionResult result) {
        results.add(result);
    }

    @Override
    public List<DetectionResult> query(DetectionQuery query) {
        return results.stream()
                .filter(r -> query.streamId() == null || query.streamId().equals(r.streamId()))
                .filter(r -> query.from() == null || !r.capturedAt().isBefore(query.from()))
                .filter(r -> query.to() == null || !r.capturedAt().isAfter(query.to()))
                .filter(r -> query.label() == null
                        || r.detections().stream().anyMatch(d -> query.label().equals(d.label())))
                .limit(query.limit())
                .toList();
    }
}
