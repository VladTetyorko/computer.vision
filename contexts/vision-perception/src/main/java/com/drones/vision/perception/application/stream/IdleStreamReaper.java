package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.StopReason;
import com.drones.vision.perception.domain.port.VideoDemandPort;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Stops streams nobody is watching (docs/plans/active/STREAM-STATE-PLAN.md &sect;3.2).
 *
 * <p><b>The measured problem.</b> Nothing in this system was responsible for ending a stream. A
 * stream left running by accident on the owner's machine burned ~2.9 cores &mdash; app plus
 * cv-service &mdash; indefinitely, and would have kept doing so until the process died. Start had an
 * owner (the operator) and stop had one too; "still running, unwatched, forever" had none.
 *
 * <p><b>Why a separate class.</b> {@link DefaultStreamService} already owns starting, stopping,
 * config patching, detection demand and the pipeline registry; this is a policy <i>over</i> that
 * service, not another of its jobs, and it needs nothing from it that {@link StreamService} does not
 * already expose. So it sits one layer up and talks to the interface &mdash; which also means it can
 * be tested against a hand-written {@code StreamService} with no pipeline, no video source and no
 * scheduler at all.
 *
 * <h2>Fail-safe direction</h2>
 * Every uncertainty here resolves toward <b>keeping the stream alive</b>:
 * <ul>
 *   <li>{@link VideoDemandPort} answers {@code true} when it cannot tell (its own contract).</li>
 *   <li>A stream seen for the first time starts its idle clock <i>now</i>, so it always gets the
 *       full timeout &mdash; a reaper started mid-flight can never stop a stream on its first sweep.</li>
 *   <li>One stream's failed evaluation is logged and skipped; it never aborts the sweep, and it never
 *       stops that stream.</li>
 * </ul>
 * The asymmetry is deliberate: waiting too long costs CPU, which is recoverable and visible; stopping
 * too early takes the video away from an operator who is using it, and blames nobody.
 *
 * <h2>Threading</h2>
 * One daemon scheduler thread, fixed delay (not fixed rate &mdash; a sweep that runs long must not
 * queue another behind it, since {@link VideoDemandPort} may do network I/O). {@link #sweep} is
 * package-private and takes an explicit {@code now} so tests drive it directly, the same seam
 * {@code DefaultStreamService#evaluateDetectionDemand} already uses.
 */
public final class IdleStreamReaper implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IdleStreamReaper.class.getName());

    private final StreamService streamService;
    private final VideoDemandPort videoDemandPort;
    private final IdleStreamPolicy policy;
    private final Function<DeviceId, AssetId> assetResolver;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "idle-stream-reaper");
        thread.setDaemon(true);
        return thread;
    });

    /** Per-stream instant of the last sweep at which its video was observably wanted. */
    private final ConcurrentHashMap<StreamId, Instant> lastWantedAt = new ConcurrentHashMap<>();

    /**
     * @param streamService  the streams to sweep and, when idle, to stop
     * @param videoDemandPort what "watched" means; see its own contract for why it must fail open
     * @param policy         when to stop; {@link IdleStreamPolicy#disabled()} makes {@link #start} a no-op
     * @param assetResolver  a stream's device to its owning asset, or {@code null} when it has none —
     *                       a narrow functional seam rather than a dependency on {@code UsageTracker}
     *                       itself, the same idiom {@code LiveAndPollDetectionDemand} uses for its own
     *                       registry lookup
     */
    public IdleStreamReaper(StreamService streamService, VideoDemandPort videoDemandPort,
                             IdleStreamPolicy policy, Function<DeviceId, AssetId> assetResolver) {
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.videoDemandPort = Objects.requireNonNull(videoDemandPort, "videoDemandPort must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.assetResolver = Objects.requireNonNull(assetResolver, "assetResolver must not be null");
    }

    /**
     * Begins sweeping, or does nothing at all when the policy is disabled — the off switch is here
     * rather than at the wiring site so a deployment that turns the policy off still gets a bean it
     * can read, and so "disabled" cannot be expressed by silently forgetting to call this.
     *
     * <p>Idempotent enough for its one caller (a Spring lifecycle hook); calling it twice would
     * schedule two sweeps, which is a wiring bug rather than something to defend against here.
     */
    public void start() {
        if (!policy.enabled()) {
            LOG.log(System.Logger.Level.INFO, "idle-stream policy disabled; streams will run until stopped explicitly");
            return;
        }
        long intervalMillis = policy.checkInterval().toMillis();
        scheduler.scheduleWithFixedDelay(() -> sweep(Instant.now()), intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        LOG.log(System.Logger.Level.INFO, "idle-stream policy active: stopping streams unwatched for "
                + policy.timeout() + ", checked every " + policy.checkInterval());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /**
     * Evaluates every running stream once.
     *
     * <p>Each stream is individually wrapped: {@code scheduleWithFixedDelay} silently cancels every
     * future run of a task that ever lets an exception escape, so one stream's misbehaving demand
     * lookup must not be able to disable the policy for the whole JVM with no further error ever
     * surfacing.
     *
     * @param now the instant to evaluate as of
     */
    void sweep(Instant now) {
        List<ActiveStream> streams = streamService.streams();
        Set<StreamId> running = new HashSet<>();
        for (ActiveStream stream : streams) {
            running.add(stream.streamId());
            try {
                evaluate(stream, now);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "idle evaluation failed for stream " + stream.streamId().value()
                                + "; leaving it running", t);
            }
        }
        lastWantedAt.keySet().retainAll(running); // streams stopped by anyone else stop being tracked
    }

    private void evaluate(ActiveStream stream, Instant now) {
        StreamId streamId = stream.streamId();
        // A stream seen for the first time starts its clock now: it must never be stopped on the
        // sweep that discovered it, however long it had already been running unwatched.
        Instant idleSince = lastWantedAt.putIfAbsent(streamId, now);
        if (videoDemandPort.videoWanted(streamId, assetResolver.apply(stream.deviceId()))) {
            lastWantedAt.put(streamId, now);
            return;
        }
        Duration idleFor = Duration.between(idleSince == null ? now : idleSince, now);
        if (idleFor.compareTo(policy.timeout()) < 0) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "stopping stream " + streamId.value()
                + ": no video demand for " + idleFor);
        lastWantedAt.remove(streamId);
        streamService.stop(streamId, StopReason.IDLE_NO_VIEWERS);
    }
}
