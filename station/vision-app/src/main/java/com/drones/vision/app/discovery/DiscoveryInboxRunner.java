package com.drones.vision.app.discovery;

import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.app.config.properties.VisionDiscoveryProperties;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.DiscoveryScanResult;
import com.drones.vision.warehouse.application.discovery.DiscoveryScanSpec;
import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns discovery from "a scan button feeding a form" into a standing background sweep (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P2, &sect;11, Z2c) — the {@code vision-app} composition
 * {@link DiscoveryInboxService} needs but must not itself depend on, following {@code
 * com.drones.vision.app.usage.UsageIdleCloseRunner}'s exact template (composition here, logic in
 * the context module); this codebase never uses {@code @Scheduled}/{@code @EnableScheduling}.
 *
 * <h2>What one sweep does</h2>
 * <ol>
 *   <li>If {@link VisionDiscoveryProperties.Lobby#enabled()}, re-asserts the standing MAVLink lobby
 *       hold ({@link MavlinkTelemetrySource#holdLobby(int)}) — idempotent, and also the self-healing
 *       call after a link-failure self-close (see that method's own javadoc); a brand-new vehicle
 *       broadcasting to this port locks onto vision's GCS heartbeat within seconds without an
 *       operator touching a form.</li>
 *   <li>Runs {@link DiscoveryService#scan} across every registered method, bounded by {@link
 *       VisionDiscoveryProperties.Inbox#scanTimeoutSeconds()}.</li>
 *   <li>{@link DiscoveryInboxService#report reports} each hit — the inbox's own upsert-by-identity
 *       dedupe (see that method's javadoc) does the rest.</li>
 * </ol>
 *
 * <h2>The first sweep fires immediately</h2>
 * {@link #start()} arms the tick at delay {@code 0}, not after one interval — the same "don't make
 * the operator wait out a full period after a fresh boot" reasoning {@code UsageIdleCloseRunner}'s
 * own javadoc gives.
 *
 * <h2>Gating</h2>
 * This bean is registered only while {@link VisionDiscoveryProperties.Inbox#enabled()} — read via
 * {@code @ConditionalOnProperty} on {@code DiscoveryInboxWiringConfiguration}'s {@code @Bean} method,
 * not in this class. {@link VisionDiscoveryProperties.Lobby#enabled()} is a finer-grained flag
 * checked inside every sweep instead: an operator may want the inbox's scan+report half without the
 * standing lobby's auto-reply-to-any-broadcasting-vehicle behavior.
 */
public final class DiscoveryInboxRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DiscoveryInboxRunner.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final DiscoveryInboxService discoveryInboxService;
    private final DiscoveryService discoveryService;
    private final MavlinkTelemetrySource mavlinkTelemetrySource;
    private final VisionDiscoveryProperties properties;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastSweepAt = new AtomicReference<>();

    public DiscoveryInboxRunner(DiscoveryInboxService discoveryInboxService, DiscoveryService discoveryService,
                                 MavlinkTelemetrySource mavlinkTelemetrySource, VisionDiscoveryProperties properties) {
        this.discoveryInboxService =
                Objects.requireNonNull(discoveryInboxService, "discoveryInboxService must not be null");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService must not be null");
        this.mavlinkTelemetrySource =
                Objects.requireNonNull(mavlinkTelemetrySource, "mavlinkTelemetrySource must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "discovery-inbox-runner");
        thread.setDaemon(true);
        return thread;
    }

    /** Idempotent. Arms the sweep loop -- the first sweep fires immediately, not after one interval. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long periodMillis = Duration.ofSeconds(properties.inbox().sweepSeconds()).toMillis();
        scheduler.scheduleAtFixedRate(this::sweepSafely, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void sweepSafely() {
        try {
            if (properties.lobby().enabled()) {
                mavlinkTelemetrySource.holdLobby(properties.mavlinkPort());
            }
            DiscoveryScanSpec spec = new DiscoveryScanSpec(
                    Duration.ofSeconds(properties.inbox().scanTimeoutSeconds()), Set.of());
            DiscoveryScanResult result = discoveryService.scan(spec);
            for (DiscoveredDevice discovered : result.devices()) {
                discoveryInboxService.report(discovered);
            }
            if (!result.devices().isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        () -> "discovery-inbox sweep reported " + result.devices().size() + " candidate(s)");
            }
            lastSweepAt.set(Instant.now());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "discovery-inbox sweep failed; will retry next cycle", e);
        }
    }

    /**
     * When this runner last completed a sweep (the standing-lobby re-assert, the scan, and every
     * {@link DiscoveryInboxService#report} call all finished without throwing) — {@code null} if
     * none has completed yet, the {@code GET /api/discovery/status} shape's own {@code lastSweepAt}
     * (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C2) absent-before-first-sweep
     * contract. Deliberately not updated for a sweep that threw partway through — {@link
     * #sweepSafely()}'s catch swallows the exception so the schedule keeps running, but this
     * timestamp only ever reports a sweep that genuinely finished.
     *
     * @return the instant of the last completed sweep, or {@code null}
     */
    public Instant lastSweepAt() {
        return lastSweepAt.get();
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
                        () -> "discovery-inbox-runner did not terminate within " + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
