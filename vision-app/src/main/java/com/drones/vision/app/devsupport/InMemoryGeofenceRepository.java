package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.ZoneId;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GeofenceRepositoryPort}: dev fallback with no durability across restarts
 * (docs/OPS-CORE-PLAN.md §G).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaGeofenceRepository} when {@code
 * vision.persistence.enabled=true}.
 */
public final class InMemoryGeofenceRepository implements GeofenceRepositoryPort {

    private final Map<ZoneId, GeofenceZone> zones = new ConcurrentHashMap<>();

    @Override
    public GeofenceZone save(GeofenceZone zone) {
        zones.put(zone.id(), zone);
        return zone;
    }

    @Override
    public Optional<GeofenceZone> findById(ZoneId id) {
        return Optional.ofNullable(zones.get(id));
    }

    @Override
    public List<GeofenceZone> findAll() {
        return List.copyOf(zones.values());
    }

    @Override
    public void deleteById(ZoneId id) {
        zones.remove(id);
    }
}
