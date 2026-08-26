package com.drones.vision.flight.application.telemetry;

import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;

import java.util.Objects;

/**
 * {@link TelemetryService} default implementation: a direct pass-through to {@link
 * TelemetryRepositoryPort#save} — see the interface javadoc for why this exists at all rather
 * than exposing the port to vision-perception directly.
 */
public final class DefaultTelemetryService implements TelemetryService {

    private final TelemetryRepositoryPort telemetryRepository;

    public DefaultTelemetryService(TelemetryRepositoryPort telemetryRepository) {
        this.telemetryRepository = Objects.requireNonNull(telemetryRepository, "telemetryRepository must not be null");
    }

    @Override
    public void record(UsageId usageId, Telemetry telemetry) {
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        telemetryRepository.save(usageId, telemetry);
    }
}
