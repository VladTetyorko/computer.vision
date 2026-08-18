package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDriftCalculatorTest {

    private static final Instant EARLIER = Instant.parse("2026-08-18T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-18T09:00:00Z");

    private static VehicleProfile profileAt(Instant observedAt, List<ParameterReading> parameters) {
        return new VehicleProfile("udp://0.0.0.0:14550#7", observedAt, 7, "ardupilot", "4.5.7", "quadcopter", null,
                List.of(), List.of(), parameters, null, true, null);
    }

    @Test
    void oneChangedParameterProducesExactlyOneDriftRowNamingItBothValuesAndBothTimestamps() {
        VehicleProfile earlier = profileAt(EARLIER, List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")));
        VehicleProfile later = profileAt(LATER, List.of(new ParameterReading("SR2_EXTRA2", 1.0, "REAL32")));

        List<ParameterDrift> drift = ConfigDriftCalculator.diff(earlier, later);

        assertEquals(List.of(new ParameterDrift("SR2_EXTRA2", 0.0, 1.0, EARLIER, LATER)), drift);
    }

    @Test
    void unchangedParametersProduceNoDrift() {
        VehicleProfile earlier = profileAt(EARLIER, List.of(new ParameterReading("RTL_ALT", 1500.0, "REAL32")));
        VehicleProfile later = profileAt(LATER, List.of(new ParameterReading("RTL_ALT", 1500.0, "REAL32")));

        assertTrue(ConfigDriftCalculator.diff(earlier, later).isEmpty());
    }

    @Test
    void aParameterPresentInOnlyOneSnapshotIsNotReportedAsDrift() {
        VehicleProfile earlier = profileAt(EARLIER, List.of(new ParameterReading("RTL_ALT", 1500.0, "REAL32")));
        VehicleProfile later = profileAt(LATER, List.of(new ParameterReading("SR2_EXTRA2", 1.0, "REAL32")));

        assertTrue(ConfigDriftCalculator.diff(earlier, later).isEmpty());
    }

    @Test
    void multipleChangedParametersAreSortedByName() {
        VehicleProfile earlier = profileAt(EARLIER, List.of(new ParameterReading("RTL_ALT", 1500.0, "REAL32"),
                new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")));
        VehicleProfile later = profileAt(LATER, List.of(new ParameterReading("RTL_ALT", 2000.0, "REAL32"),
                new ParameterReading("SR2_EXTRA2", 1.0, "REAL32")));

        List<ParameterDrift> drift = ConfigDriftCalculator.diff(earlier, later);

        assertEquals(List.of("RTL_ALT", "SR2_EXTRA2"), drift.stream().map(ParameterDrift::parameterName).toList());
    }

    @Test
    void laterObservedBeforeEarlierIsRejected() {
        VehicleProfile earlier = profileAt(LATER, List.of());
        VehicleProfile later = profileAt(EARLIER, List.of());

        assertThrows(IllegalArgumentException.class, () -> ConfigDriftCalculator.diff(earlier, later));
    }
}
