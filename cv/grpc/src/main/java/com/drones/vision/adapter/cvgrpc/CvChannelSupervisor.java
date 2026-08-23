package com.drones.vision.adapter.cvgrpc;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single owner of "is the cv-service channel reachable, and if not, keep trying at a cadence
 * <em>we</em> choose" (docs/plans/active/CV-RECONNECT-PLAN.md &sect;2). One instance per {@link
 * ManagedChannel} shared by every cv-service port ({@link GrpcDetectionPort} and friends) — not a
 * port itself, a read model + reconnect loop that {@link GrpcDetectionPort#detect} gates on.
 *
 * <h2>Why this exists</h2>
 * A fail-fast gRPC RPC issued while a channel is in {@link ConnectivityState#TRANSIENT_FAILURE}
 * returns the cached failure immediately — it does <b>not</b> trigger a new connect attempt. Left
 * alone, only the channel's own reconnect backoff (which escalates towards ~120s) ever retries, so
 * recovery latency after cv-service comes back up is unbounded and depends entirely on where in that
 * escalation the service happened to restart. This class forces a bounded, capped retry cadence via
 * {@link ManagedChannel#resetConnectBackoff()} instead, and gates {@code detect()} so a known outage
 * fails fast (stackless, no wasted frame encode) rather than flooding the log with an identical stack
 * trace per probe.
 *
 * <h2>The gate — sticky-closed until {@code READY}</h2>
 * <pre>
 *   OPEN   --TRANSIENT_FAILURE--&gt;         CLOSED
 *   CLOSED --CONNECTING / IDLE (reconnect churn)--&gt; CLOSED
 *   CLOSED --READY--&gt;                     OPEN
 *   OPEN   --SHUTDOWN--&gt;                  CLOSED (terminal)
 *   CLOSED --SHUTDOWN--&gt;                  CLOSED (terminal)
 * </pre>
 * {@link #available()} is {@code false} iff the gate is closed — it is deliberately <b>not</b> {@code
 * state() == READY}: the gate must open only on {@code READY}, never on {@code CONNECTING}. Every
 * reconnect attempt cycles {@code TRANSIENT_FAILURE -> CONNECTING -> TRANSIENT_FAILURE}; a gate that
 * reopened on {@code CONNECTING} would let one probe through per cycle and reproduce the log flood
 * this class exists to fix, just at a slower rate. The gate starts (and stays) <b>open</b> until the
 * first {@code TRANSIENT_FAILURE} is ever observed, so {@code IDLE}/{@code CONNECTING} during cold
 * start — before cv-service has ever been reached — never blocks a probe. {@code SHUTDOWN} is
 * terminal: a shut-down channel never transitions back to {@code READY}, so once the gate closes on
 * {@code SHUTDOWN} it stays closed for the rest of this supervisor's life, and — unlike a {@code
 * TRANSIENT_FAILURE} outage — no reconnect loop is scheduled for it (see {@link #onShutdown()}).
 * {@link #start()} applies this same transition logic to whatever state the channel is <em>already</em>
 * in before arming the watch (see {@link #start()}'s javadoc), so a supervisor started against a
 * channel that is already {@code TRANSIENT_FAILURE} or already {@code SHUTDOWN} does not wait for a
 * transition to notice.
 *
 * <h2>Bounded recovery arithmetic</h2>
 * While the gate is closed, this class calls {@link ManagedChannel#resetConnectBackoff()} on its own
 * schedule: wait {@link GrpcCvSettings#reconnectInitialBackoff()}, force a reconnect attempt, then
 * double the wait (capped at {@link GrpcCvSettings#reconnectMaxBackoff()}) and repeat. With the
 * shipped defaults (1s / 10s), the worst case from cv-service restarting to the gate reopening is
 * bounded by {@code reconnectMaxBackoff} (10s) — a caller's own probe backoff (e.g. {@code
 * StreamPipeline}'s, capped at 10s) then needs at most one more cycle, for an ~20s bounded worst case
 * against gRPC's own unbounded-in-practice escalation.
 *
 * <h2>Channel lifecycle</h2>
 * This class <b>never</b> shuts down the channel — matching this module's existing convention for
 * every port except {@link GrpcDetectionPort} (see {@link GrpcModelRegistryPort}'s javadoc). {@link
 * #close()} only stops this instance's own watch loop and shuts down its own scheduler.
 *
 * <h2>Threading</h2>
 * Three different execution contexts touch this class's state:
 * <ul>
 *   <li>{@link ManagedChannel#notifyWhenStateChanged} invokes {@link #onStateChange()} on a gRPC
 *   executor thread — its identity is not guaranteed to be the same thread across calls.</li>
 *   <li>The forced-reconnect and outage-heartbeat tasks run on this instance's own single-thread
 *   {@code cv-channel-supervisor} scheduler.</li>
 *   <li>{@link #available()}/{@link #state()}/{@link #outageFor()}/{@link #reconnectAttempts()} are
 *   read from arbitrary caller threads (e.g. {@code GrpcDetectionPort#detect}, called per frame).</li>
 * </ul>
 * The gate flag ({@link #available}) is an {@link AtomicBoolean} so entering/leaving an outage is a
 * single atomic transition regardless of which of those threads observes it first. {@link
 * #reconnectAttempts} is an {@link AtomicLong} for the same reason (read externally, incremented from
 * the scheduler thread).
 *
 * <p><b>The race this class is built to avoid</b>: a reconnect/heartbeat chain scheduled for one
 * outage must not keep running (or log a stale "still unreachable" heartbeat, or call {@code
 * resetConnectBackoff()} pointlessly) after that outage has already ended — but {@code
 * ScheduledFuture#cancel} racing a task that is already mid-execution cannot guarantee that on its
 * own. Every scheduled task therefore closes over the {@link #generation} value current when it was
 * scheduled and re-checks it (against the live {@link #generation}) both before doing any work and
 * before scheduling its own successor; both {@link #onReady} (the gate reopening) and {@link
 * #onShutdown()} (the channel dying instead) bump {@link #generation}, since either one ends whatever
 * outage a live chain was scheduled for. A stale task's checks fail and it quietly does nothing — no
 * lock, no coordination with whatever thread is running it, and no dependence on {@code cancel()}
 * winning its own race.
 */
public final class CvChannelSupervisor implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CvChannelSupervisor.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final ManagedChannel channel;
    private final GrpcCvSettings settings;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** The gate: {@code true} = open (calls allowed). Starts open — see class javadoc, cold start. */
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicLong reconnectAttempts = new AtomicLong();
    /** Bumped on every gate transition; lets a stale scheduled task detect it no longer applies. */
    private final AtomicLong generation = new AtomicLong();
    private volatile long outageStartNanos;
    private volatile ScheduledFuture<?> heartbeatTask;

    public CvChannelSupervisor(ManagedChannel channel, GrpcCvSettings settings) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable r) {
        Thread thread = new Thread(r, "cv-channel-supervisor");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Idempotent. Reads the channel's current state (requesting a connection if it is {@code IDLE},
     * so a cold channel starts connecting immediately rather than waiting for the first {@code
     * detect()} call), applies the gate's transition logic to that state <em>before</em> arming the
     * watch, and then arms it.
     *
     * <p>The apply-before-arm step matters because {@link ManagedChannel#notifyWhenStateChanged} only
     * fires when the state changes <em>away from</em> the state passed to it — a supervisor started
     * against a channel that is already {@code TRANSIENT_FAILURE} (or already {@code SHUTDOWN}) would
     * otherwise leave the gate open (or unclosed) until the channel happened to transition again,
     * which may be a long time or never. This matters in practice because this class does not control
     * its own construction order relative to the channel's — {@code vision-app} wires it as a Spring
     * bean, and this class should be correct regardless of what state the channel is already in by the
     * time {@link #start()} runs, not just for the fresh-{@code IDLE}-channel case today's wiring
     * happens to always produce.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ConnectivityState initial = channel.getState(true);
        applyTransition(initial);
        if (initial != ConnectivityState.SHUTDOWN) {
            channel.notifyWhenStateChanged(initial, this::onStateChange);
        }
    }

    /** {@code false} iff the gate is closed (see class javadoc) — deliberately not {@code state() == READY}. */
    public boolean available() {
        return available.get();
    }

    /** The channel's current {@link ConnectivityState}, read live — never cached. */
    public ConnectivityState state() {
        return channel.getState(false);
    }

    /** How long the current outage has lasted, or {@link Duration#ZERO} if the gate is open. */
    public Duration outageFor() {
        if (available.get()) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(System.nanoTime() - outageStartNanos);
    }

    /** How many forced-reconnect attempts the current (or most recently ended) outage has made. */
    public long reconnectAttempts() {
        return reconnectAttempts.get();
    }

    /**
     * The message {@link GrpcDetectionPort#detect} embeds verbatim in a {@link CvUnavailableException}
     * while the gate is closed — see that exception's javadoc for why its message is its whole
     * contract. Also the operator-facing sentence {@link CvStatusProvider} reports verbatim as
     * {@code SubsystemStatus.detail()} while the gate is closed (docs/plans/done/SYSTEM-STATUS-PLAN.md
     * §4.2) — one sentence, two callers, never reformatted.
     */
    public String describe() {
        return "cv-service at " + channel.authority() + " unreachable (state=" + state() + ", outage="
                + formatDuration(outageFor()) + ", " + reconnectAttempts() + " reconnect attempt(s))";
    }

    /**
     * Called on a gRPC executor thread whenever the channel's connectivity state changes away from
     * whatever it was when this watch was last armed. Re-arms itself with the freshly-read state
     * unless the channel has shut down — a one-shot notification that is not re-armed would silently
     * stop watching altogether.
     */
    private void onStateChange() {
        if (closed.get()) {
            return;
        }
        ConnectivityState current = channel.getState(false);
        if (current == ConnectivityState.IDLE) {
            // Keep an idle channel warm: pull it back into CONNECTING so this supervisor keeps
            // working even with no stream currently open, rather than sitting unwatched until the
            // next detect() call happens to revive it.
            current = channel.getState(true);
        }
        applyTransition(current);
        if (current == ConnectivityState.SHUTDOWN || closed.get()) {
            return; // channel is gone, or this supervisor is closing -- stop watching, no rearm
        }
        channel.notifyWhenStateChanged(current, this::onStateChange);
    }

    /**
     * The one place the gate's {@code READY}/{@code TRANSIENT_FAILURE}/{@code SHUTDOWN} transition
     * logic lives, shared by {@link #start()} (applied to whatever state the channel is already in)
     * and {@link #onStateChange()} (applied to every subsequent observed state). {@code CONNECTING}/
     * {@code IDLE} never affect the gate either way — see class javadoc.
     */
    private void applyTransition(ConnectivityState state) {
        switch (state) {
            case READY -> onReady();
            case TRANSIENT_FAILURE -> onTransientFailure();
            case SHUTDOWN -> onShutdown();
            case CONNECTING, IDLE -> {
                // Reconnect churn, or still cold-starting -- no gate effect.
            }
        }
    }

    private void onReady() {
        if (!available.compareAndSet(false, true)) {
            return; // already open (cold start reaching READY directly, or a redundant notification)
        }
        generation.incrementAndGet(); // orphans any in-flight reconnect/heartbeat chain for this outage
        cancelHeartbeat();
        Duration outageDuration = Duration.ofNanos(System.nanoTime() - outageStartNanos);
        long attempts = reconnectAttempts.get();
        LOG.log(System.Logger.Level.INFO, () -> "cv-service at " + channel.authority()
                + " reachable again after " + formatDuration(outageDuration) + " (" + attempts + " reconnect attempt(s))");
    }

    private void onTransientFailure() {
        if (!available.compareAndSet(true, false)) {
            return; // already mid-outage; the backoff/heartbeat chain already running covers it
        }
        long gen = generation.incrementAndGet();
        outageStartNanos = System.nanoTime();
        reconnectAttempts.set(0);
        LOG.log(System.Logger.Level.WARNING, () -> channel.authority() + ": cv-service unreachable");
        scheduleHeartbeat(gen);
        scheduleReconnect(gen, settings.reconnectInitialBackoff());
    }

    /**
     * The channel itself has been shut down (e.g. {@link GrpcDetectionPort#close()} — or a Spring
     * context tearing down the channel bean directly, since {@code CvWiring} declares the detection
     * port with {@code destroyMethod = ""} so {@code GrpcDetectionPort#close()} is never called and
     * the channel bean's own destruction is what actually shuts the channel down). A shut-down channel
     * is definitionally unreachable and will never transition back to {@code READY}, so the gate
     * closes and stays closed. Unlike a {@code TRANSIENT_FAILURE} outage, no reconnect loop is
     * scheduled here — forcing {@code resetConnectBackoff()} against a permanently dead channel on a
     * timer would be pure waste, since there is nothing left to recover. Bumping {@link #generation}
     * unconditionally also orphans whatever reconnect/heartbeat chain was already running for an
     * in-progress outage, so that chain stops calling {@code resetConnectBackoff()} on the now-dead
     * channel instead of continuing until this supervisor's own {@link #close()}.
     */
    private void onShutdown() {
        generation.incrementAndGet();
        cancelHeartbeat();
        if (available.compareAndSet(true, false)) {
            outageStartNanos = System.nanoTime();
            LOG.log(System.Logger.Level.WARNING,
                    () -> channel.authority() + ": cv-service channel shut down; gate closed");
        } else {
            LOG.log(System.Logger.Level.INFO, () -> channel.authority()
                    + ": cv-service channel shut down during an existing outage; reconnect loop stopped");
        }
    }

    private void scheduleReconnect(long gen, Duration delay) {
        if (closed.get()) {
            return;
        }
        try {
            scheduler.schedule(() -> forceReconnect(gen, delay), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "Reconnect scheduling skipped -- supervisor already closing", e);
        }
    }

    /**
     * Fires after {@code justWaited}: forces a reconnect attempt, then reschedules itself with the
     * doubled (capped) backoff. {@code gen} pins this call to the outage it was scheduled for — see
     * class javadoc's "race this class is built to avoid".
     */
    private void forceReconnect(long gen, Duration justWaited) {
        if (closed.get() || generation.get() != gen) {
            return; // superseded: the outage this chain belonged to already ended (or the supervisor closed)
        }
        reconnectAttempts.incrementAndGet();
        try {
            channel.resetConnectBackoff();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "resetConnectBackoff failed for " + channel.authority(), e);
        }
        Duration next = justWaited.multipliedBy(2);
        if (next.compareTo(settings.reconnectMaxBackoff()) > 0) {
            next = settings.reconnectMaxBackoff();
        }
        scheduleReconnect(gen, next);
    }

    private void scheduleHeartbeat(long gen) {
        if (closed.get()) {
            return;
        }
        long intervalMillis = settings.outageLogInterval().toMillis();
        try {
            heartbeatTask = scheduler.scheduleAtFixedRate(() -> logHeartbeat(gen),
                    intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "Heartbeat scheduling skipped -- supervisor already closing", e);
        }
    }

    private void logHeartbeat(long gen) {
        if (generation.get() != gen) {
            return; // this outage ended (or was superseded) since the tick was scheduled -- stale, ignore
        }
        LOG.log(System.Logger.Level.INFO, () -> "cv-service at " + channel.authority() + " still unreachable ("
                + formatDuration(outageFor()) + ", " + reconnectAttempts() + " attempts)");
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m" + seconds + "s" : seconds + "s";
    }

    /** Idempotent. Stops the watch loop and shuts down this instance's own scheduler — never the channel. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelHeartbeat();
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "cv-channel-supervisor did not terminate within " + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
