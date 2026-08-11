package com.drones.vision.app.devsupport;

import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link TelemetryRepositoryPort}: dev/Phase-0 fallback with no
 * durability across restarts.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres, TimescaleDB-ready
 * per the port's javadoc), planned for Phase 2.
 */
public final class InMemoryTelemetryRepository implements TelemetryRepositoryPort {

    private final Map<UsageId, List<Telemetry>> samplesByUsage = new ConcurrentHashMap<>();

    @Override
    public void save(UsageId usageId, Telemetry telemetry) {
        samplesByUsage.computeIfAbsent(usageId, id -> new CopyOnWriteArrayList<>()).add(telemetry);
    }

    @Override
    public List<Telemetry> findByUsage(UsageId usageId, int limit) {
        List<Telemetry> samples = samplesByUsage.get(usageId);
        if (samples == null) {
            return List.of();
        }
        return samples.stream().limit(limit).toList();
    }
}
