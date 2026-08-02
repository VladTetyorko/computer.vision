package com.drones.vision.application.pipeline;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link Flow.Publisher} decorator that keeps re-opening a re-openable source after it ends
 * (error or graceful completion) instead of ever surfacing that termination to the downstream
 * subscriber — the mechanism behind docs/MVP2-PLAN.md &sect;S, S-a: a started stream survives
 * source failures (video or telemetry alike); only an explicit {@link #stop()} ends it.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>{@link #subscribe(Flow.Subscriber)} opens the source once ({@code opener.get()}) and
 *       forwards {@code onSubscribe}/{@code onNext} straight through to the downstream subscriber
 *       — item delivery and backpressure are untouched; this class never sits on the hot data
 *       path, it only intercepts the two terminal signals.</li>
 *   <li>{@code onError}/{@code onComplete} from the current open are intercepted, never forwarded
 *       downstream: {@code onOutageBegan} runs exactly once per outage — not once per failed retry,
 *       mirroring {@code StreamPipeline}'s own detection-outage and {@code
 *       MediamtxStreamPublisher}'s own publish-outage "one log/event per outage" convention — then
 *       a fresh open is scheduled after a capped exponential backoff ({@link
 *       #INITIAL_BACKOFF_NANOS} doubling to {@link #MAX_BACKOFF_NANOS}, retried indefinitely; there
 *       is no give-up). The outage is considered over, and the backoff reset, the moment an item
 *       actually flows again ({@code onNext}) — not merely once a reopen attempt starts, since a
 *       source can fail again immediately after a superficially successful (re)subscribe.</li>
 *   <li>{@link #stop()} is the only way retries end: it cancels any pending scheduled reopen (no
 *       zombie timers) and every later {@code onError}/{@code onComplete} — including one that was
 *       already in flight when {@code stop()} ran — becomes a no-op instead of scheduling another
 *       retry. It does not touch whatever open subscription/publisher currently exists; the caller
 *       (which already holds the underlying port and id) remains responsible for its own {@code
 *       close(...)} call, exactly as it would without supervision.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@code onNext}/{@code onError}/{@code onComplete} for one open are never concurrent with each
 * other (the {@link Flow.Publisher} contract), but a scheduled retry runs on whatever thread {@code
 * scheduler} uses, distinct from both the thread that produced the failure and the thread that may
 * call {@link #stop()} concurrently — {@link #backoffNanos}/{@link #outageAnnounced} are {@code
 * volatile} purely for cross-thread *visibility* between successive opens (never two threads
 * writing concurrently: each open's signals are fully serialized before the next open's begin), and
 * {@link #stopped}/{@link #pendingRetry} are the two fields genuinely read and written from
 * unrelated threads, guarded by an {@link AtomicBoolean} and plain volatile get/replace
 * respectively — see {@link #openAndSubscribe}/{@link #ended} for the exact race this avoids.
 *
 * @param <T> the item type flowing through the supervised source (e.g. {@code VideoFrame}, {@code
 *            Telemetry})
 */
public final class SupervisedPublisher<T> implements Flow.Publisher<T> {

    /** Backoff before the first retry after a source ends (1s), per docs/MVP2-PLAN.md §S, S-a. */
    static final long INITIAL_BACKOFF_NANOS = 1_000_000_000L;

    /** Cap the exponential backoff doubles up to while retries keep failing (30s). */
    static final long MAX_BACKOFF_NANOS = 30_000_000_000L;

    private final Supplier<Flow.Publisher<T>> opener;
    private final Consumer<Throwable> onOutageBegan;
    private final ScheduledExecutorService scheduler;
    private final long initialBackoffNanos;
    private final long maxBackoffNanos;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> pendingRetry;
    private volatile long backoffNanos;
    private volatile boolean outageAnnounced = false;

    /**
     * @param opener        reopens the underlying source; called once for the initial {@link
     *                      #subscribe} and again for every retry. Must not block for long (see
     *                      {@code VideoSourcePort}/{@code TelemetrySourcePort}'s own "per-open,
     *                      hot/live" contract — every registered adapter today returns quickly and
     *                      surfaces failure asynchronously via {@code onError} instead)
     * @param onOutageBegan invoked exactly once per outage (the transition from "flowing" to
     *                      "ended"), with the triggering {@link Throwable} — {@code null} for a
     *                      graceful {@code onComplete} rather than an {@code onError}
     * @param scheduler     runs the delayed reopen attempts; owned and shut down by the caller, not
     *                      this class
     */
    SupervisedPublisher(Supplier<Flow.Publisher<T>> opener, Consumer<Throwable> onOutageBegan,
                         ScheduledExecutorService scheduler) {
        this(opener, onOutageBegan, scheduler, INITIAL_BACKOFF_NANOS, MAX_BACKOFF_NANOS);
    }

    /**
     * Same as the 3-argument constructor, with explicit backoff bounds — public because a caller
     * in a different {@code vision-application} feature package (e.g. {@code stream}'s {@code
     * DefaultStreamService}) supplies its own configured bounds rather than this class's own
     * defaults (docs/LAYERING-REFACTOR-PLAN.md &sect;1.3 config extraction).
     */
    public SupervisedPublisher(Supplier<Flow.Publisher<T>> opener, Consumer<Throwable> onOutageBegan,
                         ScheduledExecutorService scheduler, long initialBackoffNanos, long maxBackoffNanos) {
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
        this.onOutageBegan = Objects.requireNonNull(onOutageBegan, "onOutageBegan must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.initialBackoffNanos = initialBackoffNanos;
        this.maxBackoffNanos = maxBackoffNanos;
        this.backoffNanos = initialBackoffNanos;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        openAndSubscribe(subscriber);
    }

    /**
     * Explicit stop: cancels any pending scheduled reopen and permanently disables further
     * retries. Idempotent. Does not close the currently-open source/subscription, if any — that
     * remains the caller's own responsibility, unchanged from the unsupervised case.
     */
    public void stop() {
        stopped.set(true);
        ScheduledFuture<?> pending = pendingRetry;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private void openAndSubscribe(Flow.Subscriber<? super T> downstream) {
        if (stopped.get()) {
            return; // an explicit stop raced ahead of this (re)open attempt: do nothing
        }
        try {
            opener.get().subscribe(new SupervisingSubscriber(downstream));
        } catch (RuntimeException e) {
            // A synchronous opener failure must be treated exactly like an asynchronous onError --
            // otherwise a ScheduledExecutorService silently swallows an uncaught exception from a
            // fire-and-forget scheduled Runnable, permanently and invisibly ending retries.
            ended(e, downstream);
        }
    }

    /**
     * Runs once per terminal signal from the current open (or a synchronous {@code opener}
     * failure). Announces the outage at most once (see class javadoc) and, unless {@link #stopped}
     * has already been requested, schedules the next retry with the current backoff before doubling
     * it (capped) for next time.
     */
    private void ended(Throwable cause, Flow.Subscriber<? super T> downstream) {
        if (!outageAnnounced) {
            outageAnnounced = true;
            onOutageBegan.accept(cause);
        }
        if (stopped.get()) {
            return; // no further retry once explicitly stopped, even for a signal already in flight
        }
        long delay = backoffNanos;
        backoffNanos = Math.min(backoffNanos * 2, maxBackoffNanos);
        pendingRetry = scheduler.schedule(() -> openAndSubscribe(downstream), delay, TimeUnit.NANOSECONDS);
    }

    /** Forwards {@code onSubscribe}/{@code onNext} verbatim; intercepts the two terminal signals. */
    private final class SupervisingSubscriber implements Flow.Subscriber<T> {
        private final Flow.Subscriber<? super T> downstream;

        SupervisingSubscriber(Flow.Subscriber<? super T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(subscription);
        }

        @Override
        public void onNext(T item) {
            if (outageAnnounced) {
                outageAnnounced = false;
                backoffNanos = initialBackoffNanos;
            }
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            ended(throwable, downstream);
        }

        @Override
        public void onComplete() {
            ended(null, downstream);
        }
    }
}
