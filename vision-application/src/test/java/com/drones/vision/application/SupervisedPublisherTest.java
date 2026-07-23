package com.drones.vision.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SupervisedPublisher}, docs/MVP2-PLAN.md §S, S-a. Uses a hand-rolled fake
 * {@link ScheduledExecutorService} ({@link RecordingScheduler}) that records every scheduling
 * request without a real timer, so backoff-doubling/capping and stop-cancels-the-pending-retry are
 * both deterministic and instantaneous — no real sleeping anywhere in this suite.
 */
class SupervisedPublisherTest {

    @Test
    void firstSubscribeOpensExactlyOnceAndForwardsItemsUnchanged() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ScriptedPublisher<String> source = new ScriptedPublisher<>();
        AtomicInteger openCount = new AtomicInteger();
        SupervisedPublisher<String> supervised = new SupervisedPublisher<>(() -> {
            openCount.incrementAndGet();
            return source;
        }, cause -> { }, scheduler);
        RecordingSubscriber<String> downstream = new RecordingSubscriber<>();

        supervised.subscribe(downstream);
        source.emit("frame-0");
        source.emit("frame-1");

        assertEquals(1, openCount.get());
        assertEquals(List.of("frame-0", "frame-1"), downstream.received);
        assertTrue(scheduler.scheduled.isEmpty(), "no failure occurred: nothing should ever have been scheduled");
    }

    @Test
    void sourceErrorAnnouncesTheOutageOnceAndSchedulesARetryAtTheInitialBackoff() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ScriptedPublisher<String> source = new ScriptedPublisher<>();
        List<Throwable> announced = new ArrayList<>();
        SupervisedPublisher<String> supervised =
                new SupervisedPublisher<>(() -> source, announced::add, scheduler);
        RecordingSubscriber<String> downstream = new RecordingSubscriber<>();
        supervised.subscribe(downstream);

        RuntimeException boom = new RuntimeException("camera unplugged");
        source.error(boom);

        assertEquals(List.of(boom), announced);
        assertEquals(1, scheduler.scheduled.size());
        assertEquals(SupervisedPublisher.INITIAL_BACKOFF_NANOS, scheduler.scheduled.get(0).delayNanos());
    }

    @Test
    void completingGracefullyIsTreatedTheSameAsAnErrorButWithANullCause() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ScriptedPublisher<String> source = new ScriptedPublisher<>();
        List<Throwable> announced = new ArrayList<>();
        SupervisedPublisher<String> supervised =
                new SupervisedPublisher<>(() -> source, announced::add, scheduler);
        supervised.subscribe(new RecordingSubscriber<>());

        source.complete();

        assertEquals(1, announced.size());
        assertNull(announced.get(0));
        assertEquals(1, scheduler.scheduled.size());
    }

    @Test
    void backoffDoublesOnEachFailedRetryUpToTheCapAndOnlyTheFirstFailureIsAnnounced() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ScriptedPublisher<String> source = new ScriptedPublisher<>();
        List<Throwable> announced = new ArrayList<>();
        SupervisedPublisher<String> supervised =
                new SupervisedPublisher<>(() -> source, announced::add, scheduler);
        supervised.subscribe(new RecordingSubscriber<>());

        long expected = SupervisedPublisher.INITIAL_BACKOFF_NANOS;
        List<Long> observedDelays = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            source.error(new RuntimeException("still down"));
            RecordingScheduler.Scheduled last = scheduler.scheduled.get(scheduler.scheduled.size() - 1);
            observedDelays.add(last.delayNanos());
            scheduler.runNext(); // fire the retry synchronously -> re-subscribes to the same failing source
        }

        List<Long> expectedDelays = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            expectedDelays.add(expected);
            expected = Math.min(expected * 2, SupervisedPublisher.MAX_BACKOFF_NANOS);
        }
        assertEquals(expectedDelays, observedDelays);
        assertTrue(observedDelays.get(7) <= SupervisedPublisher.MAX_BACKOFF_NANOS,
                "backoff must never exceed the cap: " + observedDelays);
        // Every failure is part of the SAME uninterrupted outage (no successful onNext in between):
        // exactly one announcement, not one per failed retry.
        assertEquals(1, announced.size());
    }

    @Test
    void aSuccessfulItemEndsTheOutageAndTheNextFailureAnnouncesAFreshOne() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ScriptedPublisher<String> source = new ScriptedPublisher<>();
        List<Throwable> announced = new ArrayList<>();
        SupervisedPublisher<String> supervised =
                new SupervisedPublisher<>(() -> source, announced::add, scheduler);
        RecordingSubscriber<String> downstream = new RecordingSubscriber<>();
        supervised.subscribe(downstream);

        source.error(new RuntimeException("first outage"));
        scheduler.runNext(); // reopen: re-subscribes to the same source object
        source.emit("recovered"); // a real item flowing again ends the outage, resets backoff

        source.error(new RuntimeException("second outage"));

        assertEquals(2, announced.size());
        assertEquals(List.of("recovered"), downstream.received);
        RecordingScheduler.Scheduled secondOutageRetry = scheduler.scheduled.get(scheduler.scheduled.size() - 1);
        assertEquals(SupervisedPublisher.INITIAL_BACKOFF_NANOS, secondOutageRetry.delayNanos(),
                "backoff must have reset to the initial value after the intervening recovery");
    }

    @Test
    void explicitStopCancelsThePendingRetryAndNoFurtherOpenHappensEvenIfTheTimerFiresAnyway() {
        RecordingScheduler scheduler = new RecordingScheduler();
        AtomicInteger openCount = new AtomicInteger();
        ScriptedPublisher<String> source = new ScriptedPublisher<>();
        SupervisedPublisher<String> supervised = new SupervisedPublisher<>(() -> {
            openCount.incrementAndGet();
            return source;
        }, cause -> { }, scheduler);
        supervised.subscribe(new RecordingSubscriber<>());
        assertEquals(1, openCount.get());

        source.error(new RuntimeException("dead"));
        assertEquals(1, scheduler.scheduled.size());

        supervised.stop();

        assertTrue(scheduler.scheduled.get(0).future().cancelled(), "stop() must cancel the pending retry's future");
        // Belt-and-suspenders: even if the scheduler's own cancel() lost the race and the task ran
        // anyway, the internal stopped flag must still prevent a second open.
        scheduler.runNext();
        assertEquals(1, openCount.get(), "no further open() attempt may ever happen once stopped");
    }

    @Test
    void stopBeforeAnyFailureIsANoOpThatNeverThrows() {
        RecordingScheduler scheduler = new RecordingScheduler();
        SupervisedPublisher<String> supervised =
                new SupervisedPublisher<>(() -> new ScriptedPublisher<String>(), cause -> { }, scheduler);
        supervised.subscribe(new RecordingSubscriber<>());

        assertFalse(scheduler.scheduled.stream().anyMatch(s -> true));
        supervised.stop();
        supervised.stop(); // idempotent
    }

    @Test
    void aSynchronousOpenerFailureIsTreatedLikeAnAsyncErrorAndStillSchedulesARetry() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<Throwable> announced = new ArrayList<>();
        RuntimeException openFailure = new RuntimeException("registry misconfigured");
        SupervisedPublisher<String> supervised = new SupervisedPublisher<>(() -> {
            throw openFailure;
        }, announced::add, scheduler);

        supervised.subscribe(new RecordingSubscriber<>());

        assertEquals(List.of(openFailure), announced);
        assertEquals(1, scheduler.scheduled.size());
    }

    // -- test doubles ---------------------------------------------------------

    private static final class ScriptedPublisher<T> implements Flow.Publisher<T> {
        private volatile Flow.Subscriber<? super T> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // items are pushed synchronously via emit(); nothing to request
                }

                @Override
                public void cancel() {
                    // not exercised by this suite
                }
            });
        }

        void emit(T item) {
            subscriber.onNext(item);
        }

        void error(Throwable t) {
            subscriber.onError(t);
        }

        void complete() {
            subscriber.onComplete();
        }
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        final List<T> received = new ArrayList<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(T item) {
            received.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            // SupervisedPublisher must never forward this downstream; a stray call here would be a bug.
            throw new AssertionError("onError must never reach the downstream subscriber", throwable);
        }

        @Override
        public void onComplete() {
            throw new AssertionError("onComplete must never reach the downstream subscriber");
        }
    }

    /**
     * A {@link ScheduledExecutorService} test double that never runs a real timer: {@link
     * #schedule(Runnable, long, TimeUnit)} just records the request (delay + a cancellable {@link
     * ScheduledFuture}) so a test can assert on exactly what was requested and, via {@link
     * #runNext()}, choose precisely when (if ever) it actually executes — every other {@code
     * ScheduledExecutorService}/{@code ExecutorService} method is unused by {@link
     * SupervisedPublisher} and throws if ever called, so an accidental dependency on unsupported
     * scheduling would fail loudly rather than silently no-op.
     */
    private static final class RecordingScheduler implements ScheduledExecutorService {
        final List<Scheduled> scheduled = new ArrayList<>();

        void runNext() {
            scheduled.get(scheduled.size() - 1).command().run();
        }

        record Scheduled(Runnable command, long delayNanos, FakeFuture future) {
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            FakeFuture future = new FakeFuture();
            scheduled.add(new Scheduled(command, unit.toNanos(delay), future));
            return future;
        }

        private static final class FakeFuture implements ScheduledFuture<Object> {
            private boolean cancelled;

            boolean cancelled() {
                return cancelled;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled = true;
                return true;
            }

            @Override
            public long getDelay(TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int compareTo(Delayed o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public boolean isDone() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object get() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isTerminated() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks,
                                                                    long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }
}
