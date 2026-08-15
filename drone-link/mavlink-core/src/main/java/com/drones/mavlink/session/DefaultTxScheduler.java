package com.drones.mavlink.session;

import com.drones.mavlink.config.MavlinkCoreSettings;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one {@link TxScheduler} implementation: one shared {@link ScheduledExecutorService} for
 * every registered {@link #repeat} task, named threads, and a small fixed pool size (2 — an
 * internal implementation choice, not a spec- or config-pinned value: enough that one slow/blocked
 * TX task cannot delay every other periodic task sharing this scheduler, while still being
 * unambiguously "one shared pool," not one thread per feature, per plan §3.3).
 *
 * <p>Not itself part of the frozen {@link TxScheduler} seam: {@link #close} is this concrete
 * class's own lifecycle method for whoever constructs a scheduler to shut it down (a scheduler is
 * independent of any one {@link MavlinkSession} — {@code MavlinkSession} does not own or expose
 * one, since TX scheduling is orthogonal to the RX-driven session state it manages).
 */
public final class DefaultTxScheduler implements TxScheduler {

    private static final System.Logger LOG = System.getLogger(DefaultTxScheduler.class.getName());
    private static final int POOL_SIZE = 2;

    private final Duration closeJoinTimeout;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger threadCounter = new AtomicInteger();

    public DefaultTxScheduler(MavlinkCoreSettings settings) {
        this(settings.closeJoinTimeout());
    }

    public DefaultTxScheduler(Duration closeJoinTimeout) {
        this.closeJoinTimeout = Objects.requireNonNull(closeJoinTimeout, "closeJoinTimeout");
        this.executor = Executors.newScheduledThreadPool(POOL_SIZE, this::newThread);
    }

    private Thread newThread(Runnable r) {
        Thread thread = new Thread(r, "mavlink-tx-scheduler-" + threadCounter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public Handle repeat(String name, Duration period, Runnable task) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(task, "task");
        long periodMillis = Math.max(1, period.toMillis());
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                () -> runSafely(name, task), 0, periodMillis, TimeUnit.MILLISECONDS);
        return new HandleImpl(future);
    }

    private void runSafely(String name, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "TxScheduler task '" + name + "' threw; the schedule continues", e);
        }
    }

    /** Idempotent. Shuts down the shared executor, bounded by {@code closeJoinTimeout}. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(closeJoinTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "TxScheduler did not terminate within " + closeJoinTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class HandleImpl implements Handle {
        private final ScheduledFuture<?> future;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        HandleImpl(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false);
            }
        }
    }
}
