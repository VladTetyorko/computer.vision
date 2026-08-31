package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.session.LinkHealth;
import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>docs/plans/active/MAVLINK-CORE-PLAN.md W4: rebuilt onto {@code libs/mavlink-core} — the socket/
 * read-thread/dispatch machinery this class used to reach through a hand-rolled {@code
 * MavlinkSocketHub} now lives in {@code mavlink-core}'s L1-L3, wrapped per bind address by {@link
 * MavlinkGateway}. This class's own public contract, and every field mapping, is unchanged.
 *
 * <h2>{@code udp://host:port} means <b>listen</b>, not connect</h2>
 * A telemetry radio or SITL instance <b>pushes</b> datagrams to this app; this adapter never
 * dials out. {@code host} is the local bind address (blank/absent falls back to the wildcard
 * {@value #DEFAULT_BIND_HOST}, i.e. all interfaces); {@code port} is the local UDP port. The
 * sender's own address is irrelevant and never validated — any datagram arriving on the bound
 * port is read, which is exactly how multiple vehicles end up sharing one port.
 *
 * <h2>One socket, many vehicles ({@link MavlinkGateway})</h2>
 * Every {@link #open(Device)} call for the same bind address ({@code host:port}) shares one
 * {@link MavlinkGateway} — one bound UDP link, one {@code mavlink-core} session,
 * reference-counted across every device registered against it; the gateway closes once the last
 * device sharing it calls {@link #close(DeviceId)}. The gateway demultiplexes incoming traffic by
 * MAVLink system id (see its own javadoc for why source-address demux was descoped) and routes
 * each device only the messages from the vehicle it claims:
 * <ul>
 *   <li>{@code StreamDescriptor.options[}{@value #OPTION_SYSID}{@code ]} — a positive integer
 *       1–255 <b>pins</b> this device to exactly that system id; missing/blank/malformed/
 *       out-of-range falls back to <b>unpinned</b> (lenient, same idiom as every other option in
 *       this module).</li>
 *   <li>An unpinned device claims the first system id heard on the socket that nothing else
 *       already claims, and may re-elect to a different (still-unclaimed) system id after its
 *       claimed vehicle has been silent for 30s — see {@link MavlinkGateway}/{@link
 *       VehicleClaimPolicy} for the exact claim/re-election rules.</li>
 * </ul>
 * Vehicles heard but claimed by nobody are not silently dropped — see {@link
 * MavlinkGateway#unclaimedVehicles()} (consumed by {@code MavlinkHeartbeatScanner}).
 *
 * <h2>Standing lobby hold (claim-free) — {@link #holdLobby(int)}/{@link #releaseLobby(int)}</h2>
 * docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2b: a caller (a discovery runner, in
 * a later wave) can keep a gateway bound with <b>zero</b> device registrations, so the port is
 * listening — and answering with a GCS heartbeat — from boot, not only while a device or a scan
 * happens to be open. This never claims anything; it only widens <i>when</i> {@link
 * #unclaimedVehicles} gets populated. See {@link #holdLobby(int)}'s own javadoc.
 *
 * <h2>Bind failures are now synchronous</h2>
 * {@code mavlink-core}'s {@code UdpListenLink} binds in its own constructor, so a bind conflict
 * (the requested {@code host:port} is already held by something other than this adapter's own
 * gateway) now surfaces synchronously as an {@link UncheckedIOException} from {@link
 * #open(Device)} itself, rather than asynchronously via the returned publisher's {@code onError}
 * as the pre-W4 hub's own lazily-binding background thread did. This is a deliberate, more honest
 * behavior change — see this module's {@code MODULE.md} Gotchas.
 *
 * <h2>Robustness</h2>
 * Unparseable/garbage datagrams are never fatal — {@code mavlink-core}'s {@code FrameReader}
 * silently resyncs past anything that fails to parse or fails CRC, so this adapter adds no extra
 * try/catch around individual reads for that case.
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
    private final Map<String, MavlinkGateway> gateways = new ConcurrentHashMap<>();
    private final Map<DeviceId, DeviceRuntime> runtimes = new ConcurrentHashMap<>();

    public MavlinkTelemetrySource() {
        this(MavlinkSettings.defaults());
    }

    /**
     * @param settings this module's {@code vision.mavlink.*} tunables (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     *                 wave F2) — supplies the local bind-host fallback, the unpinned re-election
     *                 silence window, the shared gateway's close-join timeout, and the bounded
     *                 unclaimed-vehicle registry cap, all threaded into each {@link MavlinkGateway}
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
        MavlinkGateway[] gatewayHolder = new MavlinkGateway[1];
        gateways.compute(bindKey, (key, existing) -> {
            MavlinkGateway gateway = existing == null || existing.isClosed() ? newGateway(host, port) : existing;
            registrationHolder[0] = gateway.register(device.id(), pinnedSysid, publisher);
            gatewayHolder[0] = gateway;
            return gateway;
        });

        DeviceRuntime runtime = new DeviceRuntime(bindKey, gatewayHolder[0], registrationHolder[0]);
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
     * currently-open device claims — delegates to {@link MavlinkGateway#unclaimedVehicles()};
     * empty when nothing has ever been opened on that address. Package-private: {@code
     * MavlinkHeartbeatScanner} is the intended consumer.
     */
    List<MavlinkGateway.UnclaimedVehicle> unclaimedVehicles(String bindKey) {
        MavlinkGateway gateway = gateways.get(bindKey);
        return gateway == null ? List.of() : gateway.unclaimedVehicles();
    }

    /**
     * Whether a gateway for this bind address is currently active (i.e. some device has it open
     * right now). Lets {@code MavlinkHeartbeatScanner} borrow an already-running gateway's socket
     * instead of trying (and failing) to bind a port this adapter already owns.
     */
    boolean hasActiveHub(String bindKey) {
        MavlinkGateway gateway = gateways.get(bindKey);
        return gateway != null && !gateway.isClosed();
    }

    /**
     * Vehicles currently claimed by an open device on the given bind address — delegates to {@link
     * MavlinkGateway#claimedVehicles()}; empty when nothing is open on that address. Package-
     * private, same "future consumer" shape as {@link #unclaimedVehicles}: {@code
     * MavlinkHeartbeatScanner} is the consumer.
     */
    List<MavlinkGateway.ClaimedVehicle> claimedVehicles(String bindKey) {
        MavlinkGateway gateway = gateways.get(bindKey);
        return gateway == null ? List.of() : gateway.claimedVehicles();
    }

    /**
     * {@link LinkHealth.Health} for every vehicle currently claimed across every open gateway
     * (every bind address, not just one), keyed by {@link DeviceId} (FLEET-RADIO-PLAN.md D4) —
     * {@code mavlink-link}'s {@code SubsystemStatusPort} plumbing (docs/plans/done/SYSTEM-STATUS-PLAN.md
     * §4.2). Public — unlike this class's other {@code MavlinkGateway}-plumbing accessors — because
     * {@code vision-app}'s wiring passes {@code this::claimedVehicleHealth} as the {@code
     * Supplier<Map<DeviceId, LinkHealth.Health>>} {@link MavlinkLinkStatusProvider} takes; that
     * wiring class lives in a different package and cannot reach a package-private method. Empty
     * when no gateway is open, i.e. no MAVLink-protocol device has ever been opened — a genuinely
     * different, more honest state than "the link is down".
     *
     * <p>A {@code DeviceId} can claim on only one gateway at a time (one {@code open()} per device),
     * so merging every gateway's own map here can never collide two gateways' entries under the same
     * key.
     */
    public Map<DeviceId, LinkHealth.Health> claimedVehicleHealth() {
        Map<DeviceId, LinkHealth.Health> merged = new HashMap<>();
        for (MavlinkGateway gateway : gateways.values()) {
            merged.putAll(gateway.claimedVehicleHealth());
        }
        return merged;
    }

    /**
     * The bind key ({@code host:port}) a supported device's {@code udp://host:port} stream
     * resolves to — the same key {@link #open}/{@link #close} use internally to find/create a
     * {@link MavlinkGateway}. Lets {@code MavlinkFlightCommander}/{@code
     * MavlinkManualControlSender} address the same gateway this device's telemetry uses, without
     * duplicating the host-defaulting logic. Callers must have already confirmed {@link
     * #supports(Device)} — this assumes a non-null URI with a port, exactly like {@link #open}.
     */
    String bindKeyFor(Device device) {
        URI uri = device.stream().uri();
        return bindKey(bindHost(uri), uri.getPort());
    }

    /**
     * {@code deviceId}'s current command-TX coordinates on the gateway for {@code bindKey}, or
     * {@code null} if no gateway is active for that address or the device holds no claim on it
     * right now — delegates to {@link MavlinkGateway#commandTarget}.
     */
    MavlinkGateway.CommandTarget commandTarget(String bindKey, DeviceId deviceId) {
        MavlinkGateway gateway = gateways.get(bindKey);
        return gateway == null ? null : gateway.commandTarget(deviceId);
    }

    /**
     * The gateway backing {@code bindKey}, so a TX port class ({@code MavlinkFlightCommander},
     * {@code MavlinkManualControlSender}) can reach its {@code FrameSink}/{@code Correlator}/
     * {@code PeerDirectory} directly and build a {@code mavlink-core} service on top of them —
     * docs/plans/active/MAVLINK-CORE-PLAN.md W4 replaces the five separate transport/correlation
     * pass-throughs this class used to offer ({@code socket}, {@code awaitAck}, {@code
     * cancelAckWait}) with this one real collaborator. {@code null} if no gateway is active for
     * that address.
     */
    MavlinkGateway gateway(String bindKey) {
        return gateways.get(bindKey);
    }

    /**
     * Claim-free standing hold on the gateway for {@value #DEFAULT_BIND_HOST}{@code :port} — the
     * zero-config onboarding standing lobby (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11
     * Z2b). Computes/creates the gateway via the exact same atomic {@link Map#compute} path {@link
     * #open(Device)} uses, including self-healing a gateway that previously self-closed on a link
     * failure ({@code MavlinkGateway.handleLinkFailure} closes regardless of any hold — see {@link
     * MavlinkGateway#holdLobby()}'s own javadoc) — but registers <b>no claim</b>: nothing a hold does
     * is ever added to a gateway's {@link VehicleClaimPolicy} registrations, so it can never steal a
     * sysid an unpinned real device (a later {@link #open(Device)} call) would otherwise claim. Every
     * sysid heard while held still lands in {@link #unclaimedVehicles} exactly as it always has —
     * the lobby only widens <i>when</i> that registry is populated (bound at boot, not only while a
     * device or a scan happens to be open), never how it is populated.
     *
     * <p>Idempotent: holding an already-held port is a no-op. Once held, the gateway starts
     * transmitting a GCS {@code HEARTBEAT} on every link it has heard a peer on (see {@link
     * MavlinkGateway#holdLobby()}) — vision has never initiated MAVLink traffic before this; a
     * vehicle broadcasting to this port per the PX4 broadcast-until-heard convention locks unicast
     * onto the first such heartbeat it hears, closing the whole handshake without an operator ever
     * touching a form.
     *
     * @see #releaseLobby(int)
     */
    public void holdLobby(int port) {
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be in [1,65535], got " + port);
        }
        String bindKey = bindKey(DEFAULT_BIND_HOST, port);
        gateways.compute(bindKey, (key, existing) -> {
            MavlinkGateway gateway = existing == null || existing.isClosed()
                    ? newGateway(DEFAULT_BIND_HOST, port) : existing;
            gateway.holdLobby();
            return gateway;
        });
    }

    /**
     * Releases a hold acquired by {@link #holdLobby(int)}. Idempotent: releasing an unheld, never-
     * held, or unrecognized port does nothing. Always stops the GCS heartbeat TX the hold started;
     * closes the gateway (and evicts it from the shared map, mirroring {@link #closeRuntime}'s own
     * conditional eviction) only if this release leaves it with neither a lobby hold nor any device
     * registration — a device still streaming through it keeps the gateway running exactly as it
     * would have before this hold ever existed.
     */
    public void releaseLobby(int port) {
        String bindKey = bindKey(DEFAULT_BIND_HOST, port);
        MavlinkGateway gateway = gateways.get(bindKey);
        if (gateway == null) {
            return;
        }
        gateway.releaseLobby();
        if (gateway.isClosed()) {
            gateways.remove(bindKey, gateway);
        }
    }

    private MavlinkGateway newGateway(String host, int port) {
        try {
            return new MavlinkGateway(host, port, settings);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to bind MAVLink gateway on udp://" + host + ":" + port, e);
        }
    }

    /**
     * Unregisters {@code runtime} from the exact {@link MavlinkGateway} instance it was opened
     * against — not whatever gateway {@link #gateways} currently maps {@code runtime.bindKey()} to.
     *
     * <p>Those can differ: once {@link MavlinkGateway#handleLinkFailure} exists (FLEET-RADIO-PLAN.md
     * R4), a gateway can close itself from its own reader thread, independent of any {@code
     * unregister} call, and a fresh {@link #open} for the same bind address may already have
     * replaced it in {@link #gateways} (see that method's {@code existing.isClosed()} check) by the
     * time this device's {@link #close(DeviceId)} runs. Looking the gateway up by {@code bindKey}
     * here, as this method did before R4, would then unregister from the wrong — newer, healthy —
     * gateway instead of the failed one this runtime actually belongs to, corrupting a second
     * device's claim bookkeeping over a race this class never needed to run. Holding the gateway
     * reference directly in {@link DeviceRuntime} makes that impossible: {@link
     * MavlinkGateway#unregister} is idempotent and safe to call on an already-closed gateway.
     *
     * <p>The map is still touched, but only for its own conditional cleanup: {@link
     * Map#remove(Object, Object)} evicts the entry <i>only</i> if it still points at this exact
     * gateway, so a concurrent replacement already installed by a new {@link #open} is never
     * clobbered. {@link MavlinkGateway#unregister} now returns {@code true} only once this call has
     * actually closed the gateway — a standing lobby hold (docs/plans/active/
     * ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2b, see {@link #holdLobby(int)}) can leave the last
     * device's departure with zero registrations yet the gateway still very much alive, and this
     * eviction must not fire for that case.
     */
    private void closeRuntime(DeviceRuntime runtime) {
        boolean gatewayClosed = runtime.gateway().unregister(runtime.registration());
        if (gatewayClosed) {
            gateways.remove(runtime.bindKey(), runtime.gateway());
        }
    }

    private String bindHost(URI uri) {
        String host = uri.getHost();
        return host == null || host.isBlank() ? defaultBindHost : host;
    }

    /** The key {@link MavlinkGateway}s are shared under: one gateway per distinct bind address. */
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

    /**
     * This device's share of a {@link MavlinkGateway}: which bind address, which exact gateway
     * instance (see {@link #closeRuntime} for why this must be the instance actually registered
     * against, not a re-lookup by {@code bindKey}), and its registration within it.
     */
    private record DeviceRuntime(String bindKey, MavlinkGateway gateway, VehicleRegistration registration) {
    }
}
