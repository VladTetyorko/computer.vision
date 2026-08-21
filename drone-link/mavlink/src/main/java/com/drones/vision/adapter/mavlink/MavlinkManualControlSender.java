package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.service.ManualControlService;
import com.drones.mavlink.session.DefaultTxScheduler;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.flight.domain.model.RcChannels;
import com.drones.vision.flight.domain.port.ManualControlLink;
import com.drones.vision.flight.domain.port.ManualControlPort;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link ManualControlPort} implementation sending a persistent, fixed-rate, ack-less MAVLink 2
 * {@code RC_CHANNELS_OVERRIDE} (#70) stream to an aircraft this platform is already ingesting
 * telemetry from (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §3) — the streaming, opposite-shape sibling of
 * {@link MavlinkFlightCommander}'s request→ack one-shots. docs/plans/active/MAVLINK-CORE-PLAN.md
 * W4 rewired the actual send loop onto {@code mavlink-core}'s {@link ManualControlService} (fixed-
 * rate relay, latest-wins mailbox, release burst) — every public rule below is preserved exactly.
 *
 * <h2>Borrows the RX gateway; opens no socket of its own</h2>
 * Unlike {@code MavlinkFeedTransmitter} (which owns an ephemeral socket per feed), this class
 * shares {@code telemetrySource}'s own {@link MavlinkGateway} registry exactly like {@link
 * MavlinkFlightCommander} does: {@link #supports(Device)} delegates straight to {@link
 * MavlinkTelemetrySource#supports(Device)}, and {@link #engage(Device)} resolves the device's
 * current claim (sysid + last-seen UDP source address) via {@link
 * MavlinkTelemetrySource#commandTarget} before ever building a {@link ManualControlService} on top
 * of the gateway's own {@code FrameSink}/{@code PeerDirectory} — so every frame goes out over the
 * hub's shared link, the same {@code host:port} the platform already listens on.
 *
 * <h2>One shared {@link DefaultTxScheduler} per sender, not one thread per link</h2>
 * All engaged links from one {@code MavlinkManualControlSender} instance share a single {@code
 * mavlink-core} {@link DefaultTxScheduler} (two daemon threads total, regardless of how many
 * devices are engaged) — this is the DRY target docs/plans/active/MAVLINK-CORE-PLAN.md §4 named
 * explicitly: the pre-W4 design ran one hand-rolled thread per engaged link.
 *
 * <h2>Reachability rule (same as {@link MavlinkFlightCommander})</h2>
 * {@link #engage(Device)} requires a live source address — "you cannot command what you cannot
 * hear" — checked by this adapter <b>before</b> ever calling into {@code
 * ManualControlService.engage}, so no periodic task starts and the exact pre-existing message
 * survives; {@code null}/never-heard throws {@link IllegalArgumentException}.
 *
 * <h2>Fixed-rate relay + latest-wins mailbox, translation at the L4/L5 boundary</h2>
 * A per-engage {@link ManualControlService} (constructed from this sender's shared scheduler and
 * the resolved device's gateway) drives the actual tick loop; this class's only remaining job is
 * translating {@code vision-flight}'s own {@link RcChannels} into {@code mavlink-core}'s
 * structurally-identical {@link com.drones.mavlink.service.RcChannels} at {@link #send} — the
 * L4/L5 boundary translation {@code mavlink-core}'s own MODULE.md flags as this wave's job (that
 * module cannot import a context type; duplicating the tiny value type there was the deliberate
 * alternative to a shared jar for one record).
 *
 * <h2>v1 scope: channels 1..8 only</h2>
 * {@code mavlink-core}'s {@code ManualControlService} already fixes channels 9..18 to {@code
 * IGNORE} unconditionally and pads a shorter-than-8 frame the same way (docs/plans/done/
 * RC-CONTROL-PHASE1-PLAN.md §5) — unchanged wire behaviour, now enforced one level down.
 *
 * <h2>Cadence settings (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2)</h2>
 * {@code ManualControlService} only exposes a public constructor over {@code
 * MavlinkCoreSettings.Rc} (its own {@code Duration}-taking overload is a package-private test
 * seam, unreachable from here), so this class's own {@link MavlinkSettings.Rc} is translated
 * field-for-field into one at construction — {@code overrideHz} (default 33Hz, clamped to {@code
 * [minOverrideHz, maxOverrideHz]} — default 10/50, per docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §3)
 * and {@code releaseFrames} (default 3). This class's own package-private test seam (a raw tick
 * period in millis, for fast/deterministic tests) is translated the other way — into a
 * permissively-bounded {@code Rc} whose {@code overrideHz} reproduces that exact period.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration, given the same {@link MavlinkTelemetrySource} instance used for real
 * telemetry ingest (same borrowing pattern as {@link MavlinkFlightCommander}/{@code
 * MavlinkHeartbeatScanner}), so it resolves claims against the gateway actually running.
 */
public final class MavlinkManualControlSender implements ManualControlPort {

    private static final System.Logger LOG = System.getLogger(MavlinkManualControlSender.class.getName());

    /** v1 scope: {@code RC_CHANNELS_OVERRIDE} channels 1..8 only (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §5). */
    static final int CHANNEL_COUNT = 8;

    private static final Duration SCHEDULER_CLOSE_JOIN_TIMEOUT = Duration.ofSeconds(5);

    private final MavlinkTelemetrySource telemetrySource;
    private final MavlinkCoreSettings.Rc coreRc;
    private final DefaultTxScheduler scheduler;

    public MavlinkManualControlSender(MavlinkTelemetrySource telemetrySource, MavlinkSettings.Rc rc) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
        Objects.requireNonNull(rc, "rc must not be null");
        this.coreRc = new MavlinkCoreSettings.Rc(rc.overrideHz(), rc.minOverrideHz(), rc.maxOverrideHz(), rc.releaseFrames());
        this.scheduler = new DefaultTxScheduler(SCHEDULER_CLOSE_JOIN_TIMEOUT);
    }

    /** Test-only seam: inject the tick period / release-burst count directly, bypassing settings. */
    MavlinkManualControlSender(MavlinkTelemetrySource telemetrySource, long tickPeriodMillis, int releaseFrameCount) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
        if (tickPeriodMillis < 1) {
            throw new IllegalArgumentException("tickPeriodMillis must be >= 1: " + tickPeriodMillis);
        }
        if (releaseFrameCount < 1) {
            throw new IllegalArgumentException("releaseFrameCount must be >= 1: " + releaseFrameCount);
        }
        // ManualControlService only exposes a public Rc-based constructor (see class javadoc) --
        // reconstruct an Hz value that reproduces this exact period, with a permissive min/max so
        // clampedOverrideHz() never distorts it.
        int overrideHz = (int) Math.max(1L, Math.round(1000.0 / tickPeriodMillis));
        this.coreRc = new MavlinkCoreSettings.Rc(overrideHz, 1, overrideHz, releaseFrameCount);
        this.scheduler = new DefaultTxScheduler(SCHEDULER_CLOSE_JOIN_TIMEOUT);
    }

    @Override
    public boolean supports(Device device) {
        return telemetrySource.supports(device);
    }

    @Override
    public ManualControlLink engage(Device device) {
        Objects.requireNonNull(device, "device must not be null");
        if (!supports(device)) {
            throw new IllegalArgumentException("MavlinkManualControlSender does not support device: " + device);
        }
        String bindKey = telemetrySource.bindKeyFor(device);
        MavlinkGateway.CommandTarget target = telemetrySource.commandTarget(bindKey, device.id());
        if (target == null || target.sourceAddress() == null) {
            throw new IllegalArgumentException("No MAVLink vehicle has ever been heard for device " + device.id()
                    + " -- you cannot command what you cannot hear; make sure its telemetry stream is open and "
                    + "the aircraft is actually transmitting");
        }
        MavlinkGateway gateway = telemetrySource.gateway(bindKey);
        if (gateway == null) {
            throw new IllegalArgumentException("MAVLink gateway for device's stream is no longer open");
        }

        PeerId peerId = new PeerId(new SysId(target.sysid()), new CompId(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT));
        ManualControlService service = new ManualControlService(gateway.sink(), scheduler, gateway.peers(), coreRc);
        ManualControlService.ManualControlLink coreLink = service.engage(peerId);

        LOG.log(System.Logger.Level.INFO, () -> "Engaged MAVLink RC override link for device " + device.id()
                + " (sysid " + target.sysid() + ") at " + coreRc.clampedOverrideHz() + "Hz");
        return new AdapterLink(service, coreLink, device.id(), coreRc.clampedOverrideHz());
    }

    @Override
    public void send(ManualControlLink link, RcChannels channels) {
        Objects.requireNonNull(channels, "channels must not be null");
        AdapterLink adapterLink = asAdapterLink(link);
        adapterLink.service.send(adapterLink.coreLink, toCoreChannels(channels));
    }

    @Override
    public void release(ManualControlLink link) {
        AdapterLink adapterLink = asAdapterLink(link);
        LOG.log(System.Logger.Level.INFO, () -> "Releasing MAVLink RC override link for device " + adapterLink.deviceId);
        adapterLink.service.release(adapterLink.coreLink);
    }

    private static AdapterLink asAdapterLink(ManualControlLink link) {
        Objects.requireNonNull(link, "link must not be null");
        if (!(link instanceof AdapterLink adapterLink)) {
            throw new IllegalArgumentException(
                    "link was not created by MavlinkManualControlSender.engage(): " + link.getClass());
        }
        return adapterLink;
    }

    /** vision-flight's own {@link RcChannels} -> mavlink-core's structurally identical value type (see class javadoc). */
    private static com.drones.mavlink.service.RcChannels toCoreChannels(RcChannels channels) {
        return new com.drones.mavlink.service.RcChannels(channels.microsByChannel());
    }

    /**
     * The opaque handle this port hands back from {@link #engage} — pairs the {@code mavlink-core}
     * {@link ManualControlService} instance used to create the link with its own {@code
     * ManualControlLink}, so later {@link #send}/{@link #release} calls route back through the
     * exact same service instance (required for that service's own link-ownership check).
     */
    private static final class AdapterLink implements ManualControlLink {
        private final ManualControlService service;
        private final ManualControlService.ManualControlLink coreLink;
        private final DeviceId deviceId;
        private final int rateHz;

        AdapterLink(ManualControlService service, ManualControlService.ManualControlLink coreLink, DeviceId deviceId,
                    int rateHz) {
            this.service = service;
            this.coreLink = coreLink;
            this.deviceId = deviceId;
            this.rateHz = rateHz;
        }

        @Override
        public boolean active() {
            return coreLink.active();
        }

        /** The clamped keepalive rate this link was engaged with -- the real number vision-api reports. */
        @Override
        public int rateHz() {
            return rateHz;
        }
    }
}
