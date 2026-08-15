package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.Peer;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.mavlink.session.TxScheduler;

import io.dronefleet.mavlink.common.RcChannelsOverride;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MAVLink's manual-control microservice (plan §2.2, Family B — streaming): a fixed-rate
 * {@code RC_CHANNELS_OVERRIDE} (#70) relay, latest-wins mailbox, no acknowledgement at all. Ported
 * from {@code adapter-mavlink}'s {@code MavlinkManualControlSender} (see that module's MODULE.md for
 * the behaviour this preserves) onto this module's own {@link TxScheduler} instead of a hand-rolled
 * per-link thread.
 *
 * <h2>ISP: this class cannot express an ack wait</h2>
 * Constructed from {@link FrameSink} + {@link TxScheduler} + {@link PeerDirectory} only — no
 * {@link com.drones.mavlink.session.Correlator}. Manual control is genuinely fire-and-forget on the
 * wire (the spec defines no rate and no failsafe-on-silence for RC override — plan §2.2); giving this
 * class a way to await a reply would let a future change quietly grow a request/response dependency
 * where the protocol has none.
 *
 * <h2>Re-resolution every tick, never cached</h2>
 * A peer's {@code sourceAddress} refreshes on every inbound datagram and its claim can (rarely)
 * re-elect (project policy — plan §3.4). Every {@link RcLinkRuntime} tick re-reads
 * {@link PeerDirectory#peer} fresh; caching the target at {@link #engage} would let the sender keep
 * transmitting to a stale address after the vehicle moves. A tick that finds the target currently
 * unknown <b>pauses</b> — logged once on the transition, not fatal and not a busy-spin.
 */
public final class ManualControlService {

    private static final System.Logger LOG = System.getLogger(ManualControlService.class.getName());

    /** {@code RC_CHANNELS_OVERRIDE} only ever carries 18 v1+extension channels. */
    private static final int CHANNEL_COUNT = 18;

    /** This class only ever populates channels 1..8 — see the class javadoc's v1-scope note. */
    private static final int V1_CHANNEL_COUNT = 8;

    private final FrameSink sink;
    private final TxScheduler scheduler;
    private final PeerDirectory peers;
    private final Duration tickPeriod;
    private final int releaseFrames;

    public ManualControlService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, MavlinkCoreSettings.Rc rc) {
        this(sink, scheduler, peers, Duration.ofMillis(1000L / Objects.requireNonNull(rc, "rc").clampedOverrideHz()),
                rc.releaseFrames());
    }

    /** Test seam: an explicit tick period/release-frame count, bypassing the Hz clamp. */
    ManualControlService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, Duration tickPeriod, int releaseFrames) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.peers = Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(tickPeriod, "tickPeriod");
        if (tickPeriod.isZero() || tickPeriod.isNegative()) {
            throw new IllegalArgumentException("tickPeriod must be positive, got " + tickPeriod);
        }
        this.tickPeriod = tickPeriod;
        if (releaseFrames < 1) {
            throw new IllegalArgumentException("releaseFrames must be >= 1, got " + releaseFrames);
        }
        this.releaseFrames = releaseFrames;
    }

    /**
     * Starts a fixed-rate relay to {@code target}. Fails fast, starting no periodic task at all, if
     * {@code target} has never been heard from — "you cannot command what you cannot hear" (the same
     * reachability rule {@code RoutingFrameSink} enforces on every send).
     *
     * @throws IllegalArgumentException if {@code target} is not (yet) known to {@link PeerDirectory}
     */
    public ManualControlLink engage(PeerId target) {
        Objects.requireNonNull(target, "target");
        if (peers.peer(target) == null) {
            throw new IllegalArgumentException(
                    "Cannot engage manual control for " + target + " -- cannot command what you cannot hear. "
                            + "It has not been heard from on any link yet.");
        }
        return new RcLinkRuntime(target);
    }

    /**
     * Overwrites {@code target}'s latest-wins mailbox with {@code channels}. Non-blocking, never
     * touches the wire itself; a silent no-op once {@code link} has begun releasing.
     *
     * @throws IllegalArgumentException if {@code link} was not created by this service's own {@link #engage}
     */
    public void send(ManualControlLink link, RcChannels channels) {
        Objects.requireNonNull(channels, "channels");
        runtimeOf(link).setChannels(channels);
    }

    /**
     * Writes an all-{@link RcChannels#RELEASE} frame into {@code link}'s mailbox, then blocks the
     * <b>caller's</b> thread long enough ({@code releaseFrames * tickPeriod}) for the still-ticking
     * relay to actually transmit that burst before the periodic task is stopped. Idempotent — a
     * second {@code release} on an already-releasing link is an immediate no-op.
     *
     * @throws IllegalArgumentException if {@code link} was not created by this service's own {@link #engage}
     */
    public void release(ManualControlLink link) {
        runtimeOf(link).release();
    }

    private RcLinkRuntime runtimeOf(ManualControlLink link) {
        Objects.requireNonNull(link, "link");
        if (!(link instanceof RcLinkRuntime runtime) || runtime.owner() != this) {
            throw new IllegalArgumentException("ManualControlLink was not created by this ManualControlService");
        }
        return runtime;
    }

    /** An opaque, engaged manual-control relay. */
    public interface ManualControlLink {
        /** {@code false} once {@link #release} has been called on this link. */
        boolean active();
    }

    private final class RcLinkRuntime implements ManualControlLink {

        private final PeerId target;
        private final TxScheduler.Handle handle;
        private final Object lock = new Object();

        private RcChannels mailbox = RcChannels.allIgnore(V1_CHANNEL_COUNT); // guarded by lock
        private boolean releasing = false; // guarded by lock
        private int releaseBudgetRemaining = 0; // guarded by lock
        private volatile boolean lastReachable = true;

        RcLinkRuntime(PeerId target) {
            this.target = target;
            this.handle = scheduler.repeat("mavlink-rc-" + target, tickPeriod, this::tick);
        }

        ManualControlService owner() {
            return ManualControlService.this;
        }

        @Override
        public boolean active() {
            synchronized (lock) {
                return !releasing;
            }
        }

        void setChannels(RcChannels channels) {
            synchronized (lock) {
                if (releasing) {
                    return; // silent no-op once releasing has begun
                }
                mailbox = channels;
            }
        }

        void release() {
            RcChannels releaseFrame;
            synchronized (lock) {
                if (releasing) {
                    return; // idempotent
                }
                releasing = true;
                mailbox = RcChannels.released(V1_CHANNEL_COUNT);
                releaseBudgetRemaining = releaseFrames;
                releaseFrame = mailbox;
            }
            LOG.log(Level.INFO, "Releasing manual control for " + target + " -- " + releaseFrames + " release frame(s)");
            sleepUninterruptibly(releaseFrames * tickPeriod.toMillis());
            handle.close();
        }

        /** Runs on whatever thread {@link TxScheduler} drives; never overlaps itself (fixed-rate contract). */
        private void tick() {
            RcChannels toSend;
            synchronized (lock) {
                if (releasing) {
                    if (releaseBudgetRemaining <= 0) {
                        return; // burst already spent -- true silence
                    }
                    releaseBudgetRemaining--;
                }
                toSend = mailbox;
            }
            Peer current = peers.peer(target);
            if (current == null) {
                if (lastReachable) {
                    LOG.log(Level.WARNING, "Manual control target " + target + " is no longer reachable -- pausing, not failing");
                    lastReachable = false;
                }
                return;
            }
            if (!lastReachable) {
                LOG.log(Level.INFO, "Manual control target " + target + " is reachable again");
                lastReachable = true;
            }
            try {
                sink.send(buildFrame(toSend), target);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to send RC_CHANNELS_OVERRIDE to " + target, e);
            }
        }

        private RcChannelsOverride buildFrame(RcChannels channels) {
            RcChannelsOverride.Builder builder = RcChannelsOverride.builder()
                    .targetSystem(target.system().value())
                    .targetComponent(target.component().value())
                    .chan1Raw(channels.channelOrIgnore(1))
                    .chan2Raw(channels.channelOrIgnore(2))
                    .chan3Raw(channels.channelOrIgnore(3))
                    .chan4Raw(channels.channelOrIgnore(4))
                    .chan5Raw(channels.channelOrIgnore(5))
                    .chan6Raw(channels.channelOrIgnore(6))
                    .chan7Raw(channels.channelOrIgnore(7))
                    .chan8Raw(channels.channelOrIgnore(8));
            // Channels 9..18 are always IGNORE -- v1 scope, see class javadoc.
            for (int channel = V1_CHANNEL_COUNT + 1; channel <= CHANNEL_COUNT; channel++) {
                setExtensionChannel(builder, channel);
            }
            return builder.build();
        }

        private void setExtensionChannel(RcChannelsOverride.Builder builder, int channel) {
            switch (channel) {
                case 9 -> builder.chan9Raw(RcChannels.IGNORE);
                case 10 -> builder.chan10Raw(RcChannels.IGNORE);
                case 11 -> builder.chan11Raw(RcChannels.IGNORE);
                case 12 -> builder.chan12Raw(RcChannels.IGNORE);
                case 13 -> builder.chan13Raw(RcChannels.IGNORE);
                case 14 -> builder.chan14Raw(RcChannels.IGNORE);
                case 15 -> builder.chan15Raw(RcChannels.IGNORE);
                case 16 -> builder.chan16Raw(RcChannels.IGNORE);
                case 17 -> builder.chan17Raw(RcChannels.IGNORE);
                case 18 -> builder.chan18Raw(RcChannels.IGNORE);
                default -> throw new IllegalStateException("unreachable: channel " + channel);
            }
        }

        private void sleepUninterruptibly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
