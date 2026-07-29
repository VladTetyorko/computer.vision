package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.FeedTransmitterPort;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.common.GpsFixType;
import io.dronefleet.mavlink.common.GpsRawInt;
import io.dronefleet.mavlink.common.MavSysStatusSensor;
import io.dronefleet.mavlink.common.MavSysStatusSensorExtended;
import io.dronefleet.mavlink.common.SysStatus;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;
import io.dronefleet.mavlink.util.EnumValue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link FeedTransmitterPort} implementation that emits a synthetic MAVLink 2 telemetry stream —
 * {@code HEARTBEAT} + {@code SYS_STATUS} (battery) + {@code GPS_RAW_INT} (fixed 3D fix) at
 * 1&nbsp;Hz, and {@code GLOBAL_POSITION_INT} at a steady configurable rate — driven by a looping
 * flight route (docs/MVP2-PLAN.md X-a; docs/FC-INTEGRATIONS-PLAN.md F-a added the armed/mode/GPS/
 * failsafe reporting). This is the TX (transmit) half of the RX/TX doctrine ({@code
 * docs/CYCLES-PLAN.md} §0): zero-hardware rehearsal for {@link MavlinkTelemetrySource} (or any
 * real MAVLink ground station), and the same wire path {@code sim_vehicle.py} SITL would
 * otherwise be needed for.
 *
 * <h2>{@link FeedSpec#source()} is a destination, not a file</h2>
 * Unlike {@code adapter-rtsp}/{@code adapter-mjpeg}'s transmitters (which read a local video
 * <i>file</i> named by {@code source} and pick their own target), there is no media file here —
 * the telemetry is synthesized from a flight route given via {@link FeedSpec#options()}. What
 * varies per feed instead is <i>where to send it</i>, so {@code source} is repurposed as the
 * {@code udp://host:port} <b>destination</b> this transmitter actively pushes datagrams to (the
 * same address a {@link MavlinkTelemetrySource} would bind and listen on to receive it) — the one
 * deliberate semantic deviation from this port's other implementations, documented here and in
 * this module's {@code MODULE.md}.
 *
 * <h2>Flight state: armed, mode, failsafe (docs/FC-INTEGRATIONS-PLAN.md F-a)</h2>
 * Every {@code HEARTBEAT} reports {@code autopilot} ARDUPILOTMEGA, {@code type} QUADROTOR, and
 * {@code base_mode} armed + custom-mode-enabled, so the RX side always resolves a firmware/mode
 * pair. While the simulated battery (see {@code batteryDrainPerSecond}) stays at/above the new
 * {@code failsafeBatteryPercent} option, {@code custom_mode} is {@value #CUSTOM_MODE_LOITER}
 * (ArduPilot copter "Loiter") and {@code system_status} is {@code ACTIVE}; once it drains below
 * that threshold, {@code custom_mode} switches to {@value #CUSTOM_MODE_RTL} ("RTL") and {@code
 * system_status} switches to {@code CRITICAL} — a scripted, deterministic failsafe trigger for
 * exercising the RX side's failsafe reporting without hardware.
 *
 * <h2>Recognized {@link FeedSpec#options()}</h2>
 * <ul>
 *   <li>{@code route} — <b>required</b>, {@code lat,lon[,altM];lat,lon[,altM];...}, at least 2
 *       points; see {@link MavlinkRoute}. Unlike {@code adapter-simulation}'s lenient
 *       option-parsing convention, a missing/malformed route fails {@link #start} fast
 *       ({@link IllegalArgumentException}) — there is no default circular-track fallback
 *       reimplemented here (see {@link MavlinkRoute}'s javadoc for why).</li>
 *   <li>{@code speedMps} — cruise speed along the route, default {@value #DEFAULT_SPEED_MPS};
 *       a non-positive or unparseable value falls back to the default.</li>
 *   <li>{@code batteryDrainPerSecond} — percent/second drain from a full charge, default
 *       {@value #DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND}.</li>
 *   <li>{@code positionRateHz} — {@code GLOBAL_POSITION_INT} send rate, default {@value
 *       #DEFAULT_POSITION_RATE_HZ}; a non-positive or unparseable value falls back to the
 *       default.</li>
 *   <li>{@code failsafeBatteryPercent} (docs/FC-INTEGRATIONS-PLAN.md F-a) — battery percent below
 *       which {@code HEARTBEAT} switches to the failsafe {@code custom_mode}/{@code
 *       system_status} described above, default {@value #DEFAULT_FAILSAFE_BATTERY_PERCENT};
 *       missing/unparseable falls back to the default (lenient, like {@code
 *       batteryDrainPerSecond} — unlike {@code speedMps}/{@code positionRateHz}, a non-positive
 *       value is accepted as-is, since {@code 0} is a meaningful "never" setting).</li>
 *   <li>{@code sysid} (docs/DRONE-INFRA-PLAN.md I-a) — the MAVLink system id every message from
 *       this feed is sent as, default {@value #DEFAULT_MAV_SYSTEM_ID} (every real firmware's own
 *       out-of-the-box default). Lenient: missing/blank/unparseable/out of the valid 1-255 range
 *       falls back to the default — exists so a test (or a real multi-vehicle rehearsal) can run
 *       two feeds with distinct system ids at one destination, matching how a real fleet gateway
 *       (see {@link MavlinkTelemetrySource}/{@link MavlinkSocketHub}) demultiplexes them.</li>
 * </ul>
 *
 * <p>Each {@link #start(FeedId, FeedSpec)} call spins up one dedicated platform thread ({@code
 * mavlink-feed-<id>}) that owns its own ephemeral {@link DatagramSocket} and pushes {@code
 * HEARTBEAT}/{@code SYS_STATUS}/{@code GPS_RAW_INT} once a second and {@code
 * GLOBAL_POSITION_INT} at {@code positionRateHz}, as system id {@code sysid} (default {@value
 * #DEFAULT_MAV_SYSTEM_ID}) / component id {@value #MAV_COMPONENT_ID}. An unrecoverable transmit
 * failure simply stops the feed — best-effort, no error channel back to the caller, per {@link
 * FeedTransmitterPort}'s contract.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration; no constructor arguments (unlike {@code RtspFeedTransmitter}), since the
 * destination is per-feed, not a shared base.
 */
public final class MavlinkFeedTransmitter implements FeedTransmitterPort {

    private static final System.Logger LOG = System.getLogger(MavlinkFeedTransmitter.class.getName());

    private static final String PROTOCOL_MAVLINK = "mavlink";
    private static final String SCHEME_UDP = "udp";

    static final String OPTION_ROUTE = "route";
    static final String OPTION_SPEED_MPS = "speedMps";
    static final String OPTION_BATTERY_DRAIN_PERCENT_PER_SECOND = "batteryDrainPerSecond";
    static final String OPTION_POSITION_RATE_HZ = "positionRateHz";
    static final String OPTION_FAILSAFE_BATTERY_PERCENT = "failsafeBatteryPercent";
    static final String OPTION_SYSID = "sysid";

    static final double DEFAULT_SPEED_MPS = 12.0;
    static final double DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND = 0.05;
    static final double DEFAULT_POSITION_RATE_HZ = 5.0;
    static final double DEFAULT_FAILSAFE_BATTERY_PERCENT = 15.0;

    /** ArduPilot copter custom_mode for "Loiter" — the nominal (non-failsafe) heartbeat mode. */
    static final long CUSTOM_MODE_LOITER = 5L;
    /** ArduPilot copter custom_mode for "RTL" — the mode reported once failsafe triggers. */
    static final long CUSTOM_MODE_RTL = 6L;

    private static final int GPS_SATELLITES_VISIBLE = 12;
    private static final int GPS_EPH_CENTIUNITS = 90; // eph x100 -> hdop 0.9

    private static final long HEARTBEAT_PERIOD_MILLIS = 1000L;
    private static final long TICK_MILLIS = 50L;
    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 5_000L;

    static final int DEFAULT_MAV_SYSTEM_ID = 1;
    static final int MAV_COMPONENT_ID = 1;
    private static final int MIN_SYSID = 1;
    private static final int MAX_SYSID = 255;

    private final Map<FeedId, FeedRuntime> feeds = new ConcurrentHashMap<>();

    @Override
    public boolean supports(FeedSpec spec) {
        if (spec == null || !PROTOCOL_MAVLINK.equals(spec.protocol())) {
            return false;
        }
        URI source = spec.source();
        return source != null && SCHEME_UDP.equalsIgnoreCase(source.getScheme())
                && source.getHost() != null && !source.getHost().isBlank()
                && source.getPort() > 0;
    }

    @Override
    public StreamDescriptor start(FeedId id, FeedSpec spec) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (!supports(spec)) {
            throw new IllegalArgumentException("MavlinkFeedTransmitter does not support spec: " + spec);
        }

        MavlinkRoute route = MavlinkRoute.parse(spec.options().get(OPTION_ROUTE));
        if (route == null) {
            throw new IllegalArgumentException("MavlinkFeedTransmitter requires a 'route' option "
                    + "(lat,lon[,altM];lat,lon[,altM];... with at least 2 points), got: "
                    + spec.options().get(OPTION_ROUTE));
        }
        double speedMps = positiveDoubleOption(spec.options(), OPTION_SPEED_MPS, DEFAULT_SPEED_MPS);
        double batteryDrainPercentPerSecond = doubleOption(
                spec.options(), OPTION_BATTERY_DRAIN_PERCENT_PER_SECOND, DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND);
        double positionRateHz =
                positiveDoubleOption(spec.options(), OPTION_POSITION_RATE_HZ, DEFAULT_POSITION_RATE_HZ);
        double failsafeBatteryPercent = doubleOption(
                spec.options(), OPTION_FAILSAFE_BATTERY_PERCENT, DEFAULT_FAILSAFE_BATTERY_PERCENT);
        int sysid = sysidOption(spec.options());

        FeedRuntime runtime = new FeedRuntime(id, spec.source().getHost(), spec.source().getPort(),
                route, speedMps, batteryDrainPercentPerSecond, positionRateHz, failsafeBatteryPercent, sysid);
        FeedRuntime previous = feeds.put(id, runtime);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live feeds
        }
        runtime.start();

        // The destination we push to doubles as the descriptor a MavlinkTelemetrySource would
        // bind/listen on to receive it -- see class javadoc's "source is a destination" section.
        return new StreamDescriptor(PROTOCOL_MAVLINK, spec.source(), Map.of());
    }

    @Override
    public void stop(FeedId id) {
        FeedRuntime runtime = feeds.remove(id);
        if (runtime != null) {
            runtime.close();
        }
    }

    private static double doubleOption(Map<String, String> options, String key, double defaultValue) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Like {@link #doubleOption}, but a parsed value that isn't positive also falls back to the default. */
    private static double positiveDoubleOption(Map<String, String> options, String key, double defaultValue) {
        double parsed = doubleOption(options, key, defaultValue);
        return parsed > 0 ? parsed : defaultValue;
    }

    /** Lenient like every other option here: missing/blank/unparseable/out of 1-255 -> {@value #DEFAULT_MAV_SYSTEM_ID}. */
    private static int sysidOption(Map<String, String> options) {
        String raw = options.get(OPTION_SYSID);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAV_SYSTEM_ID;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= MIN_SYSID && value <= MAX_SYSID ? value : DEFAULT_MAV_SYSTEM_ID;
        } catch (NumberFormatException e) {
            return DEFAULT_MAV_SYSTEM_ID;
        }
    }

    /**
     * Per-feed runtime: a dedicated socket + transmit thread pacing
     * HEARTBEAT/SYS_STATUS/GPS_RAW_INT (1&nbsp;Hz) and GLOBAL_POSITION_INT (positionRateHz).
     */
    private static final class FeedRuntime {
        private final FeedId feedId;
        private final String targetHost;
        private final int targetPort;
        private final MavlinkRoute route;
        private final double speedMps;
        private final double batteryDrainPercentPerSecond;
        private final long positionPeriodMillis;
        private final double failsafeBatteryPercent;
        private final int sysid;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread transmitThread;
        private volatile DatagramSocket socket;

        FeedRuntime(FeedId feedId, String targetHost, int targetPort, MavlinkRoute route, double speedMps,
                    double batteryDrainPercentPerSecond, double positionRateHz, double failsafeBatteryPercent,
                    int sysid) {
            this.feedId = feedId;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.route = route;
            this.speedMps = speedMps;
            this.batteryDrainPercentPerSecond = batteryDrainPercentPerSecond;
            this.positionPeriodMillis = Math.max(1L, Math.round(1000.0 / positionRateHz));
            this.failsafeBatteryPercent = failsafeBatteryPercent;
            this.sysid = sysid;
        }

        void start() {
            transmitThread = new Thread(this::runTransmitLoop, "mavlink-feed-" + feedId.value());
            transmitThread.setDaemon(true);
            transmitThread.start();
        }

        private void runTransmitLoop() {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                socket = sock;
                InetAddress address = InetAddress.getByName(targetHost);
                MavlinkConnection connection = MavlinkConnection.create(
                        InputStream.nullInputStream(), new MavlinkUdpOutputStream(sock, address, targetPort));

                long startNanos = System.nanoTime();
                long heartbeatPeriodNanos = TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_PERIOD_MILLIS);
                long positionPeriodNanos = TimeUnit.MILLISECONDS.toNanos(positionPeriodMillis);
                long nextHeartbeatNanos = startNanos;
                long nextPositionNanos = startNanos;
                long lastPositionTickNanos = startNanos;
                double distanceMeters = 0.0;

                LOG.log(System.Logger.Level.INFO,
                        () -> "Transmitting MAVLink feed " + feedId.value() + " to " + targetHost + ":" + targetPort);

                while (!stopRequested.get()) {
                    long now = System.nanoTime();
                    if (now >= nextHeartbeatNanos) {
                        double elapsedSeconds = (now - startNanos) / 1e9;
                        int batteryPercent = (int) Math.round(
                                Math.max(0.0, 100.0 - batteryDrainPercentPerSecond * elapsedSeconds));
                        boolean failsafeTriggered = batteryPercent < failsafeBatteryPercent;
                        sendHeartbeat(connection, failsafeTriggered);
                        sendSysStatus(connection, batteryPercent);
                        sendGpsRawInt(connection, route.positionAt(distanceMeters));
                        nextHeartbeatNanos += heartbeatPeriodNanos;
                    }
                    if (now >= nextPositionNanos) {
                        double tickSeconds = (now - lastPositionTickNanos) / 1e9;
                        lastPositionTickNanos = now;
                        distanceMeters += speedMps * tickSeconds;
                        MavlinkRoute.Position position = route.positionAt(distanceMeters);
                        long elapsedMillis = (now - startNanos) / 1_000_000L;
                        sendGlobalPositionInt(connection, position, elapsedMillis);
                        nextPositionNanos += positionPeriodNanos;
                    }
                    sleepMillis(TICK_MILLIS);
                }
            } catch (Exception e) {
                if (!stopRequested.get()) {
                    // Unrecoverable transmit failure: best-effort, no error channel back to the
                    // caller (per FeedTransmitterPort's contract) -- just stop the feed.
                    LOG.log(System.Logger.Level.WARNING, "Feed " + feedId.value() + " stopped due to an error", e);
                }
            } finally {
                closeQuietly(sock);
            }
        }

        /**
         * @param failsafeTriggered {@code true} once the drained battery has fallen below {@code
         *                          failsafeBatteryPercent} — switches {@code custom_mode} to RTL
         *                          and {@code system_status} to CRITICAL (docs/FC-INTEGRATIONS-PLAN.md F-a)
         */
        private void sendHeartbeat(MavlinkConnection connection, boolean failsafeTriggered) throws IOException {
            Heartbeat heartbeat = Heartbeat.builder()
                    .type(MavType.MAV_TYPE_QUADROTOR)
                    .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                    .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                    .customMode(failsafeTriggered ? CUSTOM_MODE_RTL : CUSTOM_MODE_LOITER)
                    .systemStatus(failsafeTriggered ? MavState.MAV_STATE_CRITICAL : MavState.MAV_STATE_ACTIVE)
                    .mavlinkVersion(3)
                    .build();
            connection.send2(sysid, MAV_COMPONENT_ID, heartbeat);
        }

        /** Fixed 3D fix, {@value #GPS_SATELLITES_VISIBLE} satellites, {@value #GPS_EPH_CENTIUNITS} eph (hdop 0.9). */
        private void sendGpsRawInt(MavlinkConnection connection, MavlinkRoute.Position position) throws IOException {
            int altMillimeters =
                    position.altitudeMeters() == null ? 0 : (int) Math.round(position.altitudeMeters() * 1000.0);
            GpsRawInt gpsRawInt = GpsRawInt.builder()
                    .timeUsec(BigInteger.ZERO)
                    .fixType(GpsFixType.GPS_FIX_TYPE_3D_FIX)
                    .lat((int) Math.round(position.latitude() * 1e7))
                    .lon((int) Math.round(position.longitude() * 1e7))
                    .alt(altMillimeters)
                    .eph(GPS_EPH_CENTIUNITS)
                    .epv(0)
                    .vel(0)
                    .cog(0)
                    .satellitesVisible(GPS_SATELLITES_VISIBLE)
                    .build();
            connection.send2(sysid, MAV_COMPONENT_ID, gpsRawInt);
        }

        private void sendSysStatus(MavlinkConnection connection, int batteryPercent) throws IOException {
            SysStatus sysStatus = SysStatus.builder()
                    .onboardControlSensorsPresent(EnumValue.<MavSysStatusSensor>create(0))
                    .onboardControlSensorsEnabled(EnumValue.<MavSysStatusSensor>create(0))
                    .onboardControlSensorsHealth(EnumValue.<MavSysStatusSensor>create(0))
                    .load(0)
                    .voltageBattery(0)
                    .currentBattery(-1)
                    .batteryRemaining(batteryPercent)
                    .dropRateComm(0)
                    .errorsComm(0)
                    .errorsCount1(0)
                    .errorsCount2(0)
                    .errorsCount3(0)
                    .errorsCount4(0)
                    .onboardControlSensorsPresentExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                    .onboardControlSensorsEnabledExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                    .onboardControlSensorsHealthExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                    .build();
            connection.send2(sysid, MAV_COMPONENT_ID, sysStatus);
        }

        private void sendGlobalPositionInt(MavlinkConnection connection, MavlinkRoute.Position position,
                                            long elapsedMillis) throws IOException {
            double headingRadians = Math.toRadians(position.headingDegrees());
            int vxCmS = (int) Math.round(speedMps * Math.cos(headingRadians) * 100.0);
            int vyCmS = (int) Math.round(speedMps * Math.sin(headingRadians) * 100.0);
            int altMillimeters = position.altitudeMeters() == null
                    ? 0 : (int) Math.round(position.altitudeMeters() * 1000.0);
            int headingCentidegrees =
                    ((int) Math.round(position.headingDegrees() * 100.0) % 36000 + 36000) % 36000;

            GlobalPositionInt message = GlobalPositionInt.builder()
                    .timeBootMs(elapsedMillis)
                    .lat((int) Math.round(position.latitude() * 1e7))
                    .lon((int) Math.round(position.longitude() * 1e7))
                    .alt(altMillimeters)
                    .relativeAlt(altMillimeters)
                    .vx(vxCmS)
                    .vy(vyCmS)
                    .vz(0)
                    .hdg(headingCentidegrees)
                    .build();
            connection.send2(sysid, MAV_COMPONENT_ID, message);
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                closeQuietly(socket); // unblocks nothing here (TX never blocks on receive), released promptly regardless
                Thread thread = transmitThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt();
                    try {
                        thread.join(CLOSE_JOIN_TIMEOUT_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private static void sleepMillis(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void closeQuietly(DatagramSocket socket) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
