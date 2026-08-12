package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraAttitudeTest {

    private static final Instant AT = Instant.parse("2026-08-12T10:15:30Z");

    private static Telemetry telemetry(Double headingDegrees) {
        return new Telemetry(DeviceId.random(), AT, 50.0, 30.0, 120.0, headingDegrees, 80.0, Map.of());
    }

    @Test
    void rejectsANullTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> new CameraAttitude(10, 0, 0, 60, 0, null));
    }

    @Test
    void rejectsANonFiniteAngle() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraAttitude(Double.NaN, 0, 0, 60, 0, AT));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraAttitude(0, Double.POSITIVE_INFINITY, 0, 60, 0, AT));
    }

    @Test
    void rejectsAFieldOfViewOutsideZeroToOneEighty() {
        assertThrows(IllegalArgumentException.class, () -> new CameraAttitude(0, 0, 0, -1, 0, AT));
        assertThrows(IllegalArgumentException.class, () -> new CameraAttitude(0, 0, 0, 180, 0, AT));
        assertThrows(IllegalArgumentException.class, () -> new CameraAttitude(0, 0, 0, 60, 200, AT));
    }

    @Test
    void aZeroFieldOfViewIsUnknownRatherThanInvalid() {
        CameraAttitude attitude = new CameraAttitude(45, 0, 0, 0, 0, AT);

        // Legal to construct, but not usable: cv-service gates pose compensation on hfov > 0, and
        // this side agrees rather than assuming.
        assertFalse(attitude.known());
    }

    @Test
    void isKnownOnceAFieldOfViewIsSupplied() {
        assertTrue(CameraAttitude.ofYaw(45, 60, AT).known());
    }

    @Test
    void ofYawLeavesPitchRollAndVerticalFovUnset() {
        CameraAttitude attitude = CameraAttitude.ofYaw(45, 60, AT);

        assertEquals(45.0, attitude.yawDegrees());
        assertEquals(0.0, attitude.pitchDegrees());
        assertEquals(0.0, attitude.rollDegrees());
        assertEquals(60.0, attitude.hfovDegrees());
        // 0 = "derive it from hfov and the frame aspect ratio", which is what cv-service does.
        assertEquals(0.0, attitude.vfovDegrees());
        assertEquals(AT, attitude.at());
    }

    @Test
    void buildsFromTelemetryHeading() {
        CameraAttitude attitude = CameraAttitude.from(telemetry(137.5), 60.0);

        assertEquals(137.5, attitude.yawDegrees());
        assertEquals(60.0, attitude.hfovDegrees());
        assertEquals(AT, attitude.at());
    }

    @Test
    void isNullWithoutTelemetry() {
        assertNull(CameraAttitude.from(null, 60.0));
    }

    @Test
    void isNullWhenTelemetryCarriesNoHeading() {
        // Deliberately null rather than a zeroed attitude: 0.0 is due north, a real bearing, so a
        // placeholder would feed the compensator a fabricated delta on every telemetry dropout.
        assertNull(CameraAttitude.from(telemetry(null), 60.0));
    }

    @Test
    void carriesAnUnknownFieldOfViewThroughRatherThanRejectingTheSample() {
        CameraAttitude attitude = CameraAttitude.from(telemetry(90.0), 0.0);

        assertEquals(90.0, attitude.yawDegrees());
        assertFalse(attitude.known());
    }
}
