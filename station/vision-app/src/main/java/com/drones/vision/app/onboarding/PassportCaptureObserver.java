package com.drones.vision.app.onboarding;

import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.perception.application.pipeline.UsagePhaseObserver;
import com.drones.vision.platform.PlatformActor;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.UsagePhase;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The flight passport's automatic capture (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave
 * O11) — the {@link UsagePhaseObserver} implementation that composes {@code UsageTracker}
 * (vision-perception)'s phase-transition seam with {@link VehicleProfileService#captureSnapshot}
 * (vision-flight), without either context depending on the other: this class lives in {@code
 * vision-app}, the one place allowed to know about both, exactly the composition role {@code
 * ApplicationServiceWiring} already plays for {@code GeofenceMonitor::evaluate} against the sibling
 * {@code telemetryObserver} seam.
 *
 * <h2>What triggers a capture</h2>
 * <ul>
 *   <li>{@code previous == null && next == PREFLIGHT} — the usage's opening: a passport needs a
 *       PREFLIGHT snapshot from the moment the usage begins, since nothing else marks "before this
 *       flight."</li>
 *   <li>{@code next == POSTFLIGHT} — the flight's own ending.</li>
 *   <li>Everything else (IN_FLIGHT, LINK_LOST, ABANDONED, CLOSED, or a redundant PREFLIGHT
 *       re-notification that can't occur per {@link UsagePhaseObserver}'s own contract) is ignored.</li>
 * </ul>
 *
 * <h2>Never blocks the caller</h2>
 * {@link #onPhaseChanged} is called on {@code UsageTracker}'s telemetry/stream-lifecycle thread,
 * which must never be stalled by a probe's real link I/O. Every capture therefore runs on {@link
 * #executor}, a single dedicated background thread with a small bounded queue; when the queue is
 * already full (captures backing up faster than they complete — a stuck or slow probe, not a sizing
 * problem, since a passport captures at most twice per flight) the submission is dropped and logged
 * rather than blocking or growing without bound.
 *
 * <h2>Failure handling</h2>
 * {@link IllegalStateException} (no probeable device, or {@code vision.onboarding.probe.enabled=false}
 * — see {@code NoopVehicleConfigPort}) is an <b>expected</b> outcome, not a bug: logged once per
 * usage, at a low level, with no stack trace, so a fleet running with probing off doesn't spam its
 * logs with the same explained failure on every single flight. Any other {@link RuntimeException} is
 * unexpected and logged at {@code WARNING} with its stack trace every time.
 */
public final class PassportCaptureObserver implements UsagePhaseObserver, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PassportCaptureObserver.class.getName());

    /**
     * How many pending captures {@link #executor}'s queue tolerates before a new one is dropped
     * instead of blocking the caller. A passport captures at most twice per flight (PREFLIGHT,
     * POSTFLIGHT), so this is sized generously above any realistic burst of usages transitioning at
     * once — a full queue means captures are genuinely stuck (a hung probe), not that this number is
     * too small.
     */
    private static final int QUEUE_CAPACITY = 32;

    private final VehicleProfileService vehicleProfileService;
    private final Duration window;
    private final ThreadPoolExecutor executor;
    /** Usages a "probing disabled / no device" skip has already been logged for — see the class javadoc. */
    private final Set<UsageId> disabledSkipLogged = ConcurrentHashMap.newKeySet();

    /**
     * @param vehicleProfileService the PROBE-stage service {@link #capture} calls
     * @param window                the probe budget handed to {@code captureSnapshot} — {@code
     *                               vision.onboarding.probe.inventory-window}, the same window a
     *                               manually-triggered probe uses (see {@code
     *                               VisionOnboardingProperties.Probe#inventoryWindow()}'s own
     *                               javadoc: "{@code VehicleConfigPort#probe}'s {@code window}
     *                               argument")
     */
    public PassportCaptureObserver(VehicleProfileService vehicleProfileService, Duration window) {
        this.vehicleProfileService =
                Objects.requireNonNull(vehicleProfileService, "vehicleProfileService must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "passport-capture");
                    thread.setDaemon(true);
                    return thread;
                },
                // Never throws, never runs on the caller's thread: a full (or already-shut-down)
                // queue simply drops the submission and logs it, exactly the "drop and log rather
                // than block the telemetry thread" contract this class promises.
                (task, exec) -> LOG.log(System.Logger.Level.WARNING,
                        "passport capture queue is full or shut down -- dropping this capture"));
    }

    @Override
    public void onPhaseChanged(AssetId assetId, UsageId usageId, UsagePhase previous, UsagePhase next, Instant at) {
        FlightPhase phase = capturePhaseFor(previous, next);
        if (phase == null) {
            return;
        }
        executor.execute(() -> capture(assetId, usageId, phase));
    }

    private static FlightPhase capturePhaseFor(UsagePhase previous, UsagePhase next) {
        if (next == UsagePhase.POSTFLIGHT) {
            return FlightPhase.POSTFLIGHT;
        }
        if (previous == null && next == UsagePhase.PREFLIGHT) {
            return FlightPhase.PREFLIGHT;
        }
        return null;
    }

    private void capture(AssetId assetId, UsageId usageId, FlightPhase phase) {
        try {
            vehicleProfileService.captureSnapshot(assetId, usageId, phase, window, PlatformActor.USER_ID,
                    VisibilityScope.unbounded());
        } catch (IllegalStateException e) {
            if (disabledSkipLogged.add(usageId)) {
                LOG.log(System.Logger.Level.DEBUG, () -> "passport capture (" + phase + ") skipped for usage "
                        + usageId.value() + ": " + e.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "passport capture (" + phase + ") failed for usage "
                    + usageId.value() + ": " + e.getMessage(), e);
        }
    }

    /** Shuts {@link #executor} down cleanly — Spring infers this as the bean's destroy method. */
    @Override
    public void close() {
        executor.shutdown();
    }
}
