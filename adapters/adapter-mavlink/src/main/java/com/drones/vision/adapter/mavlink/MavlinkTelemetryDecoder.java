package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FlightState;
import com.drones.vision.domain.model.Telemetry;

import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.EkfStatusReport;
import io.dronefleet.mavlink.ardupilotmega.Rangefinder;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.BatteryStatus;
import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.common.GpsRawInt;
import io.dronefleet.mavlink.common.MissionCurrent;
import io.dronefleet.mavlink.common.RcChannels;
import io.dronefleet.mavlink.common.RcChannelsRaw;
import io.dronefleet.mavlink.common.Statustext;
import io.dronefleet.mavlink.common.SysStatus;
import io.dronefleet.mavlink.common.VfrHud;
import io.dronefleet.mavlink.common.Vibration;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavState;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges a stream of {@link MavlinkMessage}s from one MAVLink system into {@link Telemetry}
 * samples, applying the unit conversions documented per field below. Stateful — one instance per
 * {@link com.drones.vision.domain.port.out.TelemetrySourcePort#open} call, never shared across
 * two runtimes, since it accumulates the latest known value of each field across messages.
 *
 * <h2>System-id stickiness (now enforced one level up, by {@link MavlinkSocketHub})</h2>
 * A single UDP port can carry more than one MAVLink system's traffic (e.g. a telemetry radio
 * relaying two vehicles, or stray traffic sharing the port). This decoder still locks onto the
 * <b>first</b> {@link MavlinkMessage#getOriginSystemId()} it observes — of any message type,
 * including {@code HEARTBEAT} — and silently ignores every subsequent message from a different
 * system for the remainder of this decoder's lifetime; that invariant is exercised directly by
 * this class's own unit tests and remains true for any caller that feeds one decoder instance
 * mixed-system traffic. As of docs/DRONE-INFRA-PLAN.md I-a, {@link MavlinkTelemetrySource} is no
 * longer such a caller: {@link MavlinkSocketHub} demultiplexes by sysid <b>before</b> a message
 * ever reaches a decoder, and hands each claiming device a <b>fresh</b> decoder on every claim or
 * re-election, so in practice a decoder here only ever sees one system's messages for its whole
 * lifetime, and this class's own lock never actually rejects anything at the system level — see
 * {@link MavlinkSocketHub}'s javadoc for the claim/re-election rules that replaced the old
 * "first sysid wins, no re-adoption" single-device gotcha.
 *
 * <h2>Message → field mapping</h2>
 * <ul>
 *   <li>{@code HEARTBEAT} — {@code autopilot} → {@link FlightState#firmware()} ({@code
 *       "ardupilot"}/{@code "generic"}/{@code "px4"}/{@code null}, see {@link FlightModes});
 *       {@code base_mode} bit {@code 128} (safety-armed) → {@link FlightState#armed()}; {@code
 *       base_mode} bit {@code 1} (custom-mode-enabled) gates whether {@code custom_mode} is
 *       resolved through {@link FlightModes#name(int, int, long)} into {@link
 *       FlightState#mode()} — left unchanged otherwise; {@code system_status ==
 *       MAV_STATE_CRITICAL} → {@link FlightState#failsafe()}. Arming becoming {@code true} clears
 *       any accumulated {@link FlightState#armingBlockers()} (see {@code STATUSTEXT} below).</li>
 *   <li>{@code GLOBAL_POSITION_INT} — {@code lat}/{@code lon} (degrees × 1e7) → {@link
 *       Telemetry#latitude()}/{@link Telemetry#longitude()} (÷ 1e7); {@code alt} (mm, AMSL) →
 *       {@link Telemetry#altitudeMeters()} (÷ 1000 — the AMSL reading, not {@code relativeAlt}, to
 *       match {@link Telemetry#altitudeMeters()}'s "absolute" semantics used elsewhere in this
 *       codebase, e.g. {@code GeoPosition}); {@code hdg} (centidegrees, {@code 65535} = unknown) →
 *       {@link Telemetry#headingDegrees()} (÷ 100, or {@code null} when unknown); {@code
 *       vx}/{@code vy}/{@code vz} (cm/s, NED) → {@code extra} keys {@code vxMps}/{@code
 *       vyMps}/{@code vzMps} (÷ 100) — {@link Telemetry} has no named velocity fields.</li>
 *   <li>{@code GPS_RAW_INT} — {@code fix_type} → {@link FlightState#gpsFixType()} (already the
 *       0..8 ordinal {@link FlightState} expects); {@code satellites_visible} ({@code 255} =
 *       unknown) → {@link FlightState#satellites()}; {@code eph} (HDOP × 100, {@code 65535} =
 *       invalid) → {@link FlightState#hdop()} (÷ 100, or {@code null}).</li>
 *   <li>{@code RC_CHANNELS} / {@code RC_CHANNELS_RAW} — {@code rssi} (0..254, {@code 255} =
 *       invalid) → {@link FlightState#rssiPercent()} (rounded to a percent of 254, or {@code
 *       null}).</li>
 *   <li>{@code SYS_STATUS}/{@code BATTERY_STATUS} — {@code batteryRemaining} (%, {@code -1} =
 *       unknown) → {@link Telemetry#batteryPercent()}. Both messages report the same domain
 *       field; whichever arrives most recently wins, with no separate per-source history kept.
 *       {@code SYS_STATUS} additionally reports {@code voltage_battery} (mV, {@code 65535} =
 *       unknown) → {@code extra} key {@code batteryVoltage} (÷ 1000, omitted when unknown) — this
 *       is a plain {@code extra} reading, not a {@link FlightState} field.</li>
 *   <li>{@code VFR_HUD} — only {@code groundspeed} (already m/s, no conversion needed) → {@code
 *       extra} key {@code groundspeedMps}. Its own {@code alt}/{@code heading} are ignored in
 *       favor of {@code GLOBAL_POSITION_INT}'s more precise versions; {@code
 *       airspeed}/{@code throttle}/{@code climb} have no home in {@link Telemetry} and are
 *       dropped.</li>
 *   <li>{@code STATUSTEXT} — text matching {@code ^(PreArm|Arm): (.*)} (ArduPilot's arming-blocker
 *       broadcast, re-sent roughly every 30s while disarmed) captures the reason into {@link
 *       FlightState#armingBlockers()}: an insertion-ordered, deduplicated, capped-at-10 rolling
 *       set (oldest evicted first once full), cleared entirely the moment {@code HEARTBEAT}
 *       reports armed. Non-matching status text is recognized (still emits a sample) but changes
 *       nothing.</li>
 *   <li>{@code WIND} (ardupilotmega) — {@code speed}/{@code direction} (m/s, degrees) → {@code
 *       extra} keys {@code windSpeedMps}/{@code windDirectionDegrees}, no conversion needed
 *       (docs/FC-INTEGRATIONS-PLAN.md F-e).</li>
 *   <li>{@code VIBRATION} (common) — {@code vibrationX}/{@code vibrationY}/{@code vibrationZ}
 *       (m/s²) → {@code extra} keys {@code vibeXMs2}/{@code vibeYMs2}/{@code vibeZMs2}; the
 *       {@code clipping0}/{@code clipping1}/{@code clipping2} accelerometer-clipping counts are
 *       deliberately skipped, per the frozen F-e key table.</li>
 *   <li>{@code EKF_STATUS_REPORT} (ardupilotmega) — {@code velocityVariance}/{@code
 *       posHorizVariance}/{@code posVertVariance}/{@code compassVariance} → {@code extra} keys
 *       {@code ekfVelocityVariance}/{@code ekfPosHorizVariance}/{@code ekfPosVertVariance}/{@code
 *       ekfCompassVariance}, no conversion needed ({@code terrainAltVariance}/{@code
 *       airspeedVariance} have no key in the frozen table and are dropped).</li>
 *   <li>{@code MISSION_CURRENT} (common) — {@code seq} → {@code extra} key {@code missionSeq}.</li>
 *   <li>{@code RANGEFINDER} (ardupilotmega) — {@code distance} (meters) → {@code extra} key
 *       {@code rangefinderDistanceM}; {@code voltage} has no home in {@link Telemetry} and is
 *       dropped.</li>
 * </ul>
 * Every other MAVLink message type — there are hundreds in the common dialect alone — is
 * silently ignored: this decoder only maps what {@link Telemetry}/{@link FlightState} actually
 * have fields for.
 *
 * <h2>ardupilotmega dialect selection (docs/FC-INTEGRATIONS-PLAN.md F-e)</h2>
 * {@code WIND}/{@code EKF_STATUS_REPORT}/{@code RANGEFINDER} live in the {@code ardupilotmega}
 * dialect, not {@code common} — but no wiring change was needed anywhere in this module to decode
 * them. {@code io.dronefleet.mavlink.MavlinkConnection.Builder}'s constructor (used by every
 * {@code MavlinkConnection.create(...)} call in this module, since none of them call {@code
 * .dialect(...)}/{@code .defaultDialect(...)} to override it) registers {@code
 * MAV_AUTOPILOT_ARDUPILOTMEGA} (and {@code MAV_AUTOPILOT_PX4}) against {@code
 * ArdupilotmegaDialect} out of the box, alongside every other {@code MavAutopilot} value against
 * {@code CommonDialect}. {@code MavlinkConnection#next()} resolves the per-system dialect the
 * moment it decodes that system's first {@code HEARTBEAT} (caching it in a {@code
 * systemDialects} map keyed by system id, for the life of that one {@code MavlinkConnection}
 * instance) and uses it for every subsequent message from that system — so a real ArduPilot
 * vehicle's own unsolicited {@code HEARTBEAT} stream (autopilot {@code ARDUPILOTMEGA}, sent at
 * ~1&nbsp;Hz by every firmware, already relied on for {@link FlightState#firmware()}/{@link
 * FlightState#mode()}) is what silently unlocks these three messages on the one long-lived {@code
 * MavlinkConnection} each of {@link MavlinkSocketHub}'s read loop and {@link
 * MavlinkHeartbeatScanner}'s self-bind path keeps open for as long as they run. No dialect
 * override, no new dependency, no version bump — see this module's {@code MODULE.md} for the
 * confirmed mechanism and the one gotcha it implies for isolated single-message tests (a fresh
 * {@code MavlinkConnection} with no prior {@code HEARTBEAT} for that system id falls back to
 * {@code CommonDialect} and cannot resolve an ardupilotmega-only message id).
 *
 * <h2>{@link FlightState} materialization</h2>
 * Every emitted {@link Telemetry} carries the decoder's current merged {@link FlightState} — but
 * only once at least one {@link FlightState} field has actually become known ({@code HEARTBEAT},
 * {@code GPS_RAW_INT}, {@code RC_CHANNELS}/{@code RC_CHANNELS_RAW}, or a matching {@code
 * STATUSTEXT}); before that, {@link Telemetry#flightState()} stays {@code null} rather than
 * emitting an all-unknown record — same "honest null" discipline as every other optional {@link
 * Telemetry} field. {@code SYS_STATUS}'s {@code batteryVoltage} contribution lives in {@code
 * extra} only and never by itself materializes a {@link FlightState}.
 */
final class MavlinkTelemetryDecoder {

    private static final int UNKNOWN_HEADING_CENTIDEGREES = 65535;
    private static final int UNKNOWN_BATTERY_PERCENT = -1;
    private static final int UNKNOWN_VOLTAGE_BATTERY_MILLIVOLTS = 65535;
    private static final int UNKNOWN_SATELLITES = 255;
    private static final int UNKNOWN_EPH_CENTIUNITS = 65535;
    private static final int UNKNOWN_RSSI = 255;

    private static final int MAV_MODE_FLAG_SAFETY_ARMED = 128;
    private static final int MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1;

    private static final int MAX_ARMING_BLOCKERS = 10;
    private static final Pattern ARMING_BLOCKER_PATTERN = Pattern.compile("^(?:PreArm|Arm): (.*)$");

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
    private Double batteryVoltage;
    private Double windSpeedMps;
    private Double windDirectionDegrees;
    private Double vibeXMs2;
    private Double vibeYMs2;
    private Double vibeZMs2;
    private Double ekfVelocityVariance;
    private Double ekfPosHorizVariance;
    private Double ekfPosVertVariance;
    private Double ekfCompassVariance;
    private Double missionSeq;
    private Double rangefinderDistanceM;

    private String firmware;
    private String mode;
    private Boolean armed;
    private Boolean failsafe;
    private Integer gpsFixType;
    private Integer satellites;
    private Double hdop;
    private Integer rssiPercent;
    private final Set<String> armingBlockers = new LinkedHashSet<>();

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
            applyBatteryVoltage(sysStatus.voltageBattery());
        } else if (payload instanceof BatteryStatus batteryStatus) {
            applyBatteryPercent(batteryStatus.batteryRemaining());
        } else if (payload instanceof VfrHud vfrHud) {
            groundspeedMps = (double) vfrHud.groundspeed();
        } else if (payload instanceof Heartbeat heartbeat) {
            applyHeartbeat(heartbeat);
        } else if (payload instanceof GpsRawInt gpsRawInt) {
            applyGps(gpsRawInt);
        } else if (payload instanceof RcChannels rcChannels) {
            applyRssi(rcChannels.rssi());
        } else if (payload instanceof RcChannelsRaw rcChannelsRaw) {
            applyRssi(rcChannelsRaw.rssi());
        } else if (payload instanceof Statustext statustext) {
            applyStatustext(statustext.text());
        } else if (payload instanceof Wind wind) {
            windDirectionDegrees = (double) wind.direction();
            windSpeedMps = (double) wind.speed();
        } else if (payload instanceof Vibration vibration) {
            vibeXMs2 = (double) vibration.vibrationX();
            vibeYMs2 = (double) vibration.vibrationY();
            vibeZMs2 = (double) vibration.vibrationZ();
        } else if (payload instanceof EkfStatusReport ekfStatusReport) {
            ekfVelocityVariance = (double) ekfStatusReport.velocityVariance();
            ekfPosHorizVariance = (double) ekfStatusReport.posHorizVariance();
            ekfPosVertVariance = (double) ekfStatusReport.posVertVariance();
            ekfCompassVariance = (double) ekfStatusReport.compassVariance();
        } else if (payload instanceof MissionCurrent missionCurrent) {
            missionSeq = (double) missionCurrent.seq();
        } else if (payload instanceof Rangefinder rangefinder) {
            rangefinderDistanceM = (double) rangefinder.distance();
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

    private void applyBatteryVoltage(int voltageBatteryMillivolts) {
        batteryVoltage =
                voltageBatteryMillivolts == UNKNOWN_VOLTAGE_BATTERY_MILLIVOLTS ? null : voltageBatteryMillivolts / 1000.0;
    }

    private void applyHeartbeat(Heartbeat heartbeat) {
        int autopilot = heartbeat.autopilot().value();
        firmware = firmwareLabel(autopilot);

        int baseMode = heartbeat.baseMode().value();
        armed = (baseMode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;
        if (Boolean.TRUE.equals(armed)) {
            armingBlockers.clear(); // arming resolves/discards whatever was blocking it, see class javadoc
        }
        if ((baseMode & MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) != 0) {
            int mavType = heartbeat.type().value();
            mode = FlightModes.name(autopilot, mavType, heartbeat.customMode());
        } // else: custom_mode isn't valid on the wire -- leave the last known mode unchanged.

        failsafe = heartbeat.systemStatus().entry() == MavState.MAV_STATE_CRITICAL;
    }

    /** Package-private (not {@code private}): reused by {@link MavlinkSocketHub} to label unclaimed vehicles. */
    static String firmwareLabel(int autopilot) {
        if (autopilot == FlightModes.AUTOPILOT_ARDUPILOTMEGA) {
            return "ardupilot";
        }
        if (autopilot == FlightModes.AUTOPILOT_GENERIC) {
            return "generic";
        }
        if (autopilot == FlightModes.AUTOPILOT_PX4) {
            return "px4";
        }
        return null;
    }

    private void applyGps(GpsRawInt gpsRawInt) {
        gpsFixType = gpsRawInt.fixType().value();
        int satellitesVisible = gpsRawInt.satellitesVisible();
        satellites = satellitesVisible == UNKNOWN_SATELLITES ? null : satellitesVisible;
        int ephCentiunits = gpsRawInt.eph();
        hdop = ephCentiunits == UNKNOWN_EPH_CENTIUNITS ? null : ephCentiunits / 100.0;
    }

    private void applyRssi(int rssiRaw) {
        rssiPercent = rssiRaw == UNKNOWN_RSSI ? null : (int) Math.round(rssiRaw / 254.0 * 100.0);
    }

    private void applyStatustext(String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = ARMING_BLOCKER_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return;
        }
        if (armingBlockers.add(matcher.group(1))) {
            while (armingBlockers.size() > MAX_ARMING_BLOCKERS) {
                Iterator<String> oldest = armingBlockers.iterator();
                oldest.next();
                oldest.remove();
            }
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
        if (batteryVoltage != null) {
            extra.put("batteryVoltage", batteryVoltage);
        }
        if (windSpeedMps != null) {
            extra.put("windSpeedMps", windSpeedMps);
        }
        if (windDirectionDegrees != null) {
            extra.put("windDirectionDegrees", windDirectionDegrees);
        }
        if (vibeXMs2 != null) {
            extra.put("vibeXMs2", vibeXMs2);
        }
        if (vibeYMs2 != null) {
            extra.put("vibeYMs2", vibeYMs2);
        }
        if (vibeZMs2 != null) {
            extra.put("vibeZMs2", vibeZMs2);
        }
        if (ekfVelocityVariance != null) {
            extra.put("ekfVelocityVariance", ekfVelocityVariance);
        }
        if (ekfPosHorizVariance != null) {
            extra.put("ekfPosHorizVariance", ekfPosHorizVariance);
        }
        if (ekfPosVertVariance != null) {
            extra.put("ekfPosVertVariance", ekfPosVertVariance);
        }
        if (ekfCompassVariance != null) {
            extra.put("ekfCompassVariance", ekfCompassVariance);
        }
        if (missionSeq != null) {
            extra.put("missionSeq", missionSeq);
        }
        if (rangefinderDistanceM != null) {
            extra.put("rangefinderDistanceM", rangefinderDistanceM);
        }
        return new Telemetry(deviceId, Instant.now(), latitude, longitude, altitudeMeters,
                headingDegrees, batteryPercent, extra, currentFlightState());
    }

    /** {@code null} until at least one {@link FlightState} field has actually become known — see class javadoc. */
    private FlightState currentFlightState() {
        if (firmware == null && mode == null && armed == null && failsafe == null && gpsFixType == null
                && satellites == null && hdop == null && rssiPercent == null && armingBlockers.isEmpty()) {
            return null;
        }
        return new FlightState(firmware, mode, armed, failsafe, gpsFixType, satellites, hdop, rssiPercent,
                List.copyOf(armingBlockers));
    }
}
