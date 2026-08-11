package com.drones.vision.app.devsupport;

import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.port.MarkRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MarkRepositoryPort}: dev fallback with no durability across restarts
 * (docs/plans/done/TACTICAL-MARKS-PLAN.md §3).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaMarkRepository} when {@code
 * vision.persistence.enabled=true}.
 */
public final class InMemoryMarkRepository implements MarkRepositoryPort {

    private final Map<MarkId, Mark> marks = new ConcurrentHashMap<>();

    @Override
    public Mark save(Mark mark) {
        marks.put(mark.id(), mark);
        return mark;
    }

    @Override
    public Optional<Mark> findById(MarkId id) {
        return Optional.ofNullable(marks.get(id));
    }

    @Override
    public List<Mark> findAll() {
        return List.copyOf(marks.values());
    }

    @Override
    public void deleteById(MarkId id) {
        marks.remove(id);
    }
}
