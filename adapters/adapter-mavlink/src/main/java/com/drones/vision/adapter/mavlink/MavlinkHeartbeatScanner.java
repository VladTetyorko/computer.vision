package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.DiscoveredDevice;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.DeviceDiscoveryPort;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.minimal.Heartbeat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link DeviceDiscoveryPort} implementation for plug-and-fly MAVLink heartbeat discovery
 * (docs/plans/active/DRONE-INFRA-PLAN.md I-b): every distinct MAVLink system id heard on the well-known GCS
 * port becomes a {@link DiscoveredDevice} — no manual sysid typing, no manual {@code udp://} URI
 * entry, matching the "operator will not hand-edit sysids" field reality I-b was written for.
 *
 * <h2>Why this lives in {@code adapter-mavlink}, not {@code adapter-discovery}</h2>
 * Adapters never depend on each other (ArchUnit-enforced) — a scanner in {@code adapter-discovery}
 * could not see {@link MavlinkSocketHub} at all, and would have no choice but to bind its own
 * socket on every scan. That fails outright the moment a real MAVLink telemetry {@code Device} is
 * already open on the same port (the common case once I-a's gateway is running one aircraft), and
 * even when nothing is open yet, a separate socket can never see what the gateway itself has
 * already claimed. Living in this module lets the scanner reach {@link MavlinkTelemetrySource}
 * directly and share its already-bound socket instead.
 *
 * <h2>Two paths, chosen per scan</h2>
 * <ul>
 *   <li><b>Hub already active</b> ({@link MavlinkTelemetrySource#hasActiveHub}) — some device has
 *       the port open right now. This scanner never binds anything; it polls {@link
 *       MavlinkTelemetrySource#claimedVehicles}/{@link MavlinkTelemetrySource#unclaimedVehicles} a
 *       few times across the scan timeout (no busy-spin — each poll is followed by a bounded
 *       sleep) and reports both: a <b>claimed</b> vehicle is labeled as already registered (see
 *       {@code details["claimed"]}/{@code details["claimedBy"]} below) so the onboarding wizard
 *       never invites creating a duplicate asset for an aircraft that already has one; an
 *       <b>unclaimed</b> one is reported plain, ready to register.</li>
 *   <li><b>No active hub</b> — nothing has this port open. This scanner binds it itself for the
 *       scan's duration, reusing the same {@link MavlinkConnection}/{@link MavlinkUdpInputStream}
 *       machinery {@link MavlinkSocketHub} uses, reads whatever heartbeats arrive, and releases
 *       the socket before returning. A bind failure (the port is held by something that is
 *       <em>not</em> this app's own gateway — e.g. a stray process) is reported as one WARN log
 *       and an empty result, never an exception: discovery must never break the scan-all flow
 *       over one mechanism's bind conflict.</li>
 * </ul>
 *
 * <p>Both paths use the fixed wildcard bind host ({@value MavlinkTelemetrySource#DEFAULT_BIND_HOST})
 * — only the port varies, per the constructor argument — matching how a device's own {@code
 * udp://host:port} URI already defaults an absent/blank host to the wildcard (see {@link
 * MavlinkTelemetrySource}).
 *
 * <h2>Naming a discovered vehicle</h2>
 * {@code "<Firmware> <vehicle kind> (sysid <n>)"}, e.g. {@code "ArduPilot quadcopter (sysid 7)"} —
 * firmware from {@code HEARTBEAT.autopilot} (reusing {@link
 * MavlinkTelemetryDecoder#firmwareLabel}), vehicle kind from {@code HEARTBEAT.type} (see {@link
 * #vehicleKind}). Neither is known until a {@code HEARTBEAT} has actually been heard from that
 * vehicle — before that, a sysid is still reported (from whatever other message type first
 * revealed it), just as {@code "MAVLink vehicle (sysid n)"}, never a fabricated firmware/kind.
 * {@link DiscoveredDevice#suggestedCategory()} is {@code "drone"} only for an airborne vehicle
 * kind (quadcopter/hexacopter/octocopter/tricopter/helicopter/fixed-wing); a rover/boat/unknown
 * kind gets no suggested category, same "don't guess past what was actually observed" discipline.
 *
 * <p>Blocking, self-time-boxed to ~{@code timeout} on both paths; never throws for "nothing
 * heard" — an empty list is a normal, non-error result, per {@link DeviceDiscoveryPort}'s
 * contract.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration, given the same {@link MavlinkTelemetrySource} instance used for real
 * telemetry ingest so the hub-borrow path actually sees what that instance has open.
 */
public final class MavlinkHeartbeatScanner implements DeviceDiscoveryPort {

    private static final System.Logger LOG = System.getLogger(MavlinkHeartbeatScanner.class.getName());

    private static final String METHOD = "mavlink";
    private static final CategoryId DRONE_CATEGORY = new CategoryId("drone");

    // MAV_TYPE raw values this scanner cares about for naming/categorization -- duplicated from the
    // upstream MAVLink common.xml enum rather than reused from FlightModes (whose own MAV_TYPE_*
    // constants are private to that class, and exist there only to pick a mode-name table, not to
    // label a vehicle kind).
    private static final int MAV_TYPE_FIXED_WING = 1;
    private static final int MAV_TYPE_QUADROTOR = 2;
    private static final int MAV_TYPE_COAXIAL = 3;
    private static final int MAV_TYPE_HELICOPTER = 4;
    private static final int MAV_TYPE_GROUND_ROVER = 10;
    private static final int MAV_TYPE_SURFACE_BOAT = 11;
    private static final int MAV_TYPE_HEXAROTOR = 13;
    private static final int MAV_TYPE_OCTOROTOR = 14;
    private static final int MAV_TYPE_TRICOPTER = 15;

    private static final String KIND_VEHICLE = "vehicle";
    private static final Set<String> AIRBORNE_KINDS =
            Set.of("quadcopter", "hexacopter", "octocopter", "tricopter", "helicopter", "fixed-wing");

    private final MavlinkTelemetrySource telemetrySource;
    private final int port;
    private final int activeHubPollCount;
    private final long activeHubMinPollIntervalMillis;
    private final long selfBindMinReadTimeoutMillis;
    private final long selfBindMaxReadTimeoutMillis;

    /**
     * @param telemetrySource the same instance {@code vision-app} wires as the real {@code
     *                        TelemetrySourcePort} bean, so the hub-borrow path sees devices that
     *                        instance actually has open
     * @param port            the MAVLink GCS UDP port to scan (vision-app wires the well-known
     *                        default, 14550); this class makes no assumption about which port it is
     */
    public MavlinkHeartbeatScanner(MavlinkTelemetrySource telemetrySource, int port) {
        this(telemetrySource, port, MavlinkSettings.Scan.defaults());
    }

    /**
     * @param scan this module's {@code vision.mavlink.scan.*} poll/self-bind-timeout budget
     *             (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F2) — replaces this class's own
     *             {@code ACTIVE_HUB_POLL_COUNT}/{@code ACTIVE_HUB_MIN_POLL_INTERVAL_MILLIS}/
     *             {@code SELF_BIND_MIN_READ_TIMEOUT_MILLIS}/{@code SELF_BIND_MAX_READ_TIMEOUT_MILLIS}
     *             constants
     */
    public MavlinkHeartbeatScanner(MavlinkTelemetrySource telemetrySource, int port, MavlinkSettings.Scan scan) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
        Objects.requireNonNull(scan, "scan must not be null");
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be in [1,65535], got " + port);
        }
        this.port = port;
        this.activeHubPollCount = scan.activeHubPollCount();
        this.activeHubMinPollIntervalMillis = scan.activeHubMinPollInterval().toMillis();
        this.selfBindMinReadTimeoutMillis = scan.selfBindMinReadTimeout().toMillis();
        this.selfBindMaxReadTimeoutMillis = scan.selfBindMaxReadTimeout().toMillis();
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<DiscoveredDevice> scan(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        return telemetrySource.hasActiveHub(bindKey) ? scanActiveHub(bindKey, timeout) : scanBySelfBinding(timeout);
    }

    /**
     * Borrows an already-running hub's socket: no bind of its own, just repeated reads of the
     * claimed/unclaimed registries {@link MavlinkSocketHub} already maintains, spread across the
     * timeout window so a vehicle whose first {@code HEARTBEAT} (and therefore firmware/mavType
     * label) arrives partway through the scan is still picked up.
     */
    private List<DiscoveredDevice> scanActiveHub(String bindKey, Duration timeout) {
        long timeoutMillis = Math.max(0L, timeout.toMillis());
        long intervalMillis = Math.max(activeHubMinPollIntervalMillis, timeoutMillis / activeHubPollCount);
        long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;

        Map<Integer, MavlinkSocketHub.ClaimedVehicle> claimed = new LinkedHashMap<>();
        Map<Integer, MavlinkSocketHub.UnclaimedVehicle> unclaimed = new LinkedHashMap<>();
        while (true) {
            for (MavlinkSocketHub.ClaimedVehicle vehicle : telemetrySource.claimedVehicles(bindKey)) {
                claimed.put(vehicle.sysid(), vehicle);
            }
            for (MavlinkSocketHub.UnclaimedVehicle vehicle : telemetrySource.unclaimedVehicles(bindKey)) {
                unclaimed.put(vehicle.sysid(), vehicle);
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            sleepQuietly(Math.min(intervalMillis, remainingNanos / 1_000_000L + 1));
        }
        unclaimed.keySet().removeAll(claimed.keySet()); // a claimed vehicle is never also reported unclaimed

        List<DiscoveredDevice> devices = new ArrayList<>();
        for (MavlinkSocketHub.ClaimedVehicle vehicle : claimed.values()) {
            devices.add(toDiscoveredDevice(vehicle.sysid(), vehicle.firmware(), vehicle.mavType(), vehicle.deviceId()));
        }
        for (MavlinkSocketHub.UnclaimedVehicle vehicle : unclaimed.values()) {
            devices.add(toDiscoveredDevice(vehicle.sysid(), vehicle.firmware(), vehicle.mavType(), null));
        }
        return List.copyOf(devices);
    }

    /**
     * Nothing has this port open: binds it for the scan's duration, reusing {@link
     * MavlinkConnection}/{@link MavlinkUdpInputStream} exactly like {@link MavlinkSocketHub}'s own
     * read loop, and releases the socket before returning. A bind failure (port held by something
     * other than this app's own gateway) is swallowed to an empty result plus one WARN log — never
     * thrown, so one mechanism's bind conflict can never break the parallel scan-all flow.
     */
    private List<DiscoveredDevice> scanBySelfBinding(Duration timeout) {
        long timeoutMillis = Math.max(0L, timeout.toMillis());
        DatagramSocket socket;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port));
            int readTimeoutMillis = (int) Math.max(selfBindMinReadTimeoutMillis,
                    Math.min(selfBindMaxReadTimeoutMillis, Math.max(1L, timeoutMillis)));
            socket.setSoTimeout(readTimeoutMillis);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "MAVLink heartbeat scan could not bind udp://"
                    + MavlinkTelemetrySource.DEFAULT_BIND_HOST + ":" + port
                    + " -- likely already bound by something other than this app's own MAVLink gateway: " + e);
            return List.of();
        }
        try {
            MavlinkConnection connection =
                    MavlinkConnection.create(new MavlinkUdpInputStream(socket), OutputStream.nullOutputStream());

            long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
            Map<Integer, Sighting> sightings = new LinkedHashMap<>();
            while (System.nanoTime() < deadlineNanos) {
                try {
                    MavlinkMessage<?> message = connection.next();
                    recordSighting(sightings, message);
                } catch (SocketTimeoutException e) {
                    // Nothing arrived in this slice -- the loop above re-checks the deadline; the
                    // blocking receive() itself is what avoids a busy-spin here, not this catch.
                } catch (IOException e) {
                    break; // a genuine socket failure -- stop scanning, report whatever was heard so far
                }
            }
            List<DiscoveredDevice> devices = new ArrayList<>();
            for (Sighting sighting : sightings.values()) {
                devices.add(toDiscoveredDevice(sighting.sysid, sighting.firmware, sighting.mavType, null));
            }
            return List.copyOf(devices);
        } finally {
            socket.close();
        }
    }

    private static void recordSighting(Map<Integer, Sighting> sightings, MavlinkMessage<?> message) {
        int sysid = message.getOriginSystemId();
        Sighting sighting = sightings.computeIfAbsent(sysid, Sighting::new);
        if (message.getPayload() instanceof Heartbeat heartbeat) {
            sighting.firmware = MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot().value());
            sighting.mavType = heartbeat.type().value();
        }
    }

    private DiscoveredDevice toDiscoveredDevice(int sysid, String firmwareLabel, Integer mavType, DeviceId claimedBy) {
        String kind = vehicleKind(mavType);
        String name = firmwareDisplayName(firmwareLabel) + " " + kind + " (sysid " + sysid + ")";
        URI uri = URI.create("udp://" + MavlinkTelemetrySource.DEFAULT_BIND_HOST + ":" + port);
        CategoryId category = AIRBORNE_KINDS.contains(kind) ? DRONE_CATEGORY : null;
        StreamDescriptor stream =
                new StreamDescriptor(METHOD, uri, Map.of(MavlinkTelemetrySource.OPTION_SYSID, String.valueOf(sysid)));

        Map<String, String> details = new LinkedHashMap<>();
        details.put("firmware", firmwareLabel == null ? "unknown" : firmwareLabel);
        details.put("mavType", kind);
        details.put("sysid", String.valueOf(sysid));
        if (claimedBy != null) {
            details.put("claimed", "true");
            details.put("claimedBy", claimedBy.value().toString());
        }
        return new DiscoveredDevice(METHOD, name, uri, category, stream, details);
    }

    private static String firmwareDisplayName(String firmwareLabel) {
        if (firmwareLabel == null) {
            return "MAVLink";
        }
        return switch (firmwareLabel) {
            case "ardupilot" -> "ArduPilot";
            case "px4" -> "PX4";
            case "generic" -> "Generic";
            default -> "MAVLink";
        };
    }

    /** @return quadcopter/hexacopter/octocopter/tricopter/helicopter/fixed-wing/rover/boat, or {@value #KIND_VEHICLE} if unknown/unmapped */
    private static String vehicleKind(Integer mavType) {
        if (mavType == null) {
            return KIND_VEHICLE;
        }
        return switch (mavType) {
            case MAV_TYPE_QUADROTOR -> "quadcopter";
            case MAV_TYPE_HEXAROTOR -> "hexacopter";
            case MAV_TYPE_OCTOROTOR -> "octocopter";
            case MAV_TYPE_TRICOPTER -> "tricopter";
            case MAV_TYPE_HELICOPTER, MAV_TYPE_COAXIAL -> "helicopter";
            case MAV_TYPE_FIXED_WING -> "fixed-wing";
            case MAV_TYPE_GROUND_ROVER -> "rover";
            case MAV_TYPE_SURFACE_BOAT -> "boat";
            default -> KIND_VEHICLE;
        };
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mutable accumulator for the self-bind path: refined in place as more messages (esp. a HEARTBEAT) arrive for this sysid. */
    private static final class Sighting {
        private final int sysid;
        private String firmware;
        private Integer mavType;

        private Sighting(int sysid) {
            this.sysid = sysid;
        }
    }
}
