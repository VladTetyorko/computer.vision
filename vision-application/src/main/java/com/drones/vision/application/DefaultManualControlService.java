package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.ChannelMap;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.RcChannels;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.ManualControlLink;
import com.drones.vision.domain.port.out.ManualControlPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The one implementation of {@link ManualControlService} (docs/RC-CONTROL-PHASE1-PLAN.md,
 * RC-CONTROL Phase 1 R2).
 *
 * <h2>Device resolution &amp; scope gate</h2>
 * Mirrors {@link DefaultFlightCommandService} exactly: {@link #engage} resolves {@code assetId} via
 * {@link AssetService#details(AssetId)} ({@link java.util.NoSuchElementException} for an unknown
 * asset), then, if the acting {@link VisibilityScope} does not include the asset, audits {@code
 * DENIED:out of scope} and throws {@link AccessDeniedException} (403) — a command is denied
 * honestly rather than hidden the way a scoped read would be. The first of the asset's <b>active</b>
 * devices {@link ManualControlPort#supports} claims is the one engaged; zero matches, or the port
 * rejecting the attempt as unreachable, both surface as {@link IllegalStateException} (mapped by
 * vision-api to a {@code denied} frame, not an HTTP command failure — there is no HTTP command
 * here). Neither of those two guards is audited: no attempt was ever actually sent, the same
 * "guard before an attempt" rule {@code DefaultFlightCommandService} already uses.
 *
 * <h2>One session per handle</h2>
 * This instance holds at most one active {@link ManualControlSession} at a time; a second {@link
 * #engage} while one is still active throws {@link IllegalStateException} without touching the
 * port. Because {@code vision-app}'s wiring is expected to construct exactly one {@code
 * ManualControlService} bean (a singleton, like every other service in this package), this in
 * practice serializes manual-control relaying to one connection at a time across the whole
 * application — an intentional Phase 1 simplification (single SITL operator), not an oversight; a
 * later phase wanting concurrent multi-operator relays would need a per-connection service instance
 * instead of a shared singleton.
 *
 * <h2>Watchdog</h2>
 * Each session keeps at most one outstanding {@link ScheduledFuture} at a time rather than
 * cancelling and rescheduling one per {@link ManualControlSession#onChannels}: the first check is
 * armed once, right after {@code engage} succeeds, {@code watchdogTimeoutMs} out. Every firing
 * re-reads the session's live {@code lastInput} timestamp and either releases (elapsed &ge;
 * timeout) or reschedules itself for the remaining time — so a steady ~30&nbsp;Hz stream of {@code
 * onChannels} calls never touches the scheduler at all, it only keeps pushing {@code lastInput}
 * forward. See {@code DefaultManualControlSession}'s own javadoc for the full mechanism and the
 * concurrency guard between an explicit {@link ManualControlSession#release()} and a racing
 * watchdog trip.
 *
 * <h2>Watchdog timeout tunable</h2>
 * The frozen §2 contract fixes exactly two public constructors — a 5-arg one taking an explicit
 * {@link Clock} + {@link ScheduledExecutorService} (the test seam), and a 3-arg convenience ctor
 * supplying {@link Clock#systemUTC()} + a single daemon {@code "rc-watchdog"} scheduler — both of
 * which use the {@value #DEFAULT_WATCHDOG_TIMEOUT_MS}&nbsp;ms default. A third, 6-arg canonical
 * constructor additionally takes an explicit {@code watchdogTimeoutMs}, following this codebase's
 * own "N-1-arg convenience constructor" idiom (see {@code PipelineConfig}/{@code SimulationSpec} in
 * vision-domain/vision-application): the two frozen constructors both delegate to it. {@code
 * vision-app}'s wiring should call the 6-arg constructor directly with the resolved {@code
 * vision.rc.watchdog-timeout-ms} property (default 300) when it wants that property to actually
 * take effect — the 3-/5-arg constructors always use the hard-coded default.
 *
 * <h2>Audit</h2>
 * Mirrors {@link DefaultFlightCommandService#audit}: one {@link AuditEntry} per {@code
 * ENGAGE}/{@code RELEASE}/{@code WATCHDOG}/{@code DENIED:out of scope} — {@link AuditAction#UPDATED}
 * (no dedicated "commanded" value exists), {@link AuditTargetType#ASSET}, attributes {@code
 * {assetId, command:"MANUAL_CONTROL", result}}.
 */
public final class DefaultManualControlService implements ManualControlService {

    /** Default input-loss watchdog timeout, docs/RC-CONTROL-PHASE1-PLAN.md §2 — 300&nbsp;ms. */
    public static final long DEFAULT_WATCHDOG_TIMEOUT_MS = 300L;

    private static final String WATCHDOG_THREAD_NAME = "rc-watchdog";
    private static final String COMMAND = "MANUAL_CONTROL";
    private static final String RESULT_ENGAGE = "ENGAGE";
    private static final String RESULT_RELEASE = "RELEASE";
    private static final String RESULT_WATCHDOG = "WATCHDOG";
    private static final String RESULT_DENIED = "DENIED:out of scope";
    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";

    private final AssetService assetService;
    private final ManualControlPort manualControlPort;
    private final AuditTrailPort auditTrail;
    private final Clock clock;
    private final ScheduledExecutorService watchdogScheduler;
    private final long watchdogTimeoutMs;

    private final Object sessionLock = new Object();
    private DefaultManualControlSession activeSession;

    /** Production convenience ctor: {@link Clock#systemUTC()}, a fresh daemon watchdog scheduler, the default timeout. */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail) {
        this(assetService, manualControlPort, auditTrail, Clock.systemUTC(), defaultWatchdogScheduler());
    }

    /** Test/wiring seam: explicit {@link Clock} + {@link ScheduledExecutorService}, default timeout. */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail, Clock clock,
                                        ScheduledExecutorService watchdogScheduler) {
        this(assetService, manualControlPort, auditTrail, clock, watchdogScheduler, DEFAULT_WATCHDOG_TIMEOUT_MS);
    }

    /** Canonical ctor: as the 5-arg ctor, with an explicit watchdog timeout (e.g. {@code vision.rc.watchdog-timeout-ms}). */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail, Clock clock,
                                        ScheduledExecutorService watchdogScheduler, long watchdogTimeoutMs) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.manualControlPort = Objects.requireNonNull(manualControlPort, "manualControlPort must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.watchdogScheduler = Objects.requireNonNull(watchdogScheduler, "watchdogScheduler must not be null");
        if (watchdogTimeoutMs <= 0) {
            throw new IllegalArgumentException("watchdogTimeoutMs must be positive");
        }
        this.watchdogTimeoutMs = watchdogTimeoutMs;
    }

    private static ScheduledExecutorService defaultWatchdogScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, WATCHDOG_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ManualControlSession engage(AssetId assetId, UserId actor, VisibilityScope scope,
                                        WatchdogListener onWatchdog) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(onWatchdog, "onWatchdog must not be null");

        synchronized (sessionLock) {
            if (activeSession != null) {
                throw new IllegalStateException(
                        "A manual-control session is already active on this handle; release it first");
            }

            AssetDetails details = assetService.details(assetId); // NoSuchElementException -> unknown asset
            Asset asset = details.summary().asset();
            if (!scope.includes(asset)) {
                // Honest denial, not a hidden read: mirrors DefaultFlightCommandService's own command
                // gate. The denial is audited -- an authorization refusal is security-relevant.
                audit(actor, assetId, RESULT_DENIED);
                throw new AccessDeniedException(
                        "Asset " + assetId.value() + " is outside your scope; you may not take manual control of it");
            }

            Device device = firstCommandableDevice(details.devices())
                    .orElseThrow(() -> new IllegalStateException(
                            "Asset " + assetId.value() + " has no active manual-control-capable device"));

            ManualControlLink link;
            try {
                link = manualControlPort.engage(device);
            } catch (IllegalArgumentException e) {
                // Not commandable / not currently reachable -- same "guard before an attempt, no
                // audit" posture as the no-supports-device case just above; nothing was relayed.
                throw new IllegalStateException(e.getMessage(), e);
            }

            DefaultManualControlSession session =
                    new DefaultManualControlSession(assetId, actor, link, onWatchdog, this::onSessionEnded);
            activeSession = session;
            audit(actor, assetId, RESULT_ENGAGE);
            session.armWatchdog();
            return session;
        }
    }

    private void onSessionEnded(DefaultManualControlSession session) {
        synchronized (sessionLock) {
            if (activeSession == session) {
                activeSession = null;
            }
        }
    }

    private Optional<Device> firstCommandableDevice(List<Device> devices) {
        return devices.stream()
                .filter(Device::isActive)
                .filter(manualControlPort::supports)
                .findFirst();
    }

    private void audit(UserId actor, AssetId assetId, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_ASSET_ID, assetId.value().toString());
        attributes.put(ATTR_COMMAND, COMMAND);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Manual control " + result + " for asset " + assetId.value(),
                attributes));
    }

    /**
     * The one implementation of {@link ManualControlSession}, returned by {@link #engage}. A
     * private inner class (mirrors {@link SupervisedPublisher}'s own {@code SupervisingSubscriber})
     * so it can reach the enclosing service's {@code manualControlPort}/{@code auditTrail}/{@code
     * clock}/{@code watchdogScheduler}/{@code watchdogTimeoutMs} directly, without re-threading five
     * more collaborators through its own constructor.
     *
     * <h2>Concurrency</h2>
     * {@link #onChannels}, an explicit {@link #release()}, and a scheduled watchdog check
     * ({@link #checkWatchdog()}) can each run on a different thread (a WebSocket connection's read
     * thread, whatever thread tears the connection down, and the shared {@code watchdogScheduler}
     * thread, respectively). {@link #released} is an {@link AtomicBoolean}; every path that ends the
     * session goes through {@link #doRelease}, which only proceeds past {@code
     * released.compareAndSet(false, true)} for the first caller — every later call (a second
     * explicit {@code release()}, a stale watchdog check that lost the race, or {@code onChannels}
     * arriving just after) is a safe no-op, and {@code auditTrail}/{@code manualControlPort#release}
     * are each invoked at most once. {@link #lastInput} is {@code volatile} purely for cross-thread
     * *visibility*: {@code onChannels} is its only writer, the watchdog-check thread its only
     * reader, so no further synchronization is needed for that field.
     *
     * <h2>Watchdog mechanism</h2>
     * {@link #armWatchdog()} (called once, by {@code engage}, right after construction) schedules
     * the first {@link #checkWatchdog()} {@code watchdogTimeoutMs} out. Each firing re-reads the
     * live {@link #lastInput}: if {@code now - lastInput >= watchdogTimeoutMs} it releases (audited
     * {@code WATCHDOG}, then {@link WatchdogListener#watchdogTripped()}); otherwise it reschedules
     * itself for exactly the remaining time. At most one {@link ScheduledFuture} is ever outstanding
     * per session — a steady stream of {@code onChannels} calls only ever writes {@link
     * #lastInput}, it never touches the scheduler.
     */
    private final class DefaultManualControlSession implements ManualControlSession {

        private final AssetId assetId;
        private final UserId actor;
        private final ManualControlLink link;
        private final WatchdogListener onWatchdog;
        private final Consumer<DefaultManualControlSession> onEnded;
        private final ChannelMap channelMap = ChannelMap.defaultMap();

        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile Instant lastInput;
        private volatile ScheduledFuture<?> watchdogFuture;

        DefaultManualControlSession(AssetId assetId, UserId actor, ManualControlLink link,
                                     WatchdogListener onWatchdog, Consumer<DefaultManualControlSession> onEnded) {
            this.assetId = assetId;
            this.actor = actor;
            this.link = link;
            this.onWatchdog = onWatchdog;
            this.onEnded = onEnded;
            this.lastInput = clock.instant();
        }

        /** Arms the first watchdog check. Called exactly once, by {@code engage}. */
        void armWatchdog() {
            scheduleCheck(watchdogTimeoutMs);
        }

        private void scheduleCheck(long delayMs) {
            watchdogFuture = watchdogScheduler.schedule(this::checkWatchdog, delayMs, TimeUnit.MILLISECONDS);
        }

        private void checkWatchdog() {
            if (released.get()) {
                return; // already ended by another path; this firing has nothing left to do
            }
            long elapsedMs = Duration.between(lastInput, clock.instant()).toMillis();
            if (elapsedMs >= watchdogTimeoutMs) {
                doRelease(RESULT_WATCHDOG, true);
            } else {
                scheduleCheck(watchdogTimeoutMs - elapsedMs);
            }
        }

        @Override
        public void onChannels(List<Double> axes, List<Double> buttons, long seq, long tSent) {
            if (released.get()) {
                return; // no-op once released/tripped
            }
            lastInput = clock.instant();
            RcChannels channels = channelMap.apply(axes, buttons);
            manualControlPort.send(link, channels);
        }

        @Override
        public void release() {
            doRelease(RESULT_RELEASE, false);
        }

        private void doRelease(String result, boolean notifyWatchdog) {
            if (!released.compareAndSet(false, true)) {
                return; // idempotent: some other path already released this session
            }
            ScheduledFuture<?> pending = watchdogFuture;
            if (pending != null) {
                pending.cancel(false);
            }
            manualControlPort.release(link);
            audit(actor, assetId, result);
            onEnded.accept(this);
            if (notifyWatchdog) {
                onWatchdog.watchdogTripped();
            }
        }

        @Override
        public ChannelMap channelMap() {
            return channelMap;
        }

        @Override
        public boolean active() {
            return !released.get();
        }
    }
}
