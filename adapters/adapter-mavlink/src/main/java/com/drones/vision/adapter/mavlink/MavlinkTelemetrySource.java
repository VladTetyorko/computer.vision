package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import io.dronefleet.mavlink.common.CommandAck;

import java.net.DatagramSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * {@link TelemetrySourcePort} implementation that ingests MAVLink 2 telemetry over UDP — the
 * de-facto transport for telemetry radios and ArduPilot/PX4 SITL (docs/plans/done/MVP2-PLAN.md X-a) — and,
 * as of docs/plans/active/DRONE-INFRA-PLAN.md I-a, the fleet gateway: N vehicles sharing one well-known GCS
 * port (14550), matching how every real radio bridge actually behaves in the field. Supports
 * {@link StreamDescriptor#protocol()} {@code "mavlink"} with a {@code udp://host:port} {@link
 * StreamDescriptor#uri()}.
 *
 * <h2>{@code udp://host:port} means <b>listen</b>, not connect</h2>
 * A telemetry radio or SITL instance <b>pushes</b> datagrams to this app; this adapter never
 * dials out. {@code host} is the local bind address (blank/absent falls back to the wildcard
 * {@value #DEFAULT_BIND_HOST}, i.e. all interfaces); {@code port} is the local UDP port. The
 * sender's own address is irrelevant and never validated — any datagram arriving on the bound
 * port is read, which is exactly how multiple vehicles end up sharing one port.
 *
 * <h2>One socket, many vehicles ({@link MavlinkSocketHub})</h2>
 * Every {@link #open(Device)} call for the same bind address ({@code host:port}) shares one
 * {@link MavlinkSocketHub} — one {@code DatagramSocket}, one read thread, reference-counted
 * across every device registered against it; the socket closes once the last device sharing it
 * calls {@link #close(DeviceId)}. The hub demultiplexes incoming traffic by MAVLink system id
 * (see its own javadoc for why source-address demux was descoped) and routes each device only
 * the messages from the vehicle it claims:
 * <ul>
 *   <li>{@code StreamDescriptor.options[}{@value #OPTION_SYSID}{@code ]} — a positive integer
 *       1–255 <b>pins</b> this device to exactly that system id; missing/blank/malformed/
 *       out-of-range falls back to <b>unpinned</b> (lenient, same idiom as every other option in
 *       this module).</li>
 *   <li>An unpinned device claims the first system id heard on the socket that nothing else
 *       already claims, and may re-elect to a different (still-unclaimed) system id after its
 *       claimed vehicle has been silent for 30s — see {@link MavlinkSocketHub} for the exact
 *       claim/re-election rules.</li>
 * </ul>
 * Vehicles heard but claimed by nobody are not silently dropped — see {@link
 * MavlinkSocketHub#unclaimedVehicles()} (docs/plans/active/DRONE-INFRA-PLAN.md I-b consumes this next).
 *
 * <h2>Robustness</h2>
 * Unparseable/garbage datagrams are never fatal — {@code MavlinkConnection#next()} itself scans
 * for the next valid frame-start marker and silently drops anything that fails to parse or fails
 * CRC (see {@link MavlinkUdpInputStream}'s javadoc for the full reasoning), so this adapter adds
 * no extra try/catch around individual reads for that case. The only exception path that reaches
 * a device's own publisher is a genuine {@link java.io.IOException} from the shared socket
 * itself — almost always the hub's own close unblocking the read thread, which is treated as a
 * graceful shutdown, not an error.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration.
 */
public final class MavlinkTelemetrySource implements TelemetrySourcePort {

    private static final String PROTOCOL = "mavlink";
    private static final String SCHEME_UDP = "udp";
    static final String DEFAULT_BIND_HOST = "0.0.0.0";

    /** {@code StreamDescriptor.options} key for pinning a device to one MAVLink system id (docs/plans/active/DRONE-INFRA-PLAN.md I-a). */
    static final String OPTION_SYSID = "sysid";
    private static final int MIN_SYSID = 1;
    private static final int MAX_SYSID = 255;

    private final String defaultBindHost;
    private final MavlinkSettings settings;
    private final Map<String, MavlinkSocketHub> hubs = new ConcurrentHashMap<>();
    private final Map<DeviceId, DeviceRuntime> runtimes = new ConcurrentHashMap<>();

    public MavlinkTelemetrySource() {
        this(MavlinkSettings.defaults());
    }

    /**
     * @param settings this module's {@code vision.mavlink.*} tunables (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     *                 wave F2) — supplies the local bind-host fallback, the unpinned re-election
     *                 silence window, the shared hub's close-join timeout, and the bounded
     *                 unclaimed-vehicle registry cap, all threaded into each {@link MavlinkSocketHub}
     *                 this instance creates.
     */
    public MavlinkTelemetrySource(MavlinkSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.defaultBindHost = settings.bindHost();
    }

    /** Test-only hook: a shorter unpinned re-election silence window than the production 30s default. */
    MavlinkTelemetrySource(long silenceWindowMillis) {
        this.settings = MavlinkSettings.defaults().withSilenceWindow(Duration.ofMillis(silenceWindowMillis));
        this.defaultBindHost = settings.bindHost();
    }

    @Override
    public boolean supports(Device device) {
        if (device == null || !device.capabilities().contains(Capability.TELEMETRY)) {
            return false;
        }
        StreamDescriptor stream = device.stream();
        if (!PROTOCOL.equals(stream.protocol())) {
            return false;
        }
        URI uri = stream.uri();
        return uri != null && SCHEME_UDP.equalsIgnoreCase(uri.getScheme()) && uri.getPort() > 0;
    }

    @Override
    public Flow.Publisher<Telemetry> open(Device device) {
        if (!supports(device)) {
            throw new IllegalArgumentException("MavlinkTelemetrySource does not support device: " + device);
        }
        URI uri = device.stream().uri();
        String host = bindHost(uri);
        int port = uri.getPort();
        String bindKey = bindKey(host, port);
        Integer pinnedSysid = pinnedSysidOption(device.stream().options());

        SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
        VehicleRegistration[] registrationHolder = new VehicleRegistration[1];
        hubs.compute(bindKey, (key, existing) -> {
            MavlinkSocketHub hub = existing == null || existing.isClosed()
                    ? new MavlinkSocketHub(host, port, settings)
                    : existing;
            registrationHolder[0] = hub.register(device.id(), pinnedSysid, publisher);
            return hub;
        });

        DeviceRuntime runtime = new DeviceRuntime(bindKey, registrationHolder[0]);
        DeviceRuntime previous = runtimes.put(device.id(), runtime);
        if (previous != null) {
            closeRuntime(previous); // defensive: a device id must not have two live runtimes
        }
        return publisher;
    }

    @Override
    public void close(DeviceId id) {
        DeviceRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            closeRuntime(runtime);
        }
    }

    /**
     * Vehicles heard on the given bind address ({@code host:port}, see {@link #bindKey}) that no
     * currently-open device claims — delegates to {@link MavlinkSocketHub#unclaimedVehicles()};
     * empty when nothing has ever been opened on that address. Package-private: docs/
     * DRONE-INFRA-PLAN.md I-b is the intended future consumer, once it exists.
     */
    List<MavlinkSocketHub.UnclaimedVehicle> unclaimedVehicles(String bindKey) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        return hub == null ? List.of() : hub.unclaimedVehicles();
    }

    /**
     * Whether a hub for this bind address is currently active (i.e. some device has it open right
     * now). docs/plans/active/DRONE-INFRA-PLAN.md I-b: lets {@code MavlinkHeartbeatScanner} borrow an already-
     * running hub's socket instead of trying (and failing) to bind a port the gateway already owns.
     */
    boolean hasActiveHub(String bindKey) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        return hub != null && !hub.isClosed();
    }

    /**
     * Vehicles currently claimed by an open device on the given bind address — delegates to {@link
     * MavlinkSocketHub#claimedVehicles()}; empty when nothing is open on that address. Package-
     * private, same "future consumer" shape as {@link #unclaimedVehicles}: docs/plans/active/DRONE-INFRA-PLAN.md
     * I-b (`MavlinkHeartbeatScanner`) is the consumer.
     */
    List<MavlinkSocketHub.ClaimedVehicle> claimedVehicles(String bindKey) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        return hub == null ? List.of() : hub.claimedVehicles();
    }

    /**
     * The bind key ({@code host:port}) a supported device's {@code udp://host:port} stream
     * resolves to — the same key {@link #open}/{@link #close} use internally to find/create a
     * {@link MavlinkSocketHub}. docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1: lets {@code
     * MavlinkFlightCommander} address the same hub this device's telemetry uses, without
     * duplicating the host-defaulting logic. Callers must have already confirmed {@link
     * #supports(Device)} — this assumes a non-null URI with a port, exactly like {@link #open}.
     */
    String bindKeyFor(Device device) {
        URI uri = device.stream().uri();
        return bindKey(bindHost(uri), uri.getPort());
    }

    /**
     * docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1: {@code deviceId}'s current command-TX coordinates on
     * the hub for {@code bindKey}, or {@code null} if no hub is active for that address or the
     * device holds no claim on it right now — delegates to {@link MavlinkSocketHub#commandTarget}.
     */
    MavlinkSocketHub.CommandTarget commandTarget(String bindKey, DeviceId deviceId) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        return hub == null ? null : hub.commandTarget(deviceId);
    }

    /**
     * The shared socket for {@code bindKey}'s hub, so a command sender can push a reply through
     * the same socket that receives that hub's traffic instead of opening a second one
     * (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1). {@code null} if no active hub.
     */
    DatagramSocket socket(String bindKey) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        return hub == null ? null : hub.socket();
    }

    /**
     * docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1: registers interest in the next {@code COMMAND_ACK}
     * matching {@code sysid}/{@code commandId} on {@code bindKey}'s hub — delegates to {@link
     * MavlinkSocketHub#awaitAck}. Returns an already-failed future (never {@code null}) if no hub
     * is active for that address, so callers can treat both cases uniformly.
     */
    CompletableFuture<CommandAck> awaitAck(String bindKey, int sysid, int commandId) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        if (hub == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No active MAVLink gateway for " + bindKey));
        }
        return hub.awaitAck(sysid, commandId);
    }

    /** docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1: releases a waiter registered via {@link #awaitAck}, idempotent. */
    void cancelAckWait(String bindKey, int sysid, int commandId) {
        MavlinkSocketHub hub = hubs.get(bindKey);
        if (hub != null) {
            hub.cancelAckWait(sysid, commandId);
        }
    }

    private void closeRuntime(DeviceRuntime runtime) {
        hubs.compute(runtime.bindKey(), (key, hub) -> {
            if (hub == null) {
                return null;
            }
            boolean hubNowEmpty = hub.unregister(runtime.registration());
            return hubNowEmpty ? null : hub;
        });
    }

    private String bindHost(URI uri) {
        String host = uri.getHost();
        return host == null || host.isBlank() ? defaultBindHost : host;
    }

    /** The key {@link MavlinkSocketHub}s are shared under: one hub per distinct bind address. */
    static String bindKey(String host, int port) {
        return host + ":" + port;
    }

    /** Lenient like every other option in this module: missing/blank/malformed/out-of-range (not 1-255) -> unpinned. */
    private static Integer pinnedSysidOption(Map<String, String> options) {
        String raw = options.get(OPTION_SYSID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= MIN_SYSID && value <= MAX_SYSID ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** This device's share of a {@link MavlinkSocketHub}: which hub, and its registration within it. */
    private record DeviceRuntime(String bindKey, VehicleRegistration registration) {
    }
}
