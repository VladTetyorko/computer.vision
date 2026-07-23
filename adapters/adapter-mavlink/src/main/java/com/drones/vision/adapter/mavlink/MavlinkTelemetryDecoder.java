package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Telemetry;

import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.BatteryStatus;
import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.common.SysStatus;
import io.dronefleet.mavlink.common.VfrHud;
import io.dronefleet.mavlink.minimal.Heartbeat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges a stream of {@link MavlinkMessage}s from one MAVLink system into {@link Telemetry}
 * samples, applying the unit conversions documented per field below. Stateful — one instance per
 * {@link com.drones.vision.domain.port.out.TelemetrySourcePort#open} call, never shared across
 * two runtimes, since it accumulates the latest known value of each field across messages.
 *
 * <h2>System-id stickiness</h2>
 * A single UDP port can carry more than one MAVLink system's traffic (e.g. a telemetry radio
 * relaying two vehicles, or stray traffic sharing the port). This decoder locks onto the
 * <b>first</b> {@link MavlinkMessage#getOriginSystemId()} it observes — of any message type,
 * including {@code HEARTBEAT} — and silently ignores every subsequent message from a different
 * system for the remainder of this decoder's lifetime. There is no re-election: if the first
 * system goes silent and a second one starts transmitting, its messages are dropped, not adopted.
 *
 * <h2>Message → field mapping</h2>
 * <ul>
 *   <li>{@code HEARTBEAT} — contributes no {@link Telemetry} field of its own; used only for
 *       liveness (an emitted sample carrying whatever else is already known) and, like every
 *       other recognized message, to lock in the system id on the very first message seen.</li>
 *   <li>{@code GLOBAL_POSITION_INT} — {@code lat}/{@code lon} (degrees × 1e7) → {@link
 *       Telemetry#latitude()}/{@link Telemetry#longitude()} (÷ 1e7); {@code alt} (mm, AMSL) →
 *       {@link Telemetry#altitudeMeters()} (÷ 1000 — the AMSL reading, not {@code relativeAlt}, to
 *       match {@link Telemetry#altitudeMeters()}'s "absolute" semantics used elsewhere in this
 *       codebase, e.g. {@code GeoPosition}); {@code hdg} (centidegrees, {@code 65535} = unknown) →
 *       {@link Telemetry#headingDegrees()} (÷ 100, or {@code null} when unknown); {@code
 *       vx}/{@code vy}/{@code vz} (cm/s, NED) → {@code extra} keys {@code vxMps}/{@code
 *       vyMps}/{@code vzMps} (÷ 100) — {@link Telemetry} has no named velocity fields.</li>
 *   <li>{@code SYS_STATUS}/{@code BATTERY_STATUS} — {@code batteryRemaining} (%, {@code -1} =
 *       unknown) → {@link Telemetry#batteryPercent()}. Both messages report the same domain
 *       field; whichever arrives most recently wins, with no separate per-source history kept.</li>
 *   <li>{@code VFR_HUD} — only {@code groundspeed} (already m/s, no conversion needed) → {@code
 *       extra} key {@code groundspeedMps}. Its own {@code alt}/{@code heading} are ignored in
 *       favor of {@code GLOBAL_POSITION_INT}'s more precise versions; {@code
 *       airspeed}/{@code throttle}/{@code climb} have no home in {@link Telemetry} and are
 *       dropped.</li>
 * </ul>
 * Every other MAVLink message type — there are hundreds in the common dialect alone — is
 * silently ignored: this decoder only maps what {@link Telemetry} actually has fields for.
 */
final class MavlinkTelemetryDecoder {

    private static final int UNKNOWN_HEADING_CENTIDEGREES = 65535;
    private static final int UNKNOWN_BATTERY_PERCENT = -1;

    private final DeviceId deviceId;

    private Integer systemId;

    private Double latitude;
    private Double longitude;
    private Double altitudeMeters;
    private Double headingDegrees;
    private Double batteryPercent;
    private Double vxMps;
    private Double vyMps;
    private Double vzMps;
    private Double groundspeedMps;

    MavlinkTelemetryDecoder(DeviceId deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * @param message the next decoded MAVLink message
     * @return the merged {@link Telemetry} snapshot reflecting {@code message}, or {@code null}
     *         if {@code message} is from a different (non-locked-in) system, or isn't one of the
     *         message types this decoder maps (see class javadoc)
     */
    Telemetry accept(MavlinkMessage<?> message) {
        int originSystemId = message.getOriginSystemId();
        if (systemId == null) {
            systemId = originSystemId;
        } else if (systemId != originSystemId) {
            return null; // a second system sharing this port: ignored, see class javadoc
        }

        Object payload = message.getPayload();
        if (payload instanceof GlobalPositionInt position) {
            applyPosition(position);
        } else if (payload instanceof SysStatus sysStatus) {
            applyBatteryPercent(sysStatus.batteryRemaining());
        } else if (payload instanceof BatteryStatus batteryStatus) {
            applyBatteryPercent(batteryStatus.batteryRemaining());
        } else if (payload instanceof VfrHud vfrHud) {
            groundspeedMps = (double) vfrHud.groundspeed();
        } else if (payload instanceof Heartbeat) {
            // Liveness only -- state above is unaffected; still emit a sample, see class javadoc.
        } else {
            return null;
        }
        return toTelemetry();
    }

    private void applyPosition(GlobalPositionInt position) {
        latitude = position.lat() / 1e7;
        longitude = position.lon() / 1e7;
        altitudeMeters = position.alt() / 1000.0;
        headingDegrees = position.hdg() == UNKNOWN_HEADING_CENTIDEGREES ? null : position.hdg() / 100.0;
        vxMps = position.vx() / 100.0;
        vyMps = position.vy() / 100.0;
        vzMps = position.vz() / 100.0;
    }

    private void applyBatteryPercent(int batteryRemainingPercent) {
        if (batteryRemainingPercent != UNKNOWN_BATTERY_PERCENT) {
            batteryPercent = (double) batteryRemainingPercent;
        }
    }

    private Telemetry toTelemetry() {
        Map<String, Double> extra = new LinkedHashMap<>();
        if (vxMps != null) {
            extra.put("vxMps", vxMps);
        }
        if (vyMps != null) {
            extra.put("vyMps", vyMps);
        }
        if (vzMps != null) {
            extra.put("vzMps", vzMps);
        }
        if (groundspeedMps != null) {
            extra.put("groundspeedMps", groundspeedMps);
        }
        return new Telemetry(deviceId, Instant.now(), latitude, longitude, altitudeMeters,
                headingDegrees, batteryPercent, extra);
    }
}
