package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.FixedCameraPose;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CameraPoseTest {

    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);

    private static CameraPose pose(double yawDegrees) {
        return new CameraPose(AssetId.random(), POSITION, 10.0, yawDegrees, 15.0, 60.0, LayerId.random(),
                CameraPoseSource.MANUAL, null, Instant.now(), UserId.random());
    }

    // --- Validation ------------------------------------------------------------

    @Test
    void rejectsNullAssetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(null, POSITION, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), null, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
    }

    @Test
    void rejectsNegativeAgl() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, -0.1, 0.0, 15.0, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
    }

    @Test
    void normalizesYawIntoZeroToThreeSixty() {
        assertEquals(10.0, pose(370.0).yawDegrees(), 1e-9);
        assertEquals(350.0, pose(-10.0).yawDegrees(), 1e-9);
        assertEquals(0.0, pose(0.0).yawDegrees(), 1e-9);
    }

    @Test
    void rejectsNonFinitePitch() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, Double.NaN, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
    }

    @Test
    void rejectsPitchOutsideRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, -10.1, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 90.1, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
    }

    @Test
    void acceptsPitchAtRangeBoundaries() {
        assertEquals(-10.0, new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, -10.0, 60.0, LayerId.random(),
                CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()).pitchDegrees(), 1e-9);
        assertEquals(90.0, new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 90.0, 60.0, LayerId.random(),
                CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()).pitchDegrees(), 1e-9);
    }

    @Test
    void rejectsHfovOutsideExclusiveRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 10.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 160.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), UserId.random()));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                        null, null, Instant.now(), UserId.random()));
    }

    @Test
    void rejectsNegativeRmsErrorPixels() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                        CameraPoseSource.CALIBRATED, -1.0, Instant.now(), UserId.random()));
    }

    @Test
    void allowsNullRmsErrorPixelsForManualSource() {
        CameraPose pose = new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                CameraPoseSource.MANUAL, null, Instant.now(), UserId.random());
        assertEquals(null, pose.rmsErrorPixels());
    }

    @Test
    void rejectsNullTargetLayerIdIsActuallyAllowed() {
        // targetLayerId is the one field explicitly allowed to be null (falls back to the COP layer).
        CameraPose pose = new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 60.0, null,
                CameraPoseSource.MANUAL, null, Instant.now(), UserId.random());
        assertEquals(null, pose.targetLayerId());
    }

    @Test
    void rejectsNullUpdatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, null, UserId.random()));
    }

    @Test
    void rejectsNullUpdatedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraPose(AssetId.random(), POSITION, 10.0, 0.0, 15.0, 60.0, LayerId.random(),
                        CameraPoseSource.MANUAL, null, Instant.now(), null));
    }

    // --- toFixedCameraPose -------------------------------------------------------

    @Test
    void toFixedCameraPoseCarriesTheFiveGeometricNumbers() {
        CameraPose pose = pose(45.0);

        FixedCameraPose fixed = pose.toFixedCameraPose();

        assertEquals(pose.position(), fixed.position());
        assertEquals(pose.aglMeters(), fixed.aglMeters(), 1e-9);
        assertEquals(pose.yawDegrees(), fixed.yawDegrees(), 1e-9);
        assertEquals(pose.pitchDegrees(), fixed.pitchDegrees(), 1e-9);
        assertEquals(pose.hfovDegrees(), fixed.hfovDegrees(), 1e-9);
    }
}
