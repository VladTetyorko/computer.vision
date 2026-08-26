package com.drones.vision.flight.application.telemetry;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3: the ownership seam that replaced
 * vision-perception's direct {@link TelemetryRepositoryPort} import — see {@link
 * TelemetryService}'s own javadoc.
 */
class DefaultTelemetryServiceTest {

    private TelemetryRepositoryPort telemetryRepository;
    private DefaultTelemetryService service;

    @BeforeEach
    void setUp() {
        telemetryRepository = mock(TelemetryRepositoryPort.class);
        service = new DefaultTelemetryService(telemetryRepository);
    }

    @Test
    void recordDelegatesToTheRepository() {
        UsageId usageId = UsageId.random();
        Telemetry sample = new Telemetry(DeviceId.random(), Instant.now(), 50.0, 30.0, 95.0, 0.0, 80.0, Map.of());

        service.record(usageId, sample);

        verify(telemetryRepository).save(usageId, sample);
    }

    @Test
    void rejectsNullArguments() {
        Telemetry sample = new Telemetry(DeviceId.random(), Instant.now(), 50.0, 30.0, 95.0, 0.0, 80.0, Map.of());
        assertThrows(NullPointerException.class, () -> service.record(null, sample));
        assertThrows(NullPointerException.class, () -> service.record(UsageId.random(), null));
    }

    @Test
    void constructorRejectsNullRepository() {
        assertThrows(NullPointerException.class, () -> new DefaultTelemetryService(null));
    }
}
