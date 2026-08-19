package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.service.CapabilityReport;
import com.drones.mavlink.service.CapabilityService;
import com.drones.mavlink.service.MessageIntervalService;
import com.drones.mavlink.service.ParameterOutcome;
import com.drones.mavlink.service.ParameterService;
import com.drones.mavlink.service.CommandService;
import com.drones.mavlink.session.HeartbeatInfo;
import com.drones.mavlink.session.Peer;
import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.RemediationResultCode;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.warehouse.domain.model.Device;

import io.dronefleet.mavlink.MavlinkDialect;
import io.dronefleet.mavlink.ardupilotmega.ArdupilotmegaDialect;
import io.dronefleet.mavlink.common.MavParamType;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The MAVLink half of vehicle onboarding (docs/plans/active/DRONE-ONBOARDING-PLAN.md wave O4):
 * turns "there is an aircraft at this address" into a {@link VehicleProfile} nobody had to type by
 * hand, and applies the two remediation mechanisms the plan defines.
 *
 * <h2>The four things this does, and what each costs the aircraft</h2>
 * <ul>
 *   <li>{@link #probe} — passive inventory (free, {@code MavlinkMessageInventory} is already
 *       counting) plus <b>two</b> active requests: one {@code AUTOPILOT_VERSION} and one batch of
 *       named parameter reads. Nothing is written.</li>
 *   <li>{@link #requestMessageInterval} — <b>Mechanism A</b>, a {@code MAV_CMD_SET_MESSAGE_INTERVAL}
 *       command. Not a write: it changes what the aircraft streams for this session and survives
 *       nothing, so it needs no snapshot and no restore.</li>
 *   <li>{@link #readParams} — named reads only.</li>
 *   <li>{@link #writeParam} — <b>Mechanism B</b>, the only method here that changes persistent
 *       state on the aircraft. Snapshot-before and read-back-after are done here, not asked of the
 *       caller (see below).</li>
 * </ul>
 *
 * <h2>Addressed by {@code linkKey}, not by {@code Device}</h2>
 * Every method takes a {@code "udp://host:port#sysid"} string because the plan's stage-3 probe runs
 * <i>before</i> registration, when no {@code DeviceId} exists yet (plan D7). The sysid suffix is
 * optional for {@link #probe} alone — an operator probing a fresh address genuinely does not know it
 * yet, and a single observed aircraft is unambiguous. It is <b>required</b> for the other three:
 * "set an interval on whichever aircraft happens to be there" is not a request this class will
 * guess at.
 *
 * <h2>Riding the shared socket</h2>
 * If a {@link MavlinkGateway} is already bound to the address (the normal case — a registered device
 * is streaming through it), this borrows it and closes nothing. If none is, it opens a temporary one
 * for the call's duration and closes it afterwards. A second socket on a port our own gateway holds
 * would see none of the traffic that gateway has already consumed — the same reasoning
 * {@code MavlinkHeartbeatScanner} documents for its own hub-borrow path.
 *
 * <h2>Silence is never fabricated into data</h2>
 * A probe that cannot reach the aircraft, cannot bind, or gets no {@code AUTOPILOT_VERSION} returns
 * a profile with {@code complete = false} and a populated {@code incompleteReason} — never a
 * plausible-looking profile with zeros in it. A parameter that was not answered has <b>no</b> entry
 * in {@code parameters}; it is not a reading of {@code 0}.
 */
public final class MavlinkVehicleConfigurator implements VehicleConfigPort {

    private static final System.Logger LOG = System.getLogger(MavlinkVehicleConfigurator.class.getName());

    /** The autopilot component every ArduPilot/PX4 vehicle answers on — same convention the rest of this module uses. */
    private static final int TARGET_COMPONENT_AUTOPILOT = MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT;

    /**
     * Resolves a wire message id to its MAVLink name. ArduPilot's dialect rather than plain common
     * because it is a superset — a name is best-effort by contract ({@code MessageObservation.name}
     * is nullable), so a wider dictionary strictly helps.
     */
    private static final MavlinkDialect DIALECT = new ArdupilotmegaDialect();

    /**
     * How long a probe waits for its passive inventory to mean something when it had to open the
     * socket itself. A borrowed gateway has been counting all along and needs no wait at all.
     */
    private static final Duration MINIMUM_SELF_BOUND_OBSERVATION = Duration.ofSeconds(1);

    /** @see #budget(Duration, int) */
    private static final Duration EXCHANGE_SLACK = Duration.ofSeconds(2);

    private final MavlinkTelemetrySource telemetrySource;
    private final MavlinkSettings settings;

    public MavlinkVehicleConfigurator(MavlinkTelemetrySource telemetrySource) {
        this(telemetrySource, MavlinkSettings.defaults());
    }

    public MavlinkVehicleConfigurator(MavlinkTelemetrySource telemetrySource, MavlinkSettings settings) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public boolean supports(Device device) {
        return telemetrySource.supports(device);
    }

    @Override
    public VehicleProfile probe(String linkKey, Duration window) {
        LinkTarget target = LinkTarget.parse(linkKey);
        Objects.requireNonNull(window, "window must not be null");
        Instant observedAt = Instant.now();
        try (LinkLease lease = lease(target)) {
            if (lease.gateway() == null) {
                return incomplete(linkKey, observedAt, target.sysid(), lease.unavailableReason());
            }
            observe(lease, window);
            Integer sysid = target.sysid() != null ? target.sysid() : soleObservedSysid(lease.gateway());
            if (sysid == null) {
                return incomplete(linkKey, observedAt, null,
                        "no single MAVLink system was observed on " + target.bindKey() + " within " + window
                                + " -- name one explicitly as \"" + target.address() + "#<sysid>\"");
            }
            return buildProfile(linkKey, observedAt, lease, target.withSysid(sysid), window);
        }
    }

    @Override
    public MessageIntervalOutcome requestMessageInterval(String linkKey, int messageId, Duration interval) {
        LinkTarget target = LinkTarget.parse(linkKey).requireSysid("requestMessageInterval");
        Objects.requireNonNull(interval, "interval must not be null");
        try (LinkLease lease = lease(target)) {
            if (lease.gateway() == null) {
                return new MessageIntervalOutcome(messageId, interval, RemediationResultCode.NO_ACK,
                        lease.unavailableReason());
            }
            String unreachable = unreachableReason(lease.gateway(), target);
            if (unreachable != null) {
                return new MessageIntervalOutcome(messageId, interval, RemediationResultCode.NO_ACK, unreachable);
            }
            int retries = settings.onboarding().capabilityRetries();
            MessageIntervalService service = new MessageIntervalService(
                    new CommandService(lease.gateway().sink(), lease.gateway().correlator(),
                            settings.ackTimeout(), retries));
            CommandService.CommandOutcome outcome = await(
                    service.setMessageInterval(target.peerId(), messageId, interval),
                    budget(settings.ackTimeout(), retries));
            if (outcome == null) {
                return new MessageIntervalOutcome(messageId, interval, RemediationResultCode.NO_ACK,
                        "the aircraft did not answer MAV_CMD_SET_MESSAGE_INTERVAL");
            }
            return new MessageIntervalOutcome(messageId, interval, remediationCodeOf(outcome.status()),
                    detailOf(outcome));
        }
    }

    @Override
    public List<ParameterReading> readParams(String linkKey, List<String> parameterNames) {
        LinkTarget target = LinkTarget.parse(linkKey).requireSysid("readParams");
        Objects.requireNonNull(parameterNames, "parameterNames must not be null");
        if (parameterNames.isEmpty()) {
            return List.of();
        }
        try (LinkLease lease = lease(target)) {
            if (lease.gateway() == null || unreachableReason(lease.gateway(), target) != null) {
                return List.of();
            }
            return readInto(lease, target, parameterNames);
        }
    }

    @Override
    public ParameterWriteOutcome writeParam(String linkKey, String parameterName, double value) {
        LinkTarget target = LinkTarget.parse(linkKey).requireSysid("writeParam");
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalArgumentException("parameterName must not be blank");
        }
        try (LinkLease lease = lease(target)) {
            if (lease.gateway() == null) {
                return new ParameterWriteOutcome(parameterName, RemediationResultCode.NO_ACK, null, null,
                        lease.unavailableReason());
            }
            String unreachable = unreachableReason(lease.gateway(), target);
            if (unreachable != null) {
                return new ParameterWriteOutcome(parameterName, RemediationResultCode.NO_ACK, null, null, unreachable);
            }
            ParameterService parameters = parameterService(lease);
            Duration budget = budget(settings.onboarding().parameterTimeout(),
                    settings.onboarding().parameterRetries());

            // Snapshot first: without the previous value a caller has nothing to restore to, and
            // "restore afterwards" is the whole safety story for a Tier-A write (plan §4a).
            ParameterOutcome before = await(parameters.read(target.peerId(), parameterName), budget);
            if (before == null || before.status() != ParameterOutcome.Status.OK) {
                // NO_ACK, not UNSUPPORTED: MAVLink gives an autopilot no way to *refuse* a read of a
                // name it does not have -- it simply says nothing -- so "unsupported", "unreachable"
                // and "lost" are one observation here. Claiming the first would be a guess; the
                // detail carries what is actually known.
                return new ParameterWriteOutcome(parameterName, RemediationResultCode.NO_ACK, null, null,
                        "the aircraft did not report a current value for \"" + parameterName
                                + "\" -- refusing to write a parameter that cannot be restored");
            }
            double previous = before.value().value();

            ParameterOutcome written = await(
                    parameters.write(target.peerId(), parameterName, (float) value, before.value().type()), budget);
            if (written == null || written.status() == ParameterOutcome.Status.NO_REPLY) {
                return new ParameterWriteOutcome(parameterName, RemediationResultCode.NO_ACK, previous, null,
                        "no PARAM_VALUE came back for \"" + parameterName + "\" -- the aircraft's value is unknown");
            }
            double now = written.value().value();
            if (written.status() == ParameterOutcome.Status.MISMATCH) {
                // The aircraft accepted the message and stored something else -- clamped, rounded, or
                // silently refused. DENIED, never ACCEPTED: the caller asked for a value it did not get.
                return new ParameterWriteOutcome(parameterName, RemediationResultCode.DENIED, previous, now,
                        written.detail());
            }
            return new ParameterWriteOutcome(parameterName, RemediationResultCode.ACCEPTED, previous, now,
                    "read back as " + now + " after the write");
        }
    }

    // ---------------------------------------------------------------- probe internals

    private VehicleProfile buildProfile(String linkKey, Instant observedAt, LinkLease lease,
                                        LinkTarget target, Duration window) {
        MavlinkGateway gateway = lease.gateway();
        MavlinkMessageInventory.PeerSnapshot snapshot = gateway.messageInventory().snapshot(target.sysid());
        List<MessageObservation> messages = observationsOf(snapshot);
        Long bytesPerSecond = snapshot == null ? null : snapshot.bytesPerSecond();

        Peer peer = gateway.peers().peer(target.peerId());
        HeartbeatInfo heartbeat = peer == null ? null : peer.heartbeat();
        String firmware = heartbeat == null ? null : MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot());
        String vehicleKind = heartbeat == null ? null : vehicleKind(heartbeat.mavType());

        String unreachable = unreachableReason(gateway, target);
        CapabilityReport capabilities =
                unreachable == null ? requestCapabilities(gateway, target) : noCapabilityReport();
        List<ParameterReading> parameters = unreachable == null
                ? readInto(lease, target, settings.onboarding().probeParameters())
                : List.of();

        String incompleteReason = incompleteReasonFor(messages, capabilities, parameters, window, unreachable);
        return new VehicleProfile(linkKey, observedAt, target.sysid(), firmware,
                capabilities.ok() ? capabilities.firmwareVersion() : null,
                vehicleKind,
                capabilities.ok() ? Long.valueOf(capabilities.raw().capabilities().value()) : null,
                capabilities.ok() ? capabilityFlagNames(capabilities) : List.of(),
                messages, parameters, bytesPerSecond,
                incompleteReason == null, incompleteReason);
    }

    /**
     * A profile is complete only when every one of the three sources answered. Anything less is
     * stated, not smoothed over — plan C7: "probably fine, didn't check" is not representable.
     */
    private String incompleteReasonFor(List<MessageObservation> messages, CapabilityReport capabilities,
                                       List<ParameterReading> parameters, Duration window, String unreachable) {
        List<String> gaps = new ArrayList<>();
        if (messages.isEmpty()) {
            gaps.add("no messages were observed within " + window);
        }
        if (unreachable != null) {
            // One gap, not three: saying "AUTOPILOT_VERSION went unanswered" about a request that was
            // never sent would read as a fact about the aircraft rather than about the link.
            gaps.add(unreachable);
            return String.join("; ", gaps);
        }
        if (!capabilities.ok()) {
            gaps.add("AUTOPILOT_VERSION not answered within " + settings.onboarding().capabilityTimeout());
        }
        if (parameters.isEmpty() && !settings.onboarding().probeParameters().isEmpty()) {
            gaps.add("no parameter read was answered -- this firmware may not support the parameter protocol");
        }
        return gaps.isEmpty() ? null : String.join("; ", gaps);
    }

    /**
     * MAVLink's own forwarding rule — enforced by {@code RoutingFrameSink} — is that a message is
     * only ever routed back to the link an earlier message from that target arrived on. So an
     * aircraft nothing has ever been heard from is not slow to answer; it cannot be <i>asked</i>.
     * Checked before every send so the outcome says which of the two happened, rather than
     * reporting an unsent request as an unanswered one.
     *
     * @return {@code null} when the aircraft is addressable, else why it is not
     */
    private static String unreachableReason(MavlinkGateway gateway, LinkTarget target) {
        if (gateway.peers().peer(target.peerId()) != null) {
            return null;
        }
        return "nothing has been heard from system " + target.sysid() + " component "
                + TARGET_COMPONENT_AUTOPILOT + " on " + target.address()
                + " -- a request was not sent, because MAVLink cannot address a peer whose link is unknown";
    }

    private CapabilityReport requestCapabilities(MavlinkGateway gateway, LinkTarget target) {
        CapabilityService service = new CapabilityService(gateway.sink(), gateway.correlator(),
                settings.onboarding().capabilityTimeout(), settings.onboarding().capabilityRetries());
        CapabilityReport report = await(service.requestAutopilotVersion(target.peerId()),
                budget(settings.onboarding().capabilityTimeout(), settings.onboarding().capabilityRetries()));
        return report == null ? noCapabilityReport() : report;
    }

    private static CapabilityReport noCapabilityReport() {
        return new CapabilityReport(CapabilityReport.Status.NO_REPLY, null, CapabilityReport.Maturity.UNKNOWN,
                java.util.Set.of(), 0, 0, 0, null);
    }

    private static List<String> capabilityFlagNames(CapabilityReport report) {
        return report.capabilities().stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }

    private List<ParameterReading> readInto(LinkLease lease, LinkTarget target, List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        ParameterService parameters = parameterService(lease);
        // All in flight at once, deliberately: an absent name costs a full timeout (MAVLink gives an
        // autopilot no way to say "no such parameter"), and batching would serialise those waits
        // instead of overlapping them. Measured against ArduPilot 4.7, twenty concurrent
        // PARAM_REQUEST_READs lose nothing -- every name that exists comes back.
        Map<String, ParameterOutcome> answered = await(parameters.readAll(target.peerId(), names),
                budget(settings.onboarding().parameterTimeout(), settings.onboarding().parameterRetries()));
        if (answered == null) {
            return List.of();
        }
        // Only what was actually read (plan §5.3) -- an unanswered name has no entry here, never a
        // fabricated zero that a readiness check would then happily evaluate against.
        return answered.values().stream()
                .filter(ParameterOutcome::ok)
                .filter(outcome -> Float.isFinite(outcome.value().value()))
                .map(outcome -> new ParameterReading(outcome.value().name(), outcome.value().value(),
                        typeTagOf(outcome.value().type())))
                .sorted(Comparator.comparing(ParameterReading::name))
                .toList();
    }

    /**
     * {@code ParameterReading.type} is required to be non-blank, and rightly so — a vehicle-reported
     * type tag is the only thing that says whether {@code 1.0} means an integer or a float. But a
     * type byte outside the enum is a value the wire can carry, so it is named as unrecognised
     * rather than silently dropped along with the reading that carries it.
     */
    private static String typeTagOf(MavParamType type) {
        return type == null ? "UNKNOWN" : type.name();
    }

    private ParameterService parameterService(LinkLease lease) {
        return new ParameterService(lease.gateway().sink(), lease.gateway().correlator(),
                settings.onboarding().parameterTimeout(), settings.onboarding().parameterRetries());
    }

    private static List<MessageObservation> observationsOf(MavlinkMessageInventory.PeerSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.messages().stream()
                .map(rate -> new MessageObservation(rate.messageId(), messageName(rate.messageId()),
                        rate.hz(), rate.count()))
                .sorted(Comparator.comparingInt(MessageObservation::messageId))
                .toList();
    }

    /**
     * {@code VfrHud} → {@code VFR_HUD}. The library names its generated classes in CamelCase while
     * the spec (and every requirement row written against it) uses SCREAMING_SNAKE, so the two are
     * bridged here rather than by a hand-maintained table of ~200 entries that would go stale.
     * {@code null} for an id this dialect does not know — the contract allows it, and an
     * unrecognised message still counts toward the inventory.
     */
    private static String messageName(int messageId) {
        Class<?> type = DIALECT.supports(messageId) ? DIALECT.resolve(messageId) : null;
        if (type == null) {
            return null;
        }
        String simpleName = type.getSimpleName();
        StringBuilder name = new StringBuilder(simpleName.length() + 4);
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(simpleName.charAt(i - 1))) {
                name.append('_');
            }
            name.append(Character.toUpperCase(c));
        }
        return name.toString();
    }

    private static String vehicleKind(int mavType) {
        return switch (mavType) {
            case 1 -> "fixed wing";
            case 2 -> "quadcopter";
            case 3 -> "coaxial helicopter";
            case 4 -> "helicopter";
            case 10 -> "ground rover";
            case 11 -> "surface boat";
            case 12 -> "submarine";
            case 13 -> "hexacopter";
            case 14 -> "octocopter";
            case 15 -> "tricopter";
            default -> null;
        };
    }

    private Integer soleObservedSysid(MavlinkGateway gateway) {
        List<Integer> observed = gateway.messageInventory().observedPeers();
        return observed.size() == 1 ? observed.get(0) : null;
    }

    /** A borrowed gateway has been counting all along; a self-bound one has seen nothing yet. */
    private void observe(LinkLease lease, Duration window) {
        if (!lease.selfBound()) {
            return;
        }
        Duration wait = window.compareTo(MINIMUM_SELF_BOUND_OBSERVATION) < 0 ? MINIMUM_SELF_BOUND_OBSERVATION : window;
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private VehicleProfile incomplete(String linkKey, Instant observedAt, Integer sysid, String reason) {
        return new VehicleProfile(linkKey, observedAt, sysid, null, null, null, null, List.of(),
                List.of(), List.of(), null, false, reason);
    }

    // ---------------------------------------------------------------- link leasing

    /**
     * Borrows the gateway already bound to {@code target}, or opens a temporary one. {@link #close}
     * closes only what this lease itself opened — closing a borrowed gateway would tear down a
     * registered device's live telemetry.
     */
    private LinkLease lease(LinkTarget target) {
        MavlinkGateway existing = telemetrySource.gateway(target.bindKey());
        if (existing != null && !existing.isClosed()) {
            return LinkLease.borrowed(existing);
        }
        try {
            return LinkLease.selfBound(new MavlinkGateway(target.host(), target.port(), settings));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "MAVLink onboarding could not bind " + target.address() + ": " + e);
            return LinkLease.unavailable("could not bind " + target.address()
                    + " -- it is held by something other than this app's own MAVLink gateway");
        }
    }

    private record LinkLease(MavlinkGateway gateway, boolean selfBound, String unavailableReason)
            implements AutoCloseable {

        static LinkLease borrowed(MavlinkGateway gateway) {
            return new LinkLease(gateway, false, null);
        }

        static LinkLease selfBound(MavlinkGateway gateway) {
            return new LinkLease(gateway, true, null);
        }

        static LinkLease unavailable(String reason) {
            return new LinkLease(null, false, reason);
        }

        @Override
        public void close() {
            if (selfBound && gateway != null) {
                gateway.close();
            }
        }
    }

    // ---------------------------------------------------------------- link key

    /** {@code "udp://host:port#sysid"} — the pre-registration address the plan's stage 3 works from. */
    private record LinkTarget(String host, int port, Integer sysid) {

        static LinkTarget parse(String linkKey) {
            if (linkKey == null || linkKey.isBlank()) {
                throw new IllegalArgumentException("linkKey must not be blank");
            }
            String rest = linkKey.trim();
            if (!rest.startsWith("udp://")) {
                throw new IllegalArgumentException(
                        "linkKey must look like \"udp://host:port#sysid\", got \"" + linkKey + "\"");
            }
            rest = rest.substring("udp://".length());
            Integer sysid = null;
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                sysid = parseSysid(rest.substring(hash + 1), linkKey);
                rest = rest.substring(0, hash);
            }
            int colon = rest.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "linkKey is missing its port: \"" + linkKey + "\" (expected \"udp://host:port#sysid\")");
            }
            String host = rest.substring(0, colon);
            int port = parsePort(rest.substring(colon + 1), linkKey);
            // A blank host means the wildcard, exactly as MavlinkTelemetrySource's own URI handling
            // already defaults it -- "udp://:14550" and "udp://0.0.0.0:14550" name the same socket.
            return new LinkTarget(host.isBlank() ? MavlinkTelemetrySource.DEFAULT_BIND_HOST : host, port, sysid);
        }

        private static Integer parseSysid(String raw, String linkKey) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value < 1 || value > 255) {
                    throw new IllegalArgumentException("sysid must be 1..255 in \"" + linkKey + "\", got " + value);
                }
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("sysid must be an integer in \"" + linkKey + "\"", e);
            }
        }

        private static int parsePort(String raw, String linkKey) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value < 1 || value > 65_535) {
                    throw new IllegalArgumentException("port must be 1..65535 in \"" + linkKey + "\", got " + value);
                }
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port must be an integer in \"" + linkKey + "\"", e);
            }
        }

        /** Fills in the sysid a probe discovered, so downstream steps address one named aircraft. */
        LinkTarget withSysid(int discovered) {
            return new LinkTarget(host, port, discovered);
        }

        LinkTarget requireSysid(String operation) {
            if (sysid == null) {
                throw new IllegalArgumentException(operation
                        + " needs an explicit sysid -- name it as \"udp://host:port#<sysid>\". Only probe() may"
                        + " omit it, because only probe() is allowed to discover which aircraft is there.");
            }
            return this;
        }

        String bindKey() {
            return MavlinkTelemetrySource.bindKey(host, port);
        }

        String address() {
            return "udp://" + host + ":" + port;
        }

        PeerId peerId() {
            return new PeerId(new SysId(sysid), new CompId(TARGET_COMPONENT_AUTOPILOT));
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * The wall-clock a {@code mavlink-core} exchange may take: one attempt plus every retry, with
     * enough slack for {@code ParameterService}'s foreign-reply extension and for the last attempt's
     * own timeout to fire and resolve the future before this outer wait gives up on it. This wait is
     * a backstop against a hung future, never the thing that decides the outcome — the service's own
     * timeout should always win, and the outcome it produces is the honest one.
     */
    private static Duration budget(Duration perAttempt, int retries) {
        return perAttempt.multipliedBy(retries + 1L).plus(EXCHANGE_SLACK);
    }

    /**
     * Waits out one of {@code mavlink-core}'s futures. The port's methods are blocking by contract,
     * so this is where the asynchronous exchange is joined. Returns {@code null} rather than
     * throwing for the two outcomes that are protocol facts (the budget ran out, the thread was
     * interrupted) — each caller turns that into its own honest, typed answer.
     */
    private static <T> T await(java.util.concurrent.CompletableFuture<T> future, Duration budget) {
        try {
            return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "MAVLink onboarding exchange failed: " + e.getCause());
            return null;
        }
    }

    private static RemediationResultCode remediationCodeOf(CommandService.CommandOutcome.Status status) {
        return switch (status) {
            case ACCEPTED -> RemediationResultCode.ACCEPTED;
            case UNSUPPORTED -> RemediationResultCode.UNSUPPORTED;
            case NO_ACK -> RemediationResultCode.NO_ACK;
            // TEMPORARILY_REJECTED / DENIED / FAILED / CANCELLED / IN_PROGRESS all mean the same thing
            // to a caller deciding whether to move on: the aircraft did not do it. The distinction
            // survives in `detail` rather than being flattened away entirely.
            default -> RemediationResultCode.DENIED;
        };
    }

    private static String detailOf(CommandService.CommandOutcome outcome) {
        return outcome.mavResult() == null ? outcome.status().name() : outcome.mavResult().name();
    }
}
