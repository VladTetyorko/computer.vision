package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.RcChannels;
import com.drones.vision.domain.port.out.ManualControlLink;
import com.drones.vision.domain.port.out.ManualControlPort;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.common.RcChannelsOverride;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ManualControlPort} implementation sending a persistent, fixed-rate, ack-less MAVLink 2
 * {@code RC_CHANNELS_OVERRIDE} (#70) stream to an aircraft this platform is already ingesting
 * telemetry from (docs/RC-CONTROL-PHASE1-PLAN.md §3) — the streaming, opposite-shape sibling of
 * {@link MavlinkFlightCommander}'s request→ack one-shots.
 *
 * <h2>Borrows the RX gateway; opens no socket of its own</h2>
 * Unlike {@link MavlinkFeedTransmitter} (which owns an ephemeral socket per feed), this class
 * shares {@code telemetrySource}'s own {@link MavlinkSocketHub} registry exactly like {@link
 * MavlinkFlightCommander} does: {@link #supports(Device)} delegates straight to {@link
 * MavlinkTelemetrySource#supports(Device)}, and every send targets the device's current claim
 * (sysid + last-seen UDP source address) via {@link MavlinkTelemetrySource#commandTarget}, writing
 * through {@link MavlinkTelemetrySource#socket}. Sending on the hub's shared socket — the same
 * {@code host:port} the platform already listens on — is deliberate: {@code DatagramSocket#send}
 * is thread-safe, so this class's own sender thread, the hub's read thread, and the occasional
 * {@link MavlinkFlightCommander} write all coexist on it without a second bind.
 *
 * <h2>Reachability rule (same as {@link MavlinkFlightCommander})</h2>
 * {@link #engage(Device)} requires a live source address — "you cannot command what you cannot
 * hear" — or it throws {@link IllegalArgumentException} and never starts a thread.
 *
 * <h2>Fixed-rate sender thread + latest-wins mailbox</h2>
 * {@link #engage(Device)} starts one dedicated daemon thread per link ({@code
 * "mavlink-rc-<deviceId>"}), modeled on {@link MavlinkFeedTransmitter}'s {@code FeedRuntime}. The
 * thread owns a single-slot, latest-wins mailbox (a plain {@code volatile} {@link RcChannels}
 * field, writes serialized against {@link #release} via one monitor — see {@code RcLinkRuntime}):
 * {@link #send} only ever overwrites that slot, never touches the wire itself; the thread reads
 * the newest slot value on every tick and sends it, decoupling the caller's own send rate from the
 * wire's fixed cadence. Before the first real {@link #send}, the slot holds an all-{@link
 * RcChannels#IGNORE IGNORE} frame — a real {@code RC_CHANNELS_OVERRIDE} datagram still goes out
 * every tick (the fixed cadence never pauses), but every channel says "leave this alone", so it
 * has no effect on the aircraft until the caller actually sends real values.
 *
 * <p>Each tick re-resolves the device's current claim rather than caching it once at {@link
 * #engage}-time, so a re-elected sysid or a refreshed source address (both tracked live by {@link
 * MavlinkSocketHub}) is picked up on the very next frame. A vehicle that has gone quiet mid-link
 * (claim lost, hub torn down) is not a fatal error — the thread simply skips that tick (logging a
 * single WARNING on the transition, and an INFO once reachability returns) rather than wedging or
 * dying; this is the "never wedge the JVM" discipline every native/IO seam in this module follows.
 *
 * <h2>Release</h2>
 * {@link #release(ManualControlLink)} writes {@link RcChannels#released(int)} for {@value
 * #CHANNEL_COUNT} channels into the mailbox, gives the sender thread a short window to actually
 * transmit that burst ({@link MavlinkSettings.Rc#releaseFrames()} ticks, default 3 — UDP is lossy,
 * so "a burst" rather than "one frame" is the whole point), then stops the thread with the same
 * CAS/interrupt/bounded-join shutdown every runtime in this module uses. Idempotent: a second
 * {@link #release} on an already-released link is a no-op.
 *
 * <h2>v1 scope: channels 1..8 only</h2>
 * Every frame sets {@code chan1Raw}..{@code chan8Raw} from the mailbox (unset entries — a shorter
 * {@link RcChannels} than 8 — become {@link RcChannels#IGNORE}) and {@code chan9Raw}..{@code
 * chan18Raw} to {@link RcChannels#IGNORE} unconditionally, matching {@code
 * ChannelMap.defaultMap()}'s own ch1..8 scope (docs/RC-CONTROL-PHASE1-PLAN.md §5) and sidestepping
 * the ambiguous extension release sentinel for channels 9..18 (the plan's Open Questions §4).
 *
 * <h2>Cadence settings (docs/LAYERING-REFACTOR-PLAN.md E2)</h2>
 * The fixed send rate and release-burst tick count come from a {@link MavlinkSettings.Rc},
 * defaulted and clamped exactly as docs/RC-CONTROL-PHASE1-PLAN.md §3 pins them: {@code overrideHz}
 * (default 33Hz, see {@link MavlinkSettings.Rc#defaults()}, clamped to {@code [minOverrideHz,
 * maxOverrideHz]} — default 10/50 — via {@link MavlinkSettings.Rc#clampedOverrideHz()}) and {@code
 * releaseFrames} (default 3, any positive value accepted). This replaces the {@code
 * VISION_RC_OVERRIDE_HZ}/{@code VISION_RC_RELEASE_FRAMES} environment variables this class used to
 * read directly at construction — the one place in the repo that bypassed Spring config;
 * {@code vision-app}'s own wiring (a later wave) binds {@code vision.rc.*} into the {@link
 * MavlinkSettings.Rc} passed here.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration, given the same {@link MavlinkTelemetrySource} instance used for real
 * telemetry ingest (same borrowing pattern as {@link MavlinkFlightCommander}/{@code
 * MavlinkHeartbeatScanner}), so it resolves claims against the gateway actually running.
 */
public final class MavlinkManualControlSender implements ManualControlPort {

    private static final System.Logger LOG = System.getLogger(MavlinkManualControlSender.class.getName());

    /** v1 scope: {@code RC_CHANNELS_OVERRIDE} channels 1..8 only (docs/RC-CONTROL-PHASE1-PLAN.md §5). */
    static final int CHANNEL_COUNT = 8;

    private static final RcChannels ALL_IGNORE =
            new RcChannels(Collections.nCopies(CHANNEL_COUNT, RcChannels.IGNORE));

    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 5_000L;

    private final MavlinkTelemetrySource telemetrySource;
    private final long tickPeriodMillis;
    private final int releaseFrameCount;

    public MavlinkManualControlSender(MavlinkTelemetrySource telemetrySource, MavlinkSettings.Rc rc) {
        this(telemetrySource, tickPeriodMillisFromRc(Objects.requireNonNull(rc, "rc must not be null")), rc.releaseFrames());
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
        this.tickPeriodMillis = tickPeriodMillis;
        this.releaseFrameCount = releaseFrameCount;
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
        MavlinkSocketHub.CommandTarget target = telemetrySource.commandTarget(bindKey, device.id());
        if (target == null || target.sourceAddress() == null) {
            throw new IllegalArgumentException("No MAVLink vehicle has ever been heard for device " + device.id()
                    + " -- you cannot command what you cannot hear; make sure its telemetry stream is open and "
                    + "the aircraft is actually transmitting");
        }

        RcLinkRuntime link = new RcLinkRuntime(telemetrySource, bindKey, device.id(), tickPeriodMillis, releaseFrameCount);
        link.start();
        LOG.log(System.Logger.Level.INFO, () -> "Engaged MAVLink RC override link for device " + device.id()
                + " (sysid " + target.sysid() + ") at " + Math.round(1000.0 / tickPeriodMillis) + "Hz");
        return link;
    }

    @Override
    public void send(ManualControlLink link, RcChannels channels) {
        Objects.requireNonNull(channels, "channels must not be null");
        asRuntime(link).send(channels);
    }

    @Override
    public void release(ManualControlLink link) {
        RcLinkRuntime runtime = asRuntime(link);
        if (runtime.beginRelease()) {
            LOG.log(System.Logger.Level.INFO, () -> "Releasing MAVLink RC override link for device " + runtime.deviceId);
            runtime.finishRelease();
        }
    }

    private static RcLinkRuntime asRuntime(ManualControlLink link) {
        Objects.requireNonNull(link, "link must not be null");
        if (!(link instanceof RcLinkRuntime runtime)) {
            throw new IllegalArgumentException(
                    "link was not created by MavlinkManualControlSender.engage(): " + link.getClass());
        }
        return runtime;
    }

    /** Converts the settings' clamped Hz into a tick period, mirroring the env-var-era conversion. */
    private static long tickPeriodMillisFromRc(MavlinkSettings.Rc rc) {
        return Math.max(1L, Math.round(1000.0 / rc.clampedOverrideHz()));
    }

    /**
     * One engaged link's sender thread + latest-wins mailbox, modeled on {@code
     * MavlinkFeedTransmitter.FeedRuntime}. Re-resolves the device's current claim on every tick
     * (see class javadoc) rather than caching it once, and sends on the hub's shared socket rather
     * than a socket of its own.
     */
    private static final class RcLinkRuntime implements ManualControlLink {
        private final MavlinkTelemetrySource telemetrySource;
        private final String bindKey;
        private final DeviceId deviceId;
        private final long tickPeriodMillis;
        private final int releaseFrameCount;

        private final Object mailboxLock = new Object();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile RcChannels mailbox = ALL_IGNORE;
        private volatile Thread senderThread;

        RcLinkRuntime(MavlinkTelemetrySource telemetrySource, String bindKey, DeviceId deviceId,
                      long tickPeriodMillis, int releaseFrameCount) {
            this.telemetrySource = telemetrySource;
            this.bindKey = bindKey;
            this.deviceId = deviceId;
            this.tickPeriodMillis = tickPeriodMillis;
            this.releaseFrameCount = releaseFrameCount;
        }

        void start() {
            senderThread = new Thread(this::runSendLoop, "mavlink-rc-" + deviceId.value());
            senderThread.setDaemon(true);
            senderThread.start();
        }

        /** Non-blocking, latest-wins: a no-op once this link has begun releasing. */
        void send(RcChannels channels) {
            synchronized (mailboxLock) {
                if (!closed.get()) {
                    mailbox = channels;
                }
            }
        }

        @Override
        public boolean active() {
            Thread thread = senderThread;
            return !closed.get() && thread != null && thread.isAlive();
        }

        /**
         * First half of release: idempotently marks this link closed (blocking out any further
         * {@link #send}) and queues the release-sentinel burst onto the mailbox.
         *
         * @return {@code true} the first time this is called on a given link; {@code false} on
         *         every subsequent call (idempotent no-op)
         */
        boolean beginRelease() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            synchronized (mailboxLock) {
                mailbox = RcChannels.released(CHANNEL_COUNT);
            }
            return true;
        }

        /** Second half of release: give the sender thread time to emit the burst, then stop it. */
        void finishRelease() {
            sleepMillis(releaseFrameCount * tickPeriodMillis);
            Thread thread = senderThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
                try {
                    thread.join(CLOSE_JOIN_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * Runs until interrupted by {@link #finishRelease()} — {@link #closed} alone does not stop
         * this loop, since {@link #beginRelease()} deliberately leaves it running long enough to
         * transmit the release burst it just queued onto the mailbox.
         */
        private void runSendLoop() {
            long tickPeriodNanos = TimeUnit.MILLISECONDS.toNanos(tickPeriodMillis);
            long nextTickNanos = System.nanoTime();
            boolean reachable = true;
            while (!Thread.currentThread().isInterrupted()) {
                reachable = sendOneFrame(reachable);
                nextTickNanos += tickPeriodNanos;
                long sleepNanos = nextTickNanos - System.nanoTime();
                if (sleepNanos > 0) {
                    sleepMillis(sleepNanos / 1_000_000L + 1);
                }
            }
        }

        private boolean sendOneFrame(boolean wasReachable) {
            MavlinkSocketHub.CommandTarget target = telemetrySource.commandTarget(bindKey, deviceId);
            DatagramSocket socket = telemetrySource.socket(bindKey);
            if (target == null || target.sourceAddress() == null || socket == null) {
                if (wasReachable) {
                    LOG.log(System.Logger.Level.WARNING, "MAVLink RC override link for device " + deviceId
                            + " lost its reachable target; override frames paused until the vehicle is heard again");
                }
                return false;
            }
            try {
                RcChannelsOverride override = buildOverride(target.sysid(), mailbox);
                MavlinkConnection connection = MavlinkConnection.create(InputStream.nullInputStream(),
                        new MavlinkUdpOutputStream(socket, target.sourceAddress().getAddress(),
                                target.sourceAddress().getPort()));
                connection.send2(MavlinkFlightCommander.COMMANDER_SYSTEM_ID,
                        MavlinkFlightCommander.COMMANDER_COMPONENT_ID, override);
                if (!wasReachable) {
                    LOG.log(System.Logger.Level.INFO, "MAVLink RC override link for device " + deviceId
                            + " regained its reachable target (sysid " + target.sysid() + "); resuming override frames");
                }
                return true;
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to send RC_CHANNELS_OVERRIDE for device " + deviceId, e);
                return wasReachable;
            }
        }

        private static RcChannelsOverride buildOverride(int sysid, RcChannels channels) {
            return RcChannelsOverride.builder()
                    .targetSystem(sysid)
                    .targetComponent(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT)
                    .chan1Raw(channelOrIgnore(channels, 1))
                    .chan2Raw(channelOrIgnore(channels, 2))
                    .chan3Raw(channelOrIgnore(channels, 3))
                    .chan4Raw(channelOrIgnore(channels, 4))
                    .chan5Raw(channelOrIgnore(channels, 5))
                    .chan6Raw(channelOrIgnore(channels, 6))
                    .chan7Raw(channelOrIgnore(channels, 7))
                    .chan8Raw(channelOrIgnore(channels, 8))
                    .chan9Raw(RcChannels.IGNORE)
                    .chan10Raw(RcChannels.IGNORE)
                    .chan11Raw(RcChannels.IGNORE)
                    .chan12Raw(RcChannels.IGNORE)
                    .chan13Raw(RcChannels.IGNORE)
                    .chan14Raw(RcChannels.IGNORE)
                    .chan15Raw(RcChannels.IGNORE)
                    .chan16Raw(RcChannels.IGNORE)
                    .chan17Raw(RcChannels.IGNORE)
                    .chan18Raw(RcChannels.IGNORE)
                    .build();
        }

        /** Channels beyond {@code channels}' own length (a v1 map may send fewer than 8) read as {@link RcChannels#IGNORE}. */
        private static int channelOrIgnore(RcChannels channels, int oneBased) {
            return oneBased <= channels.microsByChannel().size() ? channels.channel(oneBased) : RcChannels.IGNORE;
        }

        private static void sleepMillis(long millis) {
            try {
                Thread.sleep(Math.max(0, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
