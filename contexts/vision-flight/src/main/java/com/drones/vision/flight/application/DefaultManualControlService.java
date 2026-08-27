package com.drones.vision.flight.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.flight.domain.model.UnidentifiedReason;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.flight.domain.model.RcChannels;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.flight.domain.port.ManualControlLink;
import com.drones.vision.flight.domain.port.ManualControlPort;

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
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link ManualControlService} (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md,
 * RC-CONTROL Phase 1 R2).
 *
 * <h2>The stick layout is chosen per session, from the vehicle</h2>
 * The session no longer relays one frozen, airframe-blind map. {@code ManualControlLink#vehicleKind()}
 * reports what the adapter is currently hearing, and {@link ControlProfile#forKind} turns that into
 * the right stick layout — decisively, where the throttle rests: idle on a copter, stop on a rover
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P2/P9).
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
 * <h2>An unidentified vehicle is refused, not guessed at (FLEET-RADIO R2)</h2>
 * Unlike the two guards above, {@link ManualControlLink#vehicleKind()} can only be read <em>after</em>
 * the port has already opened a real relay link. If it reads {@link VehicleKind#UNKNOWN}, {@link
 * #engage} releases that link immediately (so the aircraft's failsafe still gets its release-sentinel
 * burst), audits {@code REFUSED:unidentified-vehicle:<reason>} — this guard is security/safety
 * relevant, unlike the two above, precisely because a real relay was opened and then deliberately
 * torn down — and throws {@link VehicleUnidentifiedException} carrying which of the three {@link
 * UnidentifiedReason} causes applied. See that enum's own javadoc and {@link #refusalMessage} for why
 * the three read differently to the operator instead of collapsing into one "cannot engage" text.
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
 * <h2>A silently misconfigured vehicle is refused too (FLEET-RADIO R6)</h2>
 * Unlike the R2 guard above, this one needs no live link at all: {@code RC_OPTIONS} ignoring
 * overrides and a GCS-sysid mismatch are both facts the vehicle's last-probed {@link
 * com.drones.vision.flight.domain.model.VehicleProfile} already carries (or does not — an
 * unprobed/incomplete vehicle is {@code UNKNOWN}, not a blocker, matching this module's "absence of
 * evidence is not evidence of readiness" rule everywhere else). {@link #engage} re-evaluates the
 * {@code rc-relay} {@link com.drones.vision.flight.domain.model.FeatureReadiness} row via {@link
 * ReadinessService} right after the scope gate and before any device is resolved or any port is
 * touched — a parameter can change between a preflight display and this engage, so the check is
 * re-run here rather than trusted from an earlier read. A {@link FeatureStatus#MISSING} verdict
 * throws a plain {@link IllegalStateException} carrying the row's own honest detail sentence and
 * audits {@code REFUSED:not-ready:rc-relay}; {@code DEGRADED}/{@code READY}/{@code UNKNOWN} all let
 * {@code engage} proceed.
 *
 * <h2>Audit</h2>
 * Mirrors {@link DefaultFlightCommandService#audit}: one {@link AuditEntry} per {@code
 * ENGAGE}/{@code RELEASE}/{@code WATCHDOG}/{@code DENIED:out of scope}/{@code
 * REFUSED:unidentified-vehicle:<reason>}/{@code REFUSED:not-ready:<featureKey>} — {@link
 * AuditAction#UPDATED} (no dedicated "commanded" value exists), {@link AuditTargetType#ASSET},
 * attributes {@code {assetId, command:"MANUAL_CONTROL", result}}.
 */
public final class DefaultManualControlService implements ManualControlService {

    /** Default input-loss watchdog timeout, docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §2 — 300&nbsp;ms. */
    public static final long DEFAULT_WATCHDOG_TIMEOUT_MS = 300L;

    private static final String WATCHDOG_THREAD_NAME = "rc-watchdog";
    private static final String COMMAND = "MANUAL_CONTROL";
    private static final String RESULT_ENGAGE = "ENGAGE";
    private static final String RESULT_RELEASE = "RELEASE";
    private static final String RESULT_WATCHDOG = "WATCHDOG";
    private static final String RESULT_DENIED = "DENIED:out of scope";
    private static final String RESULT_REFUSED_PREFIX = "REFUSED:unidentified-vehicle:";
    private static final String RESULT_REFUSED_NOT_READY_PREFIX = "REFUSED:not-ready:";
    private static final String RC_RELAY_FEATURE_KEY = "rc-relay";
    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";

    private final AssetService assetService;
    private final ManualControlPort manualControlPort;
    private final AuditTrailPort auditTrail;
    private final ReadinessService readinessService;
    private final Clock clock;
    private final ScheduledExecutorService watchdogScheduler;
    private final long watchdogTimeoutMs;
    private final ControlProfileSelector profileSelector;

    private final Object sessionLock = new Object();
    private DefaultManualControlSession activeSession;

    /** Production convenience ctor: {@link Clock#systemUTC()}, a fresh daemon watchdog scheduler, the default timeout. */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail, ReadinessService readinessService) {
        this(assetService, manualControlPort, auditTrail, readinessService, Clock.systemUTC(),
                defaultWatchdogScheduler());
    }

    /** Test/wiring seam: explicit {@link Clock} + {@link ScheduledExecutorService}, default timeout. */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail, ReadinessService readinessService, Clock clock,
                                        ScheduledExecutorService watchdogScheduler) {
        this(assetService, manualControlPort, auditTrail, readinessService, clock, watchdogScheduler,
                DEFAULT_WATCHDOG_TIMEOUT_MS);
    }

    /**
     * As the 6-arg ctor, with an explicit watchdog timeout (e.g. {@code vision.rc.watchdog-timeout-ms}).
     * Resolves built-in profiles only — see the canonical 8-arg ctor.
     */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail, ReadinessService readinessService, Clock clock,
                                        ScheduledExecutorService watchdogScheduler, long watchdogTimeoutMs) {
        this(assetService, manualControlPort, auditTrail, readinessService, clock, watchdogScheduler,
                watchdogTimeoutMs, ControlProfileSelector.builtInOnly());
    }

    /**
     * Canonical ctor, additionally taking the {@link ControlProfileSelector} that decides which
     * layout a session engages with (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C6). Every other
     * constructor here defaults it to {@link ControlProfileSelector#builtInOnly()}, which is exactly
     * the behaviour this service had before operators could save profiles — a caller that does not
     * pass one gets the platform's own layout, never a null map.
     *
     * <p>{@code readinessService} has no such default anywhere: unlike {@code profileSelector}
     * (whose omission falls back to identical, safe, pre-existing behaviour), skipping the {@code
     * rc-relay} readiness gate has no safe equivalent — a no-op implementation would silently
     * reintroduce the exact failure mode this collaborator exists to close (docs/plans/active/FLEET-RADIO-PLAN.md
     * R6). Every constructor below therefore requires it explicitly.
     */
    public DefaultManualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                        AuditTrailPort auditTrail, ReadinessService readinessService, Clock clock,
                                        ScheduledExecutorService watchdogScheduler, long watchdogTimeoutMs,
                                        ControlProfileSelector profileSelector) {
        this.profileSelector = Objects.requireNonNull(profileSelector, "profileSelector must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.manualControlPort = Objects.requireNonNull(manualControlPort, "manualControlPort must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService must not be null");
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
            if (!scope.includes(asset.id(), asset.ownership())) {
                // Honest denial, not a hidden read: mirrors DefaultFlightCommandService's own command
                // gate. The denial is audited -- an authorization refusal is security-relevant.
                audit(actor, assetId, RESULT_DENIED);
                throw new AccessDeniedException(
                        "Asset " + assetId.value() + " is outside your scope; you may not take manual control of it");
            }

            requireRcRelayReady(assetId, actor, scope);

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

            if (link.vehicleKind() == VehicleKind.UNKNOWN) {
                // Unlike the two guards above, the port link IS already open here -- it must be
                // released before this method returns, or the aircraft's failsafe never gets the
                // release-sentinel burst it needs (FLEET-RADIO R2 D3). This IS audited: unlike "no
                // supported device"/"unreachable", a real vehicle was heard and a real relay was
                // opened and then deliberately refused -- that is security/safety-relevant the same
                // way DENIED:out of scope is.
                UnidentifiedReason reason = link.unidentifiedReason().orElse(UnidentifiedReason.NEVER_IDENTIFIED);
                manualControlPort.release(link);
                audit(actor, assetId, RESULT_REFUSED_PREFIX + reason);
                throw new VehicleUnidentifiedException(reason, refusalMessage(reason, assetId));
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

    /**
     * FLEET-RADIO R6: re-checks the {@code rc-relay} feature (GCS-sysid mismatch, {@code RC_OPTIONS}
     * ignoring overrides) from the vehicle's own last-probed profile, right before this engage —
     * never cached from an earlier preflight read, since either fact can change in between. No live
     * link is needed for this: both facts live in the stored {@link
     * com.drones.vision.flight.domain.model.VehicleProfile}, so this runs before any device is
     * resolved or any port is touched, unlike the R2 {@code vehicleKind() == UNKNOWN} guard below,
     * which by necessity needs a link already open.
     *
     * <p>Only {@link FeatureStatus#MISSING} refuses — {@code UNKNOWN} (never probed, or an
     * incomplete profile) is "no evidence either way", not a blocker, matching {@code
     * ReadinessService}'s own "absence of evidence is not evidence of readiness" rule; a vehicle that
     * has simply never been probed must still be able to engage manual control, exactly as it could
     * before this wave.
     */
    private void requireRcRelayReady(AssetId assetId, UserId actor, VisibilityScope scope) {
        ReadinessReport report = readinessService.evaluate(assetId, scope);
        report.features().stream()
                .filter(feature -> feature.featureKey().equals(RC_RELAY_FEATURE_KEY))
                .filter(feature -> feature.status() == FeatureStatus.MISSING)
                .findFirst()
                .ifPresent(feature -> {
                    audit(actor, assetId, RESULT_REFUSED_NOT_READY_PREFIX + RC_RELAY_FEATURE_KEY);
                    throw new IllegalStateException("Asset " + assetId.value()
                            + " is not ready for manual control: " + feature.detail());
                });
    }

    private Optional<Device> firstCommandableDevice(List<Device> devices) {
        return devices.stream()
                .filter(Device::isActive)
                .filter(manualControlPort::supports)
                .findFirst();
    }

    /**
     * The one place the three {@link UnidentifiedReason} causes actually diverge in wording
     * (FLEET-RADIO R2's central design question). Each message names a different operator remedy:
     * {@link UnidentifiedReason#NOT_A_VEHICLE} says there is nothing to fly here at all (check which
     * device was selected); {@link UnidentifiedReason#UNSUPPORTED_VEHICLE} says the vehicle is known
     * and simply unsupported (no amount of retrying helps); {@link UnidentifiedReason#NEVER_IDENTIFIED}
     * is the one case where the operator's own eyes are more informative than this platform's table,
     * and says so.
     */
    private static String refusalMessage(UnidentifiedReason reason, AssetId assetId) {
        String asset = assetId.value().toString();
        return switch (reason) {
            case NOT_A_VEHICLE -> "Asset " + asset + " is not a vehicle: the device on this link is an "
                    + "instrument (for example a gimbal or a ground station), not something to fly or "
                    + "drive. Manual control refused.";
            case UNSUPPORTED_VEHICLE -> "Asset " + asset + " reports a recognized airframe that this "
                    + "platform does not support flying or driving. Manual control refused.";
            case NEVER_IDENTIFIED -> "Asset " + asset + " could not be identified: this platform has "
                    + "never seen the vehicle type it is reporting. If you can see the vehicle, choose "
                    + "its kind explicitly to proceed. Manual control refused.";
        };
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
     * private inner class (mirrors {@link com.drones.vision.perception.application.pipeline.SupervisedPublisher}'s own {@code SupervisingSubscriber})
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
        private final ControlProfile controlProfile;

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
            // Resolved once per engage, from what the vehicle is reporting right now -- not stored
            // per asset, which would go stale exactly when an operator re-flashes the flight
            // controller (P9). The operator's own saved layout for that kind wins over the built-in
            // if they have one; the selector owns that fallback so this session never has to.
            ControlProfile resolved = profileSelector.forSession(actor, link.vehicleKind());
            this.controlProfile = resolved != null ? resolved : ControlProfile.forKind(link.vehicleKind());
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
            RcChannels channels = controlProfile.channelMap().apply(axes, buttons);
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
        public int rateHz() {
            return link.rateHz();
        }

        @Override
        public ControlProfile controlProfile() {
            return controlProfile;
        }

        @Override
        public boolean active() {
            return !released.get();
        }
    }
}
