package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.MapLayer;
import com.drones.vision.domain.port.out.MapLayerRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MapLayerRepositoryPort} (docs/plans/done/MAP-REWORK-PLAN.md §2.3) — the default-config
 * fallback for {@code JpaMapLayerRepository}, selected when {@code vision.persistence.enabled} is
 * {@code false}.
 *
 * <p>A plain {@link ConcurrentHashMap}, no eviction/cap — the same shape {@link
 * InMemoryMarkRepository}/{@link InMemoryGeofenceRepository} have. {@code save} is put-by-id, which
 * replaces the layer's grant list wholesale exactly as the JPA implementation's {@code merge} over
 * the {@code map_layer_grants} element collection does; {@code deleteById} is {@code Map#remove},
 * idempotent and non-cascading (cascading to marks/drawings is {@code DefaultMapLayerService}'s job,
 * since each removed row also has to publish its own {@code MapEvent}).
 *
 * <p>Because this is what the default-config app actually runs, the COP layer is <em>not</em>
 * pre-seeded here the way {@code V12__map_layers.sql} seeds it for Postgres: {@code
 * LayerResolver#copLayerId()} is a synchronized find-or-create, and {@code vision-app}'s startup
 * runner calls it once, so both persistence modes converge on exactly one COP layer without this
 * class knowing anything about layer kinds.
 */
public final class InMemoryMapLayerRepository implements MapLayerRepositoryPort {

    private final Map<LayerId, MapLayer> layers = new ConcurrentHashMap<>();

    @Override
    public MapLayer save(MapLayer layer) {
        layers.put(layer.id(), layer);
        return layer;
    }

    @Override
    public Optional<MapLayer> findById(LayerId id) {
        return Optional.ofNullable(layers.get(id));
    }

    @Override
    public List<MapLayer> findAll() {
        return List.copyOf(layers.values());
    }

    @Override
    public void deleteById(LayerId id) {
        layers.remove(id);
    }
}
