package com.drones.vision.application;

import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.ZoneId;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The one implementation of {@link GeofenceService}.
 *
 * <p>Every mutation (create/update/delete) refreshes {@link GeofenceMonitor}'s enabled-zone cache
 * immediately afterward, so a zone change is visible to the very next telemetry sample evaluated —
 * per docs/OPS-CORE-PLAN.md §G's "cheap: zones cached in the monitor, refreshed on CRUD" design
 * note.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state is reached through the injected port/monitor.
 */
public final class DefaultGeofenceService implements GeofenceService {

    private final GeofenceRepositoryPort geofenceRepository;
    private final GeofenceMonitor geofenceMonitor;

    public DefaultGeofenceService(GeofenceRepositoryPort geofenceRepository, GeofenceMonitor geofenceMonitor) {
        this.geofenceRepository = Objects.requireNonNull(geofenceRepository, "geofenceRepository must not be null");
        this.geofenceMonitor = Objects.requireNonNull(geofenceMonitor, "geofenceMonitor must not be null");
    }

    @Override
    public List<GeofenceZone> zones() {
        return geofenceRepository.findAll().stream()
                .sorted(Comparator.comparing(GeofenceZone::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public Optional<GeofenceZone> find(ZoneId id) {
        Objects.requireNonNull(id, "id must not be null");
        return geofenceRepository.findById(id);
    }

    @Override
    public GeofenceZone create(GeofenceZoneSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        GeofenceZone zone = new GeofenceZone(ZoneId.random(), spec.name(), spec.kind(), spec.polygon(),
                spec.maxAltitudeMeters(), spec.enabled());
        GeofenceZone saved = geofenceRepository.save(zone);
        geofenceMonitor.refresh();
        return saved;
    }

    @Override
    public GeofenceZone update(ZoneId id, GeofenceZoneSpec spec) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        require(id);
        GeofenceZone updated = new GeofenceZone(id, spec.name(), spec.kind(), spec.polygon(),
                spec.maxAltitudeMeters(), spec.enabled());
        GeofenceZone saved = geofenceRepository.save(updated);
        geofenceMonitor.refresh();
        return saved;
    }

    @Override
    public void delete(ZoneId id) {
        Objects.requireNonNull(id, "id must not be null");
        require(id);
        geofenceRepository.deleteById(id);
        geofenceMonitor.refresh();
    }

    private GeofenceZone require(ZoneId id) {
        return geofenceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No geofence zone with id " + id));
    }
}
