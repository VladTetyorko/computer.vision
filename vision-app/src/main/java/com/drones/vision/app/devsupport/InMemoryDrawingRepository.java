package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.DrawingId;
import com.drones.vision.domain.port.out.DrawingRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DrawingRepositoryPort} (docs/MAP-REWORK-PLAN.md §2.3) — the default-config
 * fallback for {@code JpaDrawingRepository}, selected when {@code vision.persistence.enabled} is
 * {@code false}.
 *
 * <p>A plain {@link ConcurrentHashMap}, no eviction/cap, matching {@link InMemoryMarkRepository}
 * exactly: {@code save} is put-by-id (upsert), {@code deleteById} is {@code Map#remove} (idempotent),
 * and {@code findAll} returns an unordered snapshot — ordering is {@code DefaultDrawingService}'s
 * job, not this class's.
 */
public final class InMemoryDrawingRepository implements DrawingRepositoryPort {

    private final Map<DrawingId, Drawing> drawings = new ConcurrentHashMap<>();

    @Override
    public Drawing save(Drawing drawing) {
        drawings.put(drawing.id(), drawing);
        return drawing;
    }

    @Override
    public Optional<Drawing> findById(DrawingId id) {
        return Optional.ofNullable(drawings.get(id));
    }

    @Override
    public List<Drawing> findAll() {
        return List.copyOf(drawings.values());
    }

    @Override
    public void deleteById(DrawingId id) {
        drawings.remove(id);
    }
}
