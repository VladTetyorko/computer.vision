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
import java.util.function.LongSupplier;

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
 * <h2>Two rates, not one: a ceiling and a keepalive</h2>
 * A single fixed rate used to serve as both "how often we transmit" and "how long a new stick
 * position waits", which cost a frame up to a full period of dead time for no protocol reason
 * (docs/plans/done/RC-LATENCY-PLAN.md §1). The rate is now split:
 * <ul>
 *   <li><b>{@code coalescePeriod}</b> ({@code 1/maxOverrideHz}) — the wire <em>ceiling</em>. Never
 *       transmit faster than this, however fast the caller writes.</li>
 *   <li><b>{@code keepalivePeriod}</b> ({@code 1/clampedOverrideHz}) — the wire <em>floor</em>. An
 *       unchanged mailbox still transmits this often, so an aircraft never sees a gap long enough
 *       to expire its own override timeout.</li>
 * </ul>
 * A write that arrives at least {@code coalescePeriod} after the last transmit is pushed to the
 * wire <em>immediately</em>, via a {@link TxScheduler#submit one-shot}, instead of waiting for the
 * next tick. The periodic task therefore stops being the send loop and becomes the keepalive: it
 * transmits only when the mailbox is dirty or the floor is due, so the combined wire rate is
 * bounded by the ceiling rather than being the sum of the two paths.
 *
 * <h2>Re-resolution every tick, never cached</h2>
 * A peer's {@code sourceAddress} refreshes on every inbound datagram and its claim can (rarely)
 * re-elect (project policy — plan §3.4). Every {@link RcLinkRuntime} tick re-reads
 * {@link PeerDirectory#peer} fresh; caching the target at {@link #engage} would let the sender keep
 * transmitting to a stale address after the vehicle moves. A tick that finds the target currently
 * unknown <b>pauses</b> — logged once on the transition, not fatal and not a busy-spin.
 *
 * <h2>Every channel 1..16 the caller populates reaches the wire</h2>
 * Channels 9..16 (MAVLink's "extension" channels) used to be hardcoded to {@link RcChannels#IGNORE}
 * regardless of what a caller sent — an operator's CH9 binding was silently dropped on every frame
 * (docs/plans/active/FLEET-RADIO-PLAN.md F3). {@link RcLinkRuntime#buildFrame} now populates every
 * channel from the mailbox via {@link RcChannels#wireValue(int)}, which also resolves the
 * extension-channel sentinel asymmetry (F4: channels 9..16 use {@code 65534} for release, not the
 * {@code 0} channels 1..8 use). Channels 17/18 are always {@link RcChannels#IGNORE} — ArduPilot does
 * not read them, and {@link RcChannels} itself refuses to carry a value there (F17).
 */
public final class ManualControlService {

    private static final System.Logger LOG = System.getLogger(ManualControlService.class.getName());

    /**
     * Highest RC channel this service populates from a caller-supplied {@link RcChannels} — mirrors
     * {@link RcChannels#MAX_CHANNELS}. {@code RC_CHANNELS_OVERRIDE} itself extends to 18, but
     * ArduPilot reads only 1..16 (docs/plans/active/FLEET-RADIO-PLAN.md F17); channels 17/18 are
     * always sent as {@link RcChannels#IGNORE} in {@link RcLinkRuntime#buildFrame} since {@link
     * RcChannels} itself now refuses to carry a value there.
     */
    private static final int MAX_CHANNELS = RcChannels.MAX_CHANNELS;

    private final FrameSink sink;
    private final TxScheduler scheduler;
    private final PeerDirectory peers;
    private final Duration coalescePeriod;
    private final Duration keepalivePeriod;
    private final int releaseFrames;
    private final LongSupplier nanoClock;

    public ManualControlService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, MavlinkCoreSettings.Rc rc) {
        this(sink, scheduler, peers,
                Duration.ofMillis(1000L / Objects.requireNonNull(rc, "rc").maxOverrideHz()),
                Duration.ofMillis(1000L / rc.clampedOverrideHz()),
                rc.releaseFrames(),
                System::nanoTime);
    }

    /**
     * Test seam: one explicit period serving as both ceiling and keepalive, bypassing the Hz clamp
     * — reproduces the single-rate behaviour this class had before the split, plus
     * transmit-on-arrival within that same rate.
     */
    ManualControlService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, Duration tickPeriod, int releaseFrames) {
        this(sink, scheduler, peers, tickPeriod, tickPeriod, releaseFrames, System::nanoTime);
    }

    /**
     * Test seam: as above, plus a deterministic monotonic clock, so a manually driven
     * {@link TxScheduler} can step past the ceiling/keepalive without real sleeps.
     */
    ManualControlService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, Duration tickPeriod,
                         int releaseFrames, LongSupplier nanoClock) {
        this(sink, scheduler, peers, tickPeriod, tickPeriod, releaseFrames, nanoClock);
    }

    private ManualControlService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers,
                                 Duration coalescePeriod, Duration keepalivePeriod, int releaseFrames,
                                 LongSupplier nanoClock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.peers = Objects.requireNonNull(peers, "peers");
        requirePositive(coalescePeriod, "coalescePeriod");
        requirePositive(keepalivePeriod, "keepalivePeriod");
        if (coalescePeriod.compareTo(keepalivePeriod) > 0) {
            throw new IllegalArgumentException("coalescePeriod must not exceed keepalivePeriod (a ceiling below "
                    + "the floor would starve the keepalive), got " + coalescePeriod + " > " + keepalivePeriod);
        }
        this.coalescePeriod = coalescePeriod;
        this.keepalivePeriod = keepalivePeriod;
        if (releaseFrames < 1) {
            throw new IllegalArgumentException("releaseFrames must be >= 1, got " + releaseFrames);
        }
        this.releaseFrames = releaseFrames;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    private static void requirePositive(Duration period, String name) {
        Objects.requireNonNull(period, name);
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + period);
        }
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
     * Overwrites {@code link}'s latest-wins mailbox with {@code channels} and, when the wire
     * ceiling has already elapsed, schedules an immediate {@link TxScheduler#submit one-shot}
     * transmit rather than letting the frame wait for the next tick (see the class javadoc's
     * two-rates section). Non-blocking, and it still never touches the wire on the caller's own
     * thread; a silent no-op once {@code link} has begun releasing.
     *
     * @throws IllegalArgumentException if {@code link} was not created by this service's own {@link #engage}
     */
    public void send(ManualControlLink link, RcChannels channels) {
        Objects.requireNonNull(channels, "channels");
        runtimeOf(link).setChannels(channels);
    }

    /**
     * Writes an all-{@link RcChannels#RELEASE} frame into {@code link}'s mailbox, then blocks the
     * <b>caller's</b> thread long enough ({@code releaseFrames * coalescePeriod}) for the still-ticking
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
        private final String taskName;
        private final TxScheduler.Handle handle;
        private final Object lock = new Object();
        private final long coalesceNanos = coalescePeriod.toNanos();
        private final long keepaliveNanos = keepalivePeriod.toNanos();

        private RcChannels mailbox = RcChannels.allIgnore(MAX_CHANNELS); // guarded by lock
        private boolean dirty = false; // guarded by lock -- mailbox holds a value not yet transmitted
        private boolean wakePending = false; // guarded by lock -- a one-shot is already queued
        private long lastTransmitNanos; // guarded by lock -- last frame handed to the sink, or dropped unreachable
        private boolean releasing = false; // guarded by lock
        private int releaseBudgetRemaining = 0; // guarded by lock
        private volatile boolean lastReachable = true;

        RcLinkRuntime(PeerId target) {
            this.target = target;
            this.taskName = "mavlink-rc-" + target;
            // Backdated so the very first tick transmits at once, as this class has always done.
            this.lastTransmitNanos = nanoClock.getAsLong() - keepaliveNanos;
            this.handle = scheduler.repeat(taskName, coalescePeriod, this::transmitIfDue);
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
            boolean wake;
            synchronized (lock) {
                if (releasing) {
                    return; // silent no-op once releasing has begun
                }
                mailbox = channels;
                dirty = true;
                wake = !wakePending && nanoClock.getAsLong() - lastTransmitNanos >= coalesceNanos;
                if (wake) {
                    wakePending = true;
                }
            }
            if (wake) {
                // Outside the lock, always: never call foreign code holding this link's monitor.
                scheduler.submit(taskName, this::coalescedWake);
            }
        }

        /**
         * A {@link TxScheduler#submit} one-shot. The ceiling had already elapsed when the frame was
         * written, so it reaches the wire now instead of waiting out the rest of a tick it had no
         * protocol reason to wait for.
         */
        private void coalescedWake() {
            synchronized (lock) {
                wakePending = false;
            }
            transmitIfDue();
        }

        void release() {
            RcChannels releaseFrame;
            synchronized (lock) {
                if (releasing) {
                    return; // idempotent
                }
                releasing = true;
                mailbox = RcChannels.released(MAX_CHANNELS);
                releaseBudgetRemaining = releaseFrames;
                releaseFrame = mailbox;
            }
            LOG.log(Level.INFO, "Releasing manual control for " + target + " -- " + releaseFrames + " release frame(s)");
            sleepUninterruptibly(releaseFrames * coalescePeriod.toMillis());
            handle.close();
        }

        /**
         * Runs on whatever thread {@link TxScheduler} drives — either the periodic keepalive tick or
         * a {@link #coalescedWake} one-shot. The whole decision is taken under {@link #lock}, so the
         * two paths can never double-send and the combined wire rate stays bounded by the ceiling.
         */
        private void transmitIfDue() {
            RcChannels toSend;
            synchronized (lock) {
                long now = nanoClock.getAsLong();
                if (releasing) {
                    if (releaseBudgetRemaining <= 0) {
                        return; // burst already spent -- true silence
                    }
                    releaseBudgetRemaining--;
                } else if (dirty) {
                    if (now - lastTransmitNanos < coalesceNanos) {
                        return; // ceiling: something reached the wire too recently
                    }
                } else if (now - lastTransmitNanos < keepaliveNanos) {
                    return; // nothing new to say and the floor is not due yet
                }
                dirty = false;
                lastTransmitNanos = now;
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

        /**
         * Every channel the caller populated (1..{@link RcChannels#MAX_CHANNELS}) reaches the wire
         * through {@link RcChannels#wireValue(int)} — including the extension channels 9..16, which
         * this class used to hardcode to {@link RcChannels#IGNORE} unconditionally
         * (docs/plans/active/FLEET-RADIO-PLAN.md F3: an operator's CH9 binding was silently dropped
         * here). {@code wireValue} is also what re-encodes a domain {@link RcChannels#RELEASE} as
         * {@link RcChannels#EXTENSION_RELEASE} for those same channels (F4), so this method never has
         * to know that distinction itself. Channels 17/18 do not exist for ArduPilot (F17) and {@link
         * RcChannels} itself now refuses to carry a value past 16 — always {@link
         * RcChannels#IGNORE}, unconditionally, same as before.
         */
        private RcChannelsOverride buildFrame(RcChannels channels) {
            return RcChannelsOverride.builder()
                    .targetSystem(target.system().value())
                    .targetComponent(target.component().value())
                    .chan1Raw(channels.wireValue(1))
                    .chan2Raw(channels.wireValue(2))
                    .chan3Raw(channels.wireValue(3))
                    .chan4Raw(channels.wireValue(4))
                    .chan5Raw(channels.wireValue(5))
                    .chan6Raw(channels.wireValue(6))
                    .chan7Raw(channels.wireValue(7))
                    .chan8Raw(channels.wireValue(8))
                    .chan9Raw(channels.wireValue(9))
                    .chan10Raw(channels.wireValue(10))
                    .chan11Raw(channels.wireValue(11))
                    .chan12Raw(channels.wireValue(12))
                    .chan13Raw(channels.wireValue(13))
                    .chan14Raw(channels.wireValue(14))
                    .chan15Raw(channels.wireValue(15))
                    .chan16Raw(channels.wireValue(16))
                    .chan17Raw(RcChannels.IGNORE)
                    .chan18Raw(RcChannels.IGNORE)
                    .build();
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
