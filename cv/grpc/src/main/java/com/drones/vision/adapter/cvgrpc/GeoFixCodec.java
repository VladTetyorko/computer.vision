package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.Attitude;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.proto.v1.GeoControl;
import com.drones.vision.proto.v1.GeoEvidence;
import com.drones.vision.proto.v1.GeoFix;
import com.drones.vision.proto.v1.GeoStatus;
import com.drones.vision.proto.v1.GeoTelemetry;

import java.time.Instant;

/**
 * Pure mapping between the kernel/perception geolocation model and cv-service's {@code
 * Geolocation.LocalizeStream} wire types (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.1) — the geo
 * sibling of {@link DetectionFrameCodec}, static methods only, no state.
 *
 * <h2>D6 — the one and only pitch-sign conversion</h2>
 * {@link Attitude#gimbalPitchDegrees()} is <b>positive-up</b> (matching aircraft pitch convention);
 * {@code rectify.py} wants {@code camera_pitch_deg} as <b>degrees from nadir</b> (0 = straight down,
 * 90 = horizon). The plan names the exact formula: {@code pitch_from_nadir = 90 + gimbalPitch}.
 * {@link #toWireGeoTelemetry} is the <em>only</em> place in this codebase that performs this
 * conversion — see {@link #PITCH_FROM_NADIR_OFFSET_DEGREES}'s own javadoc for the two boundary
 * cases this formula is pinned against, and {@code GeoFixCodecTest} for the unit test naming both
 * conventions explicitly.
 *
 * <h2>Fields deliberately left absent</h2>
 * <ul>
 *   <li>{@code gps_radius_meters} — no HDOP-to-meters conversion formula exists anywhere in this
 *   codebase; inventing one here would be exactly the "unsourced conversion formula" {@code
 *   contexts/vision-flight}'s {@code DefaultTrackCorrectionService} already flagged and refused to
 *   invent for its own {@code gps_radius_meters}-shaped gap (H2a dated amendment, CLAUDE.md rule 1
 *   "no magic numbers"). Left {@code optional}-absent; the wire's own comment ("the raw fix's own
 *   1-sigma, from HDOP when known") already documents this as a legitimately absent field.</li>
 *   <li>{@code horizontal_fov_deg} — {@link GeoSessionConfig} carries no per-session hfov; the wire
 *   comment says {@code 0/absent = the region's assumed default}, exactly this codec's intent.</li>
 * </ul>
 */
final class GeoFixCodec {

    /**
     * D6's conversion constant: {@code rectify.py}'s degrees-from-nadir convention is {@code 90 -
     * (-gimbalPitch)}, i.e. {@code 90 + gimbalPitch}. Pinned at both ends: a gimbal pointed straight
     * down ({@code gimbalPitchDegrees == -90}, the most negative positive-up pitch) yields {@code
     * pitch_from_nadir == 0} (nadir); a gimbal level with the horizon ({@code gimbalPitchDegrees ==
     * 0}) yields {@code pitch_from_nadir == 90} (horizon) — both asserted by name in {@code
     * GeoFixCodecTest}.
     */
    private static final double PITCH_FROM_NADIR_OFFSET_DEGREES = 90.0;

    /** {@code Telemetry.extra()} key this codec reads {@code groundspeed_mps} from — no dedicated field exists. */
    private static final String GROUNDSPEED_EXTRA_KEY = "groundspeedMps";

    private GeoFixCodec() {
    }

    /**
     * {@link Telemetry} restated as wire {@code GeoTelemetry} — every field {@code optional} on the
     * wire, so a {@code null} kernel field simply stays unset rather than becoming a fabricated
     * zero.
     */
    static GeoTelemetry toWireGeoTelemetry(Telemetry telemetry) {
        GeoTelemetry.Builder builder = GeoTelemetry.newBuilder()
                .setSampleMillis(telemetry.at().toEpochMilli());
        if (telemetry.latitude() != null) {
            builder.setLatitude(telemetry.latitude());
        }
        if (telemetry.longitude() != null) {
            builder.setLongitude(telemetry.longitude());
        }
        if (telemetry.altitudeMeters() != null) {
            builder.setAmslMeters(telemetry.altitudeMeters());
        }
        if (telemetry.aglMeters() != null) {
            builder.setAglMeters(telemetry.aglMeters());
        }
        if (telemetry.headingDegrees() != null) {
            builder.setHeadingDegrees(telemetry.headingDegrees());
        }
        Double groundspeed = telemetry.extra().get(GROUNDSPEED_EXTRA_KEY);
        if (groundspeed != null) {
            builder.setGroundspeedMps(groundspeed);
        }
        Attitude attitude = telemetry.attitude();
        if (attitude != null) {
            Double gimbalPitch = attitude.gimbalPitchDegrees();
            if (gimbalPitch != null) {
                builder.setCameraPitchDeg(PITCH_FROM_NADIR_OFFSET_DEGREES + gimbalPitch);
            }
            if (attitude.gimbalRollDegrees() != null) {
                builder.setCameraRollDeg(attitude.gimbalRollDegrees());
            }
            if (attitude.gimbalYawDegrees() != null) {
                builder.setCameraYawDeg(attitude.gimbalYawDegrees());
            }
        }
        // horizontal_fov_deg and gps_radius_meters left absent -- see class javadoc.
        return builder.build();
    }

    /** {@link com.drones.vision.perception.domain.model.GeoPrior} restated as wire {@code GeoPrior}. */
    static com.drones.vision.proto.v1.GeoPrior toWireGeoPrior(
            com.drones.vision.perception.domain.model.GeoPrior prior) {
        return com.drones.vision.proto.v1.GeoPrior.newBuilder()
                .setLatitude(prior.latitude())
                .setLongitude(prior.longitude())
                .setRadiusMeters(prior.radiusMeters())
                .build();
    }

    /**
     * {@code stream_id}/{@code region_id}/{@code target_fps} plus, when present, {@code prior} —
     * every hot field {@link GeolocationSession} restates on each outbound {@code GeoControl}
     * message. {@code source_url}/{@code rtsp_transport} are the caller's concern (sent once, on the
     * session's first message only); {@code telemetry}/{@code stop} are merged in by the caller too.
     */
    static GeoControl.Builder toWireGeoControlBuilder(String streamId, GeoSessionConfig config) {
        GeoControl.Builder builder = GeoControl.newBuilder()
                .setStreamId(streamId)
                .setRegionId(config.regionId())
                .setTargetFps(config.targetFps());
        if (config.prior() != null) {
            builder.setPrior(toWireGeoPrior(config.prior()));
        }
        return builder;
    }

    /**
     * Wire {@code GeoFix} decoded into a kernel {@link VisualFix}. A wire response that violates
     * {@link VisualFix}'s own invariants (e.g. {@code status == GEO_STATUS_FIX} without a
     * latitude/longitude) surfaces as the same {@link IllegalArgumentException} {@link VisualFix}'s
     * compact constructor throws for any other caller — deliberately not defended against here a
     * second time; {@link GeolocationSession#onResponse} catches it, logs, and drops just that one
     * malformed item (mirroring {@link DetectionFrameCodec}/{@link PulledDetectionSession}'s "never
     * guess" convention).
     */
    static VisualFix decode(GeoFix wire) {
        boolean isFix = wire.getStatus() == GeoStatus.GEO_STATUS_FIX;
        GeoPosition position = isFix && wire.hasLatitude() && wire.hasLongitude()
                ? new GeoPosition(wire.getLatitude(), wire.getLongitude(), null)
                : null;
        Double yawDegrees = wire.hasYawDegrees() ? wire.getYawDegrees() : null;
        Double radiusMeters = wire.hasRadiusMeters() ? wire.getRadiusMeters() : null;
        Double impliedAglMeters = wire.hasImpliedAglMeters() ? wire.getImpliedAglMeters() : null;

        return new VisualFix(
                Instant.ofEpochMilli(wire.getFrameMillis()),
                position,
                yawDegrees,
                radiusMeters,
                impliedAglMeters,
                wire.getRegionId(),
                wire.getTileId(),
                wire.getRefusal(),
                toDomainEvidence(wire.getEvidence()),
                wire.getTelemetryAgeMillis(),
                wire.getLatencyMillis());
    }

    private static VisualFixEvidence toDomainEvidence(GeoEvidence wire) {
        return new VisualFixEvidence(
                wire.getCandidateCount(),
                wire.getMatchCount(),
                wire.getInlierCount(),
                wire.getInlierRatio(),
                wire.getRerankMargin(),
                wire.getReprojectionRmsPx(),
                wire.getRectified(),
                wire.getCellCalibrated(),
                wire.getSupportingFrames(),
                wire.getBaselineMeters(),
                wire.getSequenceConverged(),
                wire.getSequenceSpreadMeters(),
                wire.getSequenceUpdates(),
                wire.getOsmPrior());
    }
}
