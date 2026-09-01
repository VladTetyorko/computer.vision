package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.VehicleClass;
import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.UdpListenLink;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

import io.dronefleet.mavlink.minimal.Heartbeat;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link DeviceDiscoveryPort} implementation for plug-and-fly MAVLink heartbeat discovery
 * (docs/plans/active/DRONE-INFRA-PLAN.md I-b): every distinct MAVLink system id heard on the well-known GCS
 * port becomes a {@link DiscoveredDevice}, no manual sysid typing, no manual {@code udp://} URI
 * entry. docs/plans/active/MAVLINK-CORE-PLAN.md W4 rewired the self-bind path onto {@code
 * mavlink-core}'s {@link UdpListenLink}/{@link FrameReader} in place of a raw {@code
 * DatagramSocket}; naming, categorization, and every {@code details} key are byte-identical.
 *
 * <h2>Why this lives in {@code adapter-mavlink}, not {@code adapter-discovery}</h2>
 * Adapters never depend on each other (ArchUnit-enforced) — a scanner in {@code adapter-discovery}
 * could not see {@link MavlinkGateway} at all, and would have no choice but to bind its own socket
 * on every scan. That fails outright the moment a real MAVLink telemetry {@code Device} is already
 * open on the same port (the common case once I-a's gateway is running one aircraft), and even
 * when nothing is open yet, a separate socket can never see what the gateway itself has already
 * claimed. Living in this module lets the scanner reach {@link MavlinkTelemetrySource} directly
 * and share its already-bound socket instead.
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
 *       scan's duration with a plain {@link UdpListenLink}, reads whatever heartbeats arrive
 *       through a {@link FrameReader}, and releases the link before returning. A bind failure (the
 *       port is held by something that is <em>not</em> this app's own gateway — e.g. a stray
 *       process) is reported as one WARN log and an empty result, never an exception: discovery
 *       must never break the scan-all flow over one mechanism's bind conflict.</li>
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
 * MavlinkTelemetryDecoder#firmwareLabel}), vehicle kind from {@code HEARTBEAT.type} via {@link
 * com.drones.mavlink.VehicleClass#label} (see {@link #vehicleKind}). Neither is known until a
 * {@code HEARTBEAT} has actually been heard from that vehicle — before that, a sysid is still
 * reported (from whatever other message type first revealed it), just as {@code "MAVLink vehicle
 * (sysid n)"}, never a fabricated firmware/kind. <b>FLEET-RADIO R1 (F1c):</b> before this wave this
 * class held its own {@code MAV_TYPE} switch that knew no VTOL and no submarine, so both were
 * labelled the generic {@code "vehicle"} fallback — indistinguishable from truly not having heard a
 * heartbeat at all. It now shares {@code VehicleClass}'s one vocabulary with {@code
 * MavlinkVehicleConfigurator}'s onboarding probe, so the same vehicle is named identically by
 * discovery and by probing.
 * {@link DiscoveredDevice#suggestedCategory()} is {@code "drone"} only for a {@code
 * VehicleClass.COPTER}/{@code PLANE} vehicle (every rotorcraft and every fixed-wing/VTOL); a
 * rover/boat/submarine/unsupported/not-a-vehicle/unknown kind gets no suggested category, same
 * "don't guess past what was actually observed" discipline.
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

    // FLEET-RADIO R1 (F1c): this scanner used to hold its own MAV_TYPE switch here, duplicated from
    // (and disagreeing with) MavlinkVehicleConfigurator's. Both now read com.drones.mavlink.VehicleClass,
    // the one MAV_TYPE table -- see that class and this file's own vehicleKind/isAirborne below.
    private static final String KIND_VEHICLE = "vehicle";

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
     * Borrows an already-running gateway's socket: no bind of its own, just repeated reads of the
     * claimed/unclaimed registries {@link MavlinkGateway} already maintains, spread across the
     * timeout window so a vehicle whose first {@code HEARTBEAT} (and therefore firmware/mavType
     * label) arrives partway through the scan is still picked up.
     */
    private List<DiscoveredDevice> scanActiveHub(String bindKey, Duration timeout) {
        long timeoutMillis = Math.max(0L, timeout.toMillis());
        long intervalMillis = Math.max(activeHubMinPollIntervalMillis, timeoutMillis / activeHubPollCount);
        long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;

        Map<Integer, MavlinkGateway.ClaimedVehicle> claimed = new LinkedHashMap<>();
        Map<Integer, MavlinkGateway.UnclaimedVehicle> unclaimed = new LinkedHashMap<>();
        while (true) {
            for (MavlinkGateway.ClaimedVehicle vehicle : telemetrySource.claimedVehicles(bindKey)) {
                claimed.put(vehicle.sysid(), vehicle);
            }
            for (MavlinkGateway.UnclaimedVehicle vehicle : telemetrySource.unclaimedVehicles(bindKey)) {
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
        for (MavlinkGateway.ClaimedVehicle vehicle : claimed.values()) {
            devices.add(toDiscoveredDevice(vehicle.sysid(), vehicle.firmware(), vehicle.mavType(), vehicle.deviceId()));
        }
        for (MavlinkGateway.UnclaimedVehicle vehicle : unclaimed.values()) {
            devices.add(toDiscoveredDevice(vehicle.sysid(), vehicle.firmware(), vehicle.mavType(), null));
        }
        return List.copyOf(devices);
    }

    /**
     * Nothing has this port open: binds it for the scan's duration with a plain {@link
     * UdpListenLink} + {@link FrameReader}, releasing the link before returning. A bind failure
     * (port held by something other than this app's own gateway) is swallowed to an empty result
     * plus one WARN log — never thrown, so one mechanism's bind conflict can never break the
     * parallel scan-all flow.
     */
    private List<DiscoveredDevice> scanBySelfBinding(Duration timeout) {
        long timeoutMillis = Math.max(0L, timeout.toMillis());
        UdpListenLink link;
        try {
            link = new UdpListenLink(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "MAVLink heartbeat scan could not bind udp://"
                    + MavlinkTelemetrySource.DEFAULT_BIND_HOST + ":" + port
                    + " -- likely already bound by something other than this app's own MAVLink gateway: " + e);
            return List.of();
        }
        try {
            FrameReader reader = new FrameReader(link);
            long readTimeoutMillis = Math.max(selfBindMinReadTimeoutMillis,
                    Math.min(selfBindMaxReadTimeoutMillis, Math.max(1L, timeoutMillis)));
            Duration pollTimeout = Duration.ofMillis(readTimeoutMillis);

            long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
            Map<Integer, Sighting> sightings = new LinkedHashMap<>();
            while (System.nanoTime() < deadlineNanos) {
                ByteChunk chunk;
                try {
                    chunk = link.poll(pollTimeout);
                } catch (IOException e) {
                    break; // a genuine socket failure -- stop scanning, report whatever was heard so far
                }
                if (chunk == null) {
                    continue; // nothing arrived in this slice -- the loop re-checks the deadline, no busy-spin
                }
                reader.offer(chunk, frame -> recordSighting(sightings, frame));
            }
            List<DiscoveredDevice> devices = new ArrayList<>();
            for (Sighting sighting : sightings.values()) {
                devices.add(toDiscoveredDevice(sighting.sysid, sighting.firmware, sighting.mavType, null));
            }
            return List.copyOf(devices);
        } finally {
            link.close();
        }
    }

    private static void recordSighting(Map<Integer, Sighting> sightings, MavFrame frame) {
        int sysid = frame.header().system().value();
        Sighting sighting = sightings.computeIfAbsent(sysid, Sighting::new);
        if (frame.is(Heartbeat.class)) {
            Heartbeat heartbeat = frame.as(Heartbeat.class);
            sighting.firmware = MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot().value());
            sighting.mavType = heartbeat.type().value();
        }
    }

    private DiscoveredDevice toDiscoveredDevice(int sysid, String firmwareLabel, Integer mavType, DeviceId claimedBy) {
        String kind = vehicleKind(mavType);
        String name = firmwareDisplayName(firmwareLabel) + " " + kind + " (sysid " + sysid + ")";
        URI uri = URI.create("udp://" + MavlinkTelemetrySource.DEFAULT_BIND_HOST + ":" + port);
        CategoryId category = isAirborne(mavType) ? DRONE_CATEGORY : null;
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

    /**
     * The shared {@link VehicleClass#label} for {@code mavType}, or {@value #KIND_VEHICLE} when
     * nothing better is known — either no {@code HEARTBEAT} has been heard yet ({@code mavType ==
     * null}) or the number is genuinely unrecognized. A recognized-but-unsupported airframe or a
     * non-vehicle instrument (a gimbal, a GCS) still gets its own real label here, never this
     * fallback (FLEET-RADIO R1, F1c) — package-private so {@code VehicleTaxonomyAgreementTest} can
     * assert it against {@code MavlinkVehicleConfigurator}'s own vehicle-kind lookup directly.
     */
    static String vehicleKind(Integer mavType) {
        if (mavType == null) {
            return KIND_VEHICLE;
        }
        String label = VehicleClass.label(mavType);
        return label != null ? label : KIND_VEHICLE;
    }

    /** {@code true} only for a real airborne family ({@link VehicleClass#COPTER}/{@link VehicleClass#PLANE}). */
    private static boolean isAirborne(Integer mavType) {
        if (mavType == null) {
            return false;
        }
        VehicleClass vehicleClass = VehicleClass.of(mavType);
        return vehicleClass == VehicleClass.COPTER || vehicleClass == VehicleClass.PLANE;
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
