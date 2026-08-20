package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.Attitude;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoPrior;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.proto.v1.GeoControl;
import com.drones.vision.proto.v1.GeoEvidence;
import com.drones.vision.proto.v1.GeoFix;
import com.drones.vision.proto.v1.GeoStatus;
import com.drones.vision.proto.v1.GeoTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link GeoFixCodec} — most importantly D6's pitch-sign conversion (see {@link
 * #cameraPitchDegConvertsAttitudesPositiveUpGimbalPitchToRectifyPysDegreesFromNadir()}), plus the
 * telemetry/control/fix mapping's null-vs-absent handling.
 */
class GeoFixCodecTest {

    private static final DeviceId DEVICE_ID = DeviceId.random();

    /** {@code vision.geo.visual.mount-pitch-degrees}'s own default — boresight along the airframe. */
    private static final double NO_MOUNT_OFFSET = 0.0;

    // -- D6: the one and only pitch-sign conversion -------------------------------------------

    /**
     * {@link Attitude#gimbalPitchDegrees()} is <b>positive-up</b> (aircraft pitch convention);
     * {@code rectify.py}'s {@code camera_pitch_deg} wants <b>degrees from nadir</b> (0 = straight
     * down, 90 = horizon). Pinned at both boundary cases named in {@link
     * GeoFixCodec#PITCH_FROM_NADIR_OFFSET_DEGREES}'s own javadoc.
     */
    @Test
    void cameraPitchDegConvertsAttitudesPositiveUpGimbalPitchToRectifyPysDegreesFromNadir() {
        // Positive-up convention: gimbal pointed straight down is the MOST NEGATIVE pitch (-90).
        // Degrees-from-nadir convention: straight down is 0 (nadir itself).
        Telemetry straightDown = telemetryWithGimbalPitch(-90.0);
        GeoTelemetry wireStraightDown = GeoFixCodec.toWireGeoTelemetry(straightDown, NO_MOUNT_OFFSET);
        assertEquals(0.0, wireStraightDown.getCameraPitchDeg(), 1e-9,
                "gimbalPitchDegrees=-90 (positive-up, straight down) must become "
                        + "camera_pitch_deg=0 (degrees-from-nadir, nadir)");

        // Positive-up convention: gimbal level with the horizon is 0.
        // Degrees-from-nadir convention: level with the horizon is 90.
        Telemetry level = telemetryWithGimbalPitch(0.0);
        GeoTelemetry wireLevel = GeoFixCodec.toWireGeoTelemetry(level, NO_MOUNT_OFFSET);
        assertEquals(90.0, wireLevel.getCameraPitchDeg(), 1e-9,
                "gimbalPitchDegrees=0 (positive-up, level with horizon) must become "
                        + "camera_pitch_deg=90 (degrees-from-nadir, horizon)");
    }

    /**
     * H8's gimbal-less fallback (docs/plans/active/VISUAL-GEO-V2-PLAN.md §9.11 defect 2) — the SAME
     * degrees-from-nadir conversion, sourced from the airframe when MAVLink {@code ATTITUDE} (#30) is
     * all the aircraft reports. Both conventions are named explicitly here too: the airframe's own
     * pitch is positive-up, the mount offset is positive-up <em>relative to the airframe</em>, and the
     * wire value is degrees-from-nadir.
     */
    @Test
    void cameraPitchDegFallsBackToTheAirframePitchPlusTheMountOffsetWhenNoGimbalPitchIsReported() {
        // #30-only aircraft, level flight, camera bolted 36 degrees nose-down (positive-up: -36).
        // Positive-up camera pitch = 0 + (-36) = -36; degrees-from-nadir = 90 + (-36) = 54.
        GeoTelemetry level = GeoFixCodec.toWireGeoTelemetry(telemetryWithAirframePitch(0.0), -36.0);
        assertTrue(level.hasCameraPitchDeg(),
                "a gimbal-less aircraft must still send camera_pitch_deg -- before H8 it sent none and "
                        + "cv-service silently assumed nadir, so rectification never ran");
        assertEquals(54.0, level.getCameraPitchDeg(), 1e-9);

        // Nose 10 degrees down on the same mount: positive-up -10 + (-36) = -46; from nadir = 44.
        GeoTelemetry diving = GeoFixCodec.toWireGeoTelemetry(telemetryWithAirframePitch(-10.0), -36.0);
        assertEquals(44.0, diving.getCameraPitchDeg(), 1e-9);

        // The default offset (0.0) means "boresight along the airframe": level flight looks at the
        // horizon, which is 90 from nadir -- honest, and refused downstream rather than warped.
        GeoTelemetry boresighted =
                GeoFixCodec.toWireGeoTelemetry(telemetryWithAirframePitch(0.0), NO_MOUNT_OFFSET);
        assertEquals(90.0, boresighted.getCameraPitchDeg(), 1e-9);
    }

    @Test
    void aMeasuredGimbalPitchIsEarthFrameSoTheMountOffsetNeverAppliesToIt() {
        Telemetry withGimbal = new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, null, Map.of(),
                null, null, new Attitude(1.0, 20.0, 3.0, 4.0, -90.0, 5.0), null);

        GeoTelemetry wire = GeoFixCodec.toWireGeoTelemetry(withGimbal, -36.0);

        assertEquals(0.0, wire.getCameraPitchDeg(), 1e-9,
                "a reported gimbal pitch is absolute: neither the airframe's own pitch nor the fixed "
                        + "mount offset may be added to it");
    }

    @Test
    void cameraPitchDegIsAbsentWhenNeitherAGimbalNorAnAirframePitchIsReported() {
        Telemetry telemetry = new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, null, Map.of(),
                null, null, new Attitude(1.0, null, 3.0, 4.0, null, 5.0), null);

        GeoTelemetry wire = GeoFixCodec.toWireGeoTelemetry(telemetry, -36.0);

        assertFalse(wire.hasCameraPitchDeg(),
                "absence stays absence -- with no pitch reading at all there is nothing to shift, and "
                        + "the wire's own comment reads 'Absent = nadir assumed'");
    }

    private static Telemetry telemetryWithAirframePitch(double pitchDegrees) {
        Attitude attitude = new Attitude(0.0, pitchDegrees, 90.0, null, null, null);
        return new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, null, Map.of(), null, null, attitude,
                null);
    }

    @Test
    void cameraPitchDegIsAbsentWhenAttitudeItselfIsNull() {
        Telemetry telemetry = new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, null, Map.of());

        GeoTelemetry wire = GeoFixCodec.toWireGeoTelemetry(telemetry, NO_MOUNT_OFFSET);

        assertFalse(wire.hasCameraPitchDeg());
        assertFalse(wire.hasCameraRollDeg());
        assertFalse(wire.hasCameraYawDeg());
    }

    private static Telemetry telemetryWithGimbalPitch(double gimbalPitchDegrees) {
        Attitude attitude = new Attitude(null, null, null, null, gimbalPitchDegrees, null);
        return new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, null, Map.of(), null, null, attitude,
                null);
    }

    // -- toWireGeoTelemetry: null kernel fields stay unset, not fabricated zeros --------------

    @Test
    void toWireGeoTelemetryLeavesEveryAbsentFieldUnsetRatherThanZero() {
        Telemetry bare = new Telemetry(DEVICE_ID, Instant.ofEpochMilli(12345), null, null, null, null, null,
                Map.of());

        GeoTelemetry wire = GeoFixCodec.toWireGeoTelemetry(bare, NO_MOUNT_OFFSET);

        assertEquals(12345L, wire.getSampleMillis());
        assertFalse(wire.hasLatitude());
        assertFalse(wire.hasLongitude());
        assertFalse(wire.hasAmslMeters());
        assertFalse(wire.hasAglMeters());
        assertFalse(wire.hasHeadingDegrees());
        assertFalse(wire.hasGroundspeedMps());
        assertFalse(wire.hasCameraPitchDeg());
        assertFalse(wire.hasCameraRollDeg());
        assertFalse(wire.hasCameraYawDeg());
        // Deliberately never set by this codec -- see class javadoc "Fields deliberately left absent".
        assertFalse(wire.hasHorizontalFovDeg());
        assertFalse(wire.hasGpsRadiusMeters());
    }

    @Test
    void toWireGeoTelemetryCarriesEveryPresentFieldIncludingGroundspeedFromExtra() {
        Telemetry full = new Telemetry(DEVICE_ID, Instant.ofEpochMilli(99), 48.5, 32.0, 150.0, 90.0, 77.0,
                Map.of("groundspeedMps", 12.5), null, 80.0,
                new Attitude(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), null);

        GeoTelemetry wire = GeoFixCodec.toWireGeoTelemetry(full, NO_MOUNT_OFFSET);

        assertEquals(48.5, wire.getLatitude(), 1e-9);
        assertEquals(32.0, wire.getLongitude(), 1e-9);
        assertEquals(150.0, wire.getAmslMeters(), 1e-9);
        assertEquals(80.0, wire.getAglMeters(), 1e-9);
        assertEquals(90.0, wire.getHeadingDegrees(), 1e-9);
        assertEquals(12.5, wire.getGroundspeedMps(), 1e-9);
        assertEquals(95.0, wire.getCameraPitchDeg(), 1e-9); // 90 + gimbalPitchDegrees(5.0)
        assertEquals(4.0, wire.getCameraRollDeg(), 1e-9);
        assertEquals(6.0, wire.getCameraYawDeg(), 1e-9);
    }

    // -- toWireGeoPrior -------------------------------------------------------------------------

    @Test
    void toWireGeoPriorMapsEveryField() {
        GeoPrior prior = new GeoPrior(48.5, 32.0, 250.0);

        com.drones.vision.proto.v1.GeoPrior wire = GeoFixCodec.toWireGeoPrior(prior);

        assertEquals(48.5, wire.getLatitude(), 1e-9);
        assertEquals(32.0, wire.getLongitude(), 1e-9);
        assertEquals(250.0, wire.getRadiusMeters(), 1e-9);
    }

    // -- toWireGeoControlBuilder ------------------------------------------------------------------

    @Test
    void toWireGeoControlBuilderCarriesStreamIdRegionIdAndTargetFpsAndOmitsPriorWhenAbsent() {
        GeoSessionConfig config = new GeoSessionConfig("kyiv-pozniaky", 5.0f, null);

        GeoControl.Builder builder = GeoFixCodec.toWireGeoControlBuilder("stream-1", config);
        GeoControl control = builder.build();

        assertEquals("stream-1", control.getStreamId());
        assertEquals("kyiv-pozniaky", control.getRegionId());
        assertEquals(5.0f, control.getTargetFps(), 1e-6);
        assertFalse(control.hasPrior());
    }

    @Test
    void toWireGeoControlBuilderIncludesPriorWhenPresent() {
        GeoSessionConfig config = new GeoSessionConfig("", 0f, new GeoPrior(1.0, 2.0, 100.0));

        GeoControl control = GeoFixCodec.toWireGeoControlBuilder("stream-2", config).build();

        assertTrue(control.hasPrior());
        assertEquals(1.0, control.getPrior().getLatitude(), 1e-9);
    }

    // -- decode: GeoFix -> VisualFix --------------------------------------------------------------

    @Test
    void decodeMapsAFixWithPositionAndEveryOptionalFieldPresent() {
        GeoFix wire = GeoFix.newBuilder()
                .setStreamId("s1")
                .setSequence(3)
                .setFrameMillis(1000)
                .setStatus(GeoStatus.GEO_STATUS_FIX)
                .setRegionId("kyiv-pozniaky")
                .setTileId("17/76687/44230")
                .setLatitude(48.5)
                .setLongitude(32.0)
                .setYawDegrees(12.5)
                .setRadiusMeters(5.0)
                .setImpliedAglMeters(80.0)
                .setEvidence(fullEvidence())
                .setRefusal("")
                .setTelemetryAgeMillis(50)
                .setLatencyMillis(30)
                .build();

        VisualFix fix = GeoFixCodec.decode(wire);

        assertEquals(Instant.ofEpochMilli(1000), fix.frameAt());
        assertEquals(new GeoPosition(48.5, 32.0, null), fix.position());
        assertEquals(12.5, fix.yawDegrees(), 1e-9);
        assertEquals(5.0, fix.radiusMeters(), 1e-9);
        assertEquals(80.0, fix.impliedAglMeters(), 1e-9);
        assertEquals("kyiv-pozniaky", fix.regionId());
        assertEquals("17/76687/44230", fix.tileId());
        assertEquals("", fix.refusal());
        assertEquals(50L, fix.telemetryAgeMillis());
        assertEquals(30L, fix.latencyMillis());
        assertEquals(7, fix.evidence().candidateCount());
    }

    @Test
    void decodeMapsARefusalWithNoPositionAndNoOptionalFields() {
        GeoFix wire = GeoFix.newBuilder()
                .setStreamId("s1")
                .setFrameMillis(2000)
                .setStatus(GeoStatus.GEO_STATUS_NO_FIX)
                .setRegionId("")
                .setTileId("")
                .setRefusal("no candidate cleared the inlier-ratio gate")
                .setEvidence(GeoEvidence.getDefaultInstance())
                .build();

        VisualFix fix = GeoFixCodec.decode(wire);

        assertNull(fix.position());
        assertNull(fix.yawDegrees());
        assertNull(fix.radiusMeters());
        assertNull(fix.impliedAglMeters());
        assertEquals("no candidate cleared the inlier-ratio gate", fix.refusal());
    }

    @Test
    void decodeOfAFixStatusWithoutLatLonSurfacesAsIllegalArgumentException() {
        // GEO_STATUS_FIX but no latitude/longitude ever set -- a malformed response; VisualFix's own
        // compact constructor rejects it (position must be non-null iff refusal is empty).
        GeoFix malformed = GeoFix.newBuilder()
                .setStreamId("s1")
                .setFrameMillis(3000)
                .setStatus(GeoStatus.GEO_STATUS_FIX)
                .setEvidence(GeoEvidence.getDefaultInstance())
                .build();

        assertThrows(IllegalArgumentException.class, () -> GeoFixCodec.decode(malformed));
    }

    private static GeoEvidence fullEvidence() {
        return GeoEvidence.newBuilder()
                .setCandidateCount(7)
                .setMatchCount(40)
                .setInlierCount(30)
                .setInlierRatio(0.75)
                .setRerankMargin(0.2)
                .setReprojectionRmsPx(1.5)
                .setRectified(true)
                .setCellCalibrated(true)
                .setSupportingFrames(3)
                .setBaselineMeters(12.0)
                .setSequenceConverged(true)
                .setSequenceSpreadMeters(2.0)
                .setSequenceUpdates(5)
                .setOsmPrior(1.0)
                .build();
    }
}
