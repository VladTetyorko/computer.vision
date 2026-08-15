package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges a stream of {@link MavlinkMessage}s from one MAVLink system into {@link Telemetry}
 * samples, applying the unit conversions documented per field below. Stateful — one instance per
 * {@link com.drones.vision.flight.domain.port.TelemetrySourcePort#open} call, never shared across
 * two runtimes, since it accumulates the latest known value of each field across messages.
 *
 * <h2>Three state groups (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2)</h2>
 * The merged fields split across three package-private mutable holders, by which domain concept
 * they feed: {@link PositionAndPowerState} (position/velocity/battery — {@link
 * Telemetry}'s own named fields plus their {@code extra}-only siblings), {@link FlightStatusState}
 * (everything that materializes {@link com.drones.vision.kernel.FlightState}), and {@link
 * ArdupilotExtras} (every other {@code extra}-only key, mostly ardupilotmega-dialect messages).
 * This class itself owns only the per-system lock (below) and message-type dispatch to whichever
 * holder owns that message — see each holder's own javadoc for its exact field list.
 *
 * <h2>System-id stickiness (now enforced one level up, by {@link MavlinkGateway})</h2>
 * A single UDP port can carry more than one MAVLink system's traffic (e.g. a telemetry radio
 * relaying two vehicles, or stray traffic sharing the port). This decoder still locks onto the
 * <b>first</b> {@link MavlinkMessage#getOriginSystemId()} it observes — of any message type,
 * including {@code HEARTBEAT} — and silently ignores every subsequent message from a different
 * system for the remainder of this decoder's lifetime; that invariant is exercised directly by
 * this class's own unit tests and remains true for any caller that feeds one decoder instance
 * mixed-system traffic. As of docs/plans/active/DRONE-INFRA-PLAN.md I-a (and unchanged in shape by
 * docs/plans/active/MAVLINK-CORE-PLAN.md W4), {@link MavlinkTelemetrySource} is no longer such a
 * caller: {@link MavlinkGateway} demultiplexes by sysid <b>before</b> a message ever reaches a
 * decoder (via {@link VehicleClaimPolicy}), and hands each claiming device a <b>fresh</b> decoder
 * on every claim or re-election, so in practice a decoder here only ever sees one system's
 * messages for its whole lifetime, and this class's own lock never actually rejects anything at
 * the system level — see {@link MavlinkGateway}'s javadoc for the claim/re-election rules that
 * replaced the old "first sysid wins, no re-adoption" single-device gotcha.
 *
 * <h2>Message → field mapping</h2>
 * <ul>
 *   <li>{@code HEARTBEAT} — {@code autopilot} → {@link com.drones.vision.kernel.FlightState#firmware()} ({@code
 *       "ardupilot"}/{@code "generic"}/{@code "px4"}/{@code null}, see {@link FlightModes});
 *       {@code base_mode} bit {@code 128} (safety-armed) → {@code armed}; {@code
 *       base_mode} bit {@code 1} (custom-mode-enabled) gates whether {@code custom_mode} is
 *       resolved through {@link FlightModes#name(int, int, long)} into {@code
 *       mode} — left unchanged otherwise; {@code system_status ==
 *       MAV_STATE_CRITICAL} → {@code failsafe}. Arming becoming {@code true} clears
 *       any accumulated {@code armingBlockers} (see {@code STATUSTEXT} below).</li>
 *   <li>{@code GLOBAL_POSITION_INT} — {@code lat}/{@code lon} (degrees × 1e7) → {@link
 *       Telemetry#latitude()}/{@link Telemetry#longitude()} (÷ 1e7); {@code alt} (mm, AMSL) →
 *       {@link Telemetry#altitudeMeters()} (÷ 1000 — the AMSL reading, not {@code relativeAlt}, to
 *       match {@link Telemetry#altitudeMeters()}'s "absolute" semantics used elsewhere in this
 *       codebase, e.g. {@code GeoPosition}); {@code hdg} (centidegrees, {@code 65535} = unknown) →
 *       {@link Telemetry#headingDegrees()} (÷ 100, or {@code null} when unknown); {@code
 *       vx}/{@code vy}/{@code vz} (cm/s, NED) → {@code extra} keys {@code vxMps}/{@code
 *       vyMps}/{@code vzMps} (÷ 100) — {@link Telemetry} has no named velocity fields.</li>
 *   <li>{@code GPS_RAW_INT} — {@code fix_type} → {@code gpsFixType} (already the
 *       0..8 ordinal {@link com.drones.vision.kernel.FlightState} expects); {@code satellites_visible} ({@code 255} =
 *       unknown) → {@code satellites}; {@code eph} (HDOP × 100, {@code 65535} =
 *       invalid) → {@code hdop} (÷ 100, or {@code null}).</li>
 *   <li>{@code RC_CHANNELS} / {@code RC_CHANNELS_RAW} — {@code rssi} (0..254, {@code 255} =
 *       invalid) → {@code rssiPercent} (rounded to a percent of 254, or {@code
 *       null}).</li>
 *   <li>{@code SYS_STATUS}/{@code BATTERY_STATUS} — {@code batteryRemaining} (%, {@code -1} =
 *       unknown) → {@link Telemetry#batteryPercent()}. Both messages report the same domain
 *       field; whichever arrives most recently wins, with no separate per-source history kept.
 *       {@code SYS_STATUS} additionally reports {@code voltage_battery} (mV, {@code 65535} =
 *       unknown) → {@code extra} key {@code batteryVoltage} (÷ 1000, omitted when unknown) — this
 *       is a plain {@code extra} reading, not a {@code FlightState} field.</li>
 *   <li>{@code VFR_HUD} — only {@code groundspeed} (already m/s, no conversion needed) → {@code
 *       extra} key {@code groundspeedMps}. Its own {@code alt}/{@code heading} are ignored in
 *       favor of {@code GLOBAL_POSITION_INT}'s more precise versions; {@code
 *       airspeed}/{@code throttle}/{@code climb} have no home in {@link Telemetry} and are
 *       dropped.</li>
 *   <li>{@code STATUSTEXT} — text matching {@code ^(PreArm|Arm): (.*)} (ArduPilot's arming-blocker
 *       broadcast, re-sent roughly every 30s while disarmed) captures the reason into {@code
 *       armingBlockers}: an insertion-ordered, deduplicated, capped-at-10 rolling
 *       set (oldest evicted first once full), cleared entirely the moment {@code HEARTBEAT}
 *       reports armed. Non-matching status text is recognized (still emits a sample) but changes
 *       nothing.</li>
 *   <li>{@code WIND} (ardupilotmega) — {@code speed}/{@code direction} (m/s, degrees) → {@code
 *       extra} keys {@code windSpeedMps}/{@code windDirectionDegrees}, no conversion needed
 *       (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e).</li>
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
 * silently ignored: this decoder only maps what {@link Telemetry}/{@code FlightState} actually
 * have fields for.
 *
 * <h2>ardupilotmega dialect selection (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e; re-plumbed by
 * docs/plans/active/MAVLINK-CORE-PLAN.md W4)</h2>
 * {@code WIND}/{@code EKF_STATUS_REPORT}/{@code RANGEFINDER} live in the {@code ardupilotmega}
 * dialect, not {@code common}. Before W4, this module read every message off one long-lived {@code
 * io.dronefleet.mavlink.MavlinkConnection} per socket ({@code MavlinkSocketHub}'s read loop, or
 * {@code MavlinkHeartbeatScanner}'s self-bind path) — that single connection's own {@code
 * systemDialects} cache, keyed purely by <em>sysid</em>, meant one ArduPilot vehicle's first
 * {@code HEARTBEAT} unlocked these three messages for the rest of that connection's life,
 * <em>regardless of which physical UDP source sent them</em>.
 *
 * <p>As of W4, {@link MavlinkGateway}/{@code MavlinkHeartbeatScanner} read through {@code
 * mavlink-core}'s {@code FrameReader}, which keeps one resync buffer <em>per source address</em>
 * and primes each buffer's own fresh {@code MavlinkConnection} with whatever dialect that
 * <em>same buffer</em> last resolved (see that module's {@code ResyncBuffer} Gotchas) — a
 * per-buffer approximation of the old per-sysid cache, not a replacement for it. In every
 * production and test scenario where one physical sender speaks for one sysid (every real
 * ArduPilot vehicle, {@code MavlinkFeedTransmitter}, every fake vehicle in this module's own test
 * suite), this is unobservable: the buffer that heard the {@code HEARTBEAT} is the same buffer
 * that later sends {@code WIND}/etc. <b>It is observable, and a real regression, the moment a
 * second physical source claims the same sysid without ever sending its own {@code HEARTBEAT}</b>
 * (e.g. a raw test datagram sent from a separate socket) — that source's own resync buffer has no
 * primed dialect and falls back to {@code CommonDialect}, silently unable to decode an
 * ardupilotmega-only message id. See this module's {@code MODULE.md} Gotchas for the concrete
 * test this broke and why it is a {@code libs/mavlink-core} L2 limitation, not something this
 * adapter can work around.
 *
 * <h2>{@code FlightState} materialization</h2>
 * Every emitted {@link Telemetry} carries the decoder's current merged {@code FlightState} — but
 * only once at least one {@code FlightState} field has actually become known ({@code HEARTBEAT},
 * {@code GPS_RAW_INT}, {@code RC_CHANNELS}/{@code RC_CHANNELS_RAW}, or a matching {@code
 * STATUSTEXT}); before that, {@link Telemetry#flightState()} stays {@code null} rather than
 * emitting an all-unknown record — same "honest null" discipline as every other optional {@link
 * Telemetry} field. {@code SYS_STATUS}'s {@code batteryVoltage} contribution lives in {@code
 * extra} only and never by itself materializes a {@code FlightState}.
 */
final class MavlinkTelemetryDecoder {

    private final DeviceId deviceId;

    private Integer systemId;

    private final PositionAndPowerState positionAndPower = new PositionAndPowerState();
    private final FlightStatusState flightStatus = new FlightStatusState();
    private final ArdupilotExtras extras = new ArdupilotExtras();

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
        return accept(message.getOriginSystemId(), message.getPayload());
    }

    /**
     * docs/plans/active/MAVLINK-CORE-PLAN.md W4: {@link MavlinkGateway} delivers frames as {@code
     * (sysid, payload)} pairs (a {@code com.drones.mavlink.codec.MavFrame} already carries the
     * origin sysid in its header, separately from the raw library payload object) rather than a
     * dronefleet {@link MavlinkMessage} wrapper -- this overload is the actual decode logic; {@link
     * #accept(MavlinkMessage)} is now a one-line adapter onto it so every pre-existing golden-bytes
     * test (which still builds real {@link MavlinkMessage}s via {@code MavlinkConnection}) keeps
     * working unchanged. Same system-id lock-in / message-mapping / return-null rules as before.
     */
    Telemetry accept(int originSystemId, Object payload) {
        if (systemId == null) {
            systemId = originSystemId;
        } else if (systemId != originSystemId) {
            return null; // a second system sharing this port: ignored, see class javadoc
        }

        if (payload instanceof GlobalPositionInt position) {
            positionAndPower.applyPosition(position);
        } else if (payload instanceof SysStatus sysStatus) {
            positionAndPower.applyBatteryPercent(sysStatus.batteryRemaining());
            positionAndPower.applyBatteryVoltage(sysStatus.voltageBattery());
        } else if (payload instanceof BatteryStatus batteryStatus) {
            positionAndPower.applyBatteryPercent(batteryStatus.batteryRemaining());
        } else if (payload instanceof VfrHud vfrHud) {
            extras.applyVfrHud(vfrHud);
        } else if (payload instanceof Heartbeat heartbeat) {
            flightStatus.applyHeartbeat(heartbeat);
        } else if (payload instanceof GpsRawInt gpsRawInt) {
            flightStatus.applyGps(gpsRawInt);
        } else if (payload instanceof RcChannels rcChannels) {
            flightStatus.applyRssi(rcChannels.rssi());
        } else if (payload instanceof RcChannelsRaw rcChannelsRaw) {
            flightStatus.applyRssi(rcChannelsRaw.rssi());
        } else if (payload instanceof Statustext statustext) {
            flightStatus.applyStatustext(statustext.text());
        } else if (payload instanceof Wind wind) {
            extras.applyWind(wind);
        } else if (payload instanceof Vibration vibration) {
            extras.applyVibration(vibration);
        } else if (payload instanceof EkfStatusReport ekfStatusReport) {
            extras.applyEkfStatusReport(ekfStatusReport);
        } else if (payload instanceof MissionCurrent missionCurrent) {
            extras.applyMissionCurrent(missionCurrent);
        } else if (payload instanceof Rangefinder rangefinder) {
            extras.applyRangefinder(rangefinder);
        } else {
            return null;
        }
        return toTelemetry();
    }

    /** Package-private (not {@code private}): reused by {@link MavlinkHeartbeatScanner}/{@link VehicleClaimPolicy} to label unclaimed/claimed vehicles. */
    static String firmwareLabel(int autopilot) {
        return FlightStatusState.firmwareLabel(autopilot);
    }

    private Telemetry toTelemetry() {
        Map<String, Double> extra = new LinkedHashMap<>();
        if (positionAndPower.vxMps != null) {
            extra.put("vxMps", positionAndPower.vxMps);
        }
        if (positionAndPower.vyMps != null) {
            extra.put("vyMps", positionAndPower.vyMps);
        }
        if (positionAndPower.vzMps != null) {
            extra.put("vzMps", positionAndPower.vzMps);
        }
        if (extras.groundspeedMps != null) {
            extra.put("groundspeedMps", extras.groundspeedMps);
        }
        if (positionAndPower.batteryVoltage != null) {
            extra.put("batteryVoltage", positionAndPower.batteryVoltage);
        }
        if (extras.windSpeedMps != null) {
            extra.put("windSpeedMps", extras.windSpeedMps);
        }
        if (extras.windDirectionDegrees != null) {
            extra.put("windDirectionDegrees", extras.windDirectionDegrees);
        }
        if (extras.vibeXMs2 != null) {
            extra.put("vibeXMs2", extras.vibeXMs2);
        }
        if (extras.vibeYMs2 != null) {
            extra.put("vibeYMs2", extras.vibeYMs2);
        }
        if (extras.vibeZMs2 != null) {
            extra.put("vibeZMs2", extras.vibeZMs2);
        }
        if (extras.ekfVelocityVariance != null) {
            extra.put("ekfVelocityVariance", extras.ekfVelocityVariance);
        }
        if (extras.ekfPosHorizVariance != null) {
            extra.put("ekfPosHorizVariance", extras.ekfPosHorizVariance);
        }
        if (extras.ekfPosVertVariance != null) {
            extra.put("ekfPosVertVariance", extras.ekfPosVertVariance);
        }
        if (extras.ekfCompassVariance != null) {
            extra.put("ekfCompassVariance", extras.ekfCompassVariance);
        }
        if (extras.missionSeq != null) {
            extra.put("missionSeq", extras.missionSeq);
        }
        if (extras.rangefinderDistanceM != null) {
            extra.put("rangefinderDistanceM", extras.rangefinderDistanceM);
        }
        return new Telemetry(deviceId, Instant.now(), positionAndPower.latitude, positionAndPower.longitude,
                positionAndPower.altitudeMeters, positionAndPower.headingDegrees, positionAndPower.batteryPercent,
                extra, flightStatus.toFlightStateOrNull());
    }
}
