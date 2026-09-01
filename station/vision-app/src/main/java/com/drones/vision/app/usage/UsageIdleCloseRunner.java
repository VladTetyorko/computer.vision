package com.drones.vision.app.usage;

import com.drones.vision.app.config.properties.VisionUsageProperties;
import com.drones.vision.warehouse.application.usage.UsageIdleCloseService;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The vision-app composition {@link UsageIdleCloseService} needs but must not itself depend on
 * (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1) — sweeps every open usage on a fixed
 * cadence, following the {@code com.drones.vision.app.geo.TrackProjectionRunner} template (composition
 * here, logic in the context module) — this codebase never uses {@code @Scheduled}/{@code
 * @EnableScheduling}.
 *
 * <h2>The first sweep fires immediately</h2>
 * {@link #start()} arms the tick at delay {@code 0}, not after one interval, so a station that
 * crashed with open usages sweeps them the moment it comes back up rather than waiting out a full
 * {@link VisionUsageProperties#sweepPeriod()} first.
 *
 * <h2>Unconditional</h2>
 * Unlike most runners in this package tree, there is no enable flag gating this bean — see {@link
 * VisionUsageProperties}'s own javadoc for why.
 */
public final class UsageIdleCloseRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(UsageIdleCloseRunner.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final UsageIdleCloseService usageIdleCloseService;
    private final VisionUsageProperties properties;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public UsageIdleCloseRunner(UsageIdleCloseService usageIdleCloseService, VisionUsageProperties properties) {
        this.usageIdleCloseService = Objects.requireNonNull(usageIdleCloseService, "usageIdleCloseService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "usage-idle-close-runner");
        thread.setDaemon(true);
        return thread;
    }

    /** Idempotent. Arms the sweep loop -- the first sweep fires immediately, not after one interval. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long periodMillis = properties.sweepPeriod().toMillis();
        scheduler.scheduleAtFixedRate(this::sweepSafely, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void sweepSafely() {
        try {
            int closedCount = usageIdleCloseService.closeIdleUsages();
            if (closedCount > 0) {
                LOG.log(System.Logger.Level.INFO, () -> "idle-usage sweep closed " + closedCount + " usage(s)");
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "idle-usage sweep failed; will retry next cycle", e);
        }
    }

    /** Idempotent. Stops the sweep loop. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "usage-idle-close-runner did not terminate within " + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
