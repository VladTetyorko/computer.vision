package com.drones.vision.perception.application.pipeline;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.application.directory.AssetDirectoryService;
import com.drones.vision.warehouse.application.usage.UsageSessionService;
import com.drones.vision.flight.application.telemetry.TelemetryService;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import com.drones.vision.perception.application.stream.DefaultStreamService;
import com.drones.vision.perception.application.stream.StreamService;

/**
 * Drives {@link AssetUsage} lifecycle and telemetry sampling from {@link
 * StreamService}'s stream start/stop notifications.
 *
 * <p>The session aggregate itself — opening, folding a sample, closing — is owned by warehouse's
 * {@link UsageSessionService} (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3): this class
 * decides <em>when</em> a session starts/stops and what a sample means for its {@link UsagePhase}
 * (translating flight's {@link FlightPhaseRule} verdicts), but never constructs or persists an {@link
 * AssetUsage} itself any more. Asset/device identity lookups go through warehouse's {@link
 * AssetDirectoryService} rather than a repository port — see that type's own javadoc for why it
 * exists as a narrower seam than {@code AssetService}/{@code DeviceService}. This class therefore
 * imports no {@code *RepositoryPort} from warehouse or flight.
 *
 * <p>A device with no owning asset ({@link AssetDirectoryService#findByDevice} returns empty) is
 * tracked as a no-op: not every registered device need be wrapped by an asset yet, and this
 * collaborator must never fail a stream start/stop over that.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #onStreamStarted(DeviceId, StreamId)} — resolves the device's
 *       owning asset; if this is the asset's <b>first</b> currently-active
 *       device, opens a new {@link AssetUsage} (via {@link UsageSessionService#open}),
 *       stamping it with the given {@code streamId} (docs/plans/done/MVP2-PLAN.md §R, R-a2 — the live
 *       stream that opened it, later joined against {@link com.drones.vision.perception.domain.model.DetectionResult}
 *       for replay), and subscribes to a {@link TelemetrySourcePort} for
 *       each of the asset's {@link Capability#TELEMETRY}-capable devices
 *       that a registered source supports. An asset with several devices
 *       starting streams in succession still opens exactly <b>one</b> usage,
 *       carrying the <b>first</b> device's {@code streamId} — later devices'
 *       stream ids are not recorded.</li>
 *   <li>Each telemetry sample received while a usage is open is persisted via
 *       {@link TelemetryService#record} and folded into the usage's cheap
 *       summary — {@code startPosition} (the first sample carrying a
 *       position), {@code lastPosition} (the most recent one), and {@code
 *       sampleCount} — via {@link #applySample} and {@link UsageSessionService#fold}, kept in one
 *       method per the original port's "single write path" note. The durable {@link
 *       TelemetryService#record} call always happens on the sample's own thread; the summary write
 *       is coalesced onto {@link UsageSummaryBatchSettings}'s size-or-time bound
 *       (docs/plans/done/SCALE-100-PLAN.md S4) — see {@link #registerSummaryUpdate} for why that
 *       split is safe (the summary is a recomputable running counter, not a historical record). The
 *       same method also hands the sample to the configured {@code telemetryObserver}
 *       (docs/plans/done/OPS-CORE-PLAN.md §G — geofence evaluation in practice), right alongside the
 *       existing persist/live-update steps.</li>
 *   <li>{@link #onStreamStopped(DeviceId)} — if this was the asset's
 *       <b>last</b> currently-active device, closes the open usage (via {@link
 *       UsageSessionService#close}) and unsubscribes/closes every telemetry
 *       subscription opened for it — <b>unless</b> the open usage is {@link UsageOrigin#OPERATOR},
 *       in which case <em>both</em> the usage and its telemetry subscriptions stay open: {@link
 *       #engage} exists precisely so a session is commandable independent of any stream
 *       (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P4), so the last stream
 *       stopping must never silently undo it. Only {@link #disengage} ever tears an
 *       {@link UsageOrigin#OPERATOR OPERATOR}-origin usage's telemetry down.</li>
 *   <li>{@link #engage(AssetId)} / {@link #disengage(AssetId)} — the explicit operator verb
 *       (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2) that opens/closes a usage
 *       directly, with <b>no video stream ever required</b> — for a telemetry-only aircraft, or one
 *       being prepared before streaming, that an operator wants to mark "in use." {@link #engage}
 *       carries its own device traffic since docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 *       &sect;3 P4 (closing docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md &sect;B4): it
 *       opens a {@link TelemetrySourcePort} subscription for every one of the asset's {@link
 *       Capability#TELEMETRY}-capable devices, so a telemetry-only aircraft becomes commandable
 *       without ever pairing it with a video device. See each method's own javadoc for how they
 *       resolve colliding with a stream that is already (or still) running.</li>
 * </ul>
 *
 * <h2>{@code onTelemetryDeviceDiscovered} — deleted, not wired (wave R2)</h2>
 * A prior wave (docs/plans/active/DRONE-ONBOARDING-PLAN.md §7, Wave O7) added {@code
 * onTelemetryDeviceDiscovered(DeviceId)} — the same "first active device opens a usage" accounting
 * as {@link #onStreamStarted}, meant to open a usage for a telemetry-capable device with no video
 * stream. It was fully tested but never called from production code (measured
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md finding D2). Wave R2 deletes it rather than
 * wiring it up: {@link #engage} now covers the same need (a telemetry-only aircraft an operator
 * wants marked "in use") more generally — an explicit, restartable, operator-driven verb rather
 * than an automatic one keyed off the mere existence of a telemetry-capable device, which would
 * open a usage the moment any telemetry-capable device were merely <em>registered</em>/probed, not
 * only once an operator actually meant to fly it. Keeping both would also have left two
 * independent, disagreeing ways to open a usage with no stream — this class settles on one.
 * <h2>Phase (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7)</h2>
 * Every {@link AssetUsage} this class opens, folds a sample into, or closes also carries an
 * {@link UsagePhase}, computed by running {@code FlightPhaseRule}
 * (vision-flight) against the sample and mapping its {@link FlightPhase} verdict onto {@code
 * UsagePhase} — the translation this class exists to do, since {@code AssetUsage.phase} lives in
 * vision-warehouse (the pure leaf, which may not depend on flight) while the rule itself lives in
 * flight; see {@link UsagePhaseSettings} for why. {@link #applySample} folds the sample-driven half
 * of the state machine on every sample (zero link age); {@link #deviceStreamStopped} folds the
 * explicit-close half ({@code FlightPhaseRule#onSessionClosed}) when the usage's last active
 * device stops; {@link #evaluateLinkHealth(AssetId)} folds the silence-driven half (going quiet
 * long enough to reach {@code LINK_LOST}/{@code ABANDONED}) on demand, since neither transition can
 * arrive on a sample by definition — nothing in this wave wires it to a live scheduler (that is
 * left to whichever wave wires {@code vision.flight.phase.*}), but the method exists so the
 * transition itself is directly testable. Every one of those three fold sites, plus the two places
 * a usage opens ({@link #deviceStreamStarted}/{@link #engage}), notifies the
 * configured {@link UsagePhaseObserver} exactly once whenever the phase actually changed (or, at
 * open, once for the usage's initial {@link UsagePhase#PREFLIGHT}) — see {@link
 * #notifyPhaseObserver} and {@link UsagePhaseObserver}'s own javadoc
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11: the seam {@code vision-app} hangs the
 * flight passport's PREFLIGHT/POSTFLIGHT snapshot capture off of).
 *
 * <p>Plain class with no framework dependency; constructor-injected ports and
 * collaborators only, consistent with the rest of this module.
 *
 * <h2>Telemetry source supervision (docs/plans/done/MVP2-PLAN.md &sect;S, S-a)</h2>
 * Exactly like {@code DefaultStreamService} does for the video source, {@link #subscribeTelemetry}
 * never subscribes a {@link TelemetrySubscriber} straight to a {@link TelemetrySourcePort#open}
 * result — it wraps it in a {@link SupervisedPublisher} so a telemetry source error/completion is
 * retried with the same capped exponential backoff instead of silently ending telemetry for the
 * rest of the usage. {@link #subscribeTelemetry} has two callers ({@link #deviceStreamStarted} and,
 * since docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P4, {@link #engage}) that can
 * race for the same asset; it claims each device id under {@code tracking}'s monitor before opening
 * anything, so whichever caller runs first wins the open and the other is a safe no-op — see that
 * method's own javadoc. Unlike the video path, this does <b>not</b> publish a {@code PIPELINE_ERROR}
 * event on an outage — this class has no {@link com.drones.vision.platform.EventPublisherPort}
 * (and no {@code StreamId} to publish one against; telemetry is tracked per-asset/device, not
 * per-stream), and {@link TelemetrySubscriber#onError} was already, deliberately, a completely
 * silent no-op before this task (see the Gotchas below) — reconnection is new, the pre-existing
 * silence is not. {@link #unsubscribeTelemetry} calls {@link SupervisedPublisher#stop()}
 * synchronously (cheap, in-memory) so an explicit stream stop cancels any pending reopen
 * immediately, then releases the subscription/source off a background thread — see that method's
 * own javadoc.
 *
 * <h2>Threading</h2>
 * Per-asset state ({@link Tracking}) is reached through a {@link
 * ConcurrentHashMap} keyed by {@link AssetId}, and every read/mutate sequence
 * against one asset's tracking state is synchronized on that asset's {@code
 * Tracking} instance, so concurrent start/stop calls for different devices of
 * the same asset (or concurrent telemetry samples arriving on adapter
 * threads) never race the first-open/last-close decision or the usage
 * summary update.
 */
public final class UsageTracker {

    private static final System.Logger LOG = System.getLogger(UsageTracker.class.getName());

    private final AssetDirectoryService assetDirectory;
    private final UsageSessionService usageSessionService;
    private final TelemetryService telemetryService;
    private final List<TelemetrySourcePort> telemetrySources;
    private final TelemetryLiveUpdatePort liveUpdatePublisherPort;
    private final BiConsumer<AssetId, Telemetry> telemetryObserver;
    private final long sourceInitialBackoffNanos;
    private final long sourceMaxBackoffNanos;
    private final UsageSummaryBatchSettings summaryBatchSettings;
    private final UsagePhaseSettings phaseSettings;
    private final UsagePhaseObserver usagePhaseObserver;

    /** One dedicated daemon thread scheduling every telemetry subscription's reopen retries; see {@code DefaultStreamService}'s own field of the same shape for why this is shared rather than per-subscription. */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telemetry-supervisor");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentHashMap<AssetId, Tracking> trackingByAsset = new ConcurrentHashMap<>();

    /**
     * The single canonical constructor (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md Finding
     * R1) — every collaborator beyond the mandatory services and telemetry sources is bundled into
     * {@code settings}; see {@link UsageTrackerSettings} for what each field controls and {@link
     * UsageTrackerSettings#defaults()} for the behavior every pre-R1 shortest constructor used to
     * default to. {@code assetDirectory}/{@code usageSessionService} (warehouse) and {@code
     * telemetryService} (flight) replace the four repository ports this class used to hold directly
     * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md Finding R3/R5, wave R3) — see this class's
     * own javadoc.
     */
    public UsageTracker(AssetDirectoryService assetDirectory, UsageSessionService usageSessionService,
                         TelemetryService telemetryService, List<TelemetrySourcePort> telemetrySources,
                         UsageTrackerSettings settings) {
        this.assetDirectory = Objects.requireNonNull(assetDirectory, "assetDirectory must not be null");
        this.usageSessionService = Objects.requireNonNull(usageSessionService, "usageSessionService must not be null");
        this.telemetryService = Objects.requireNonNull(telemetryService, "telemetryService must not be null");
        Objects.requireNonNull(telemetrySources, "telemetrySources must not be null");
        this.telemetrySources = List.copyOf(telemetrySources);
        Objects.requireNonNull(settings, "settings must not be null");
        this.liveUpdatePublisherPort = settings.liveUpdatePublisherPort().orElse(null); // nullable: no live-update announcements when absent
        this.telemetryObserver = settings.telemetryObserver().orElse(null); // nullable: samples are merely persisted/announced when absent
        this.sourceInitialBackoffNanos = settings.sourceInitialBackoffNanos();
        this.sourceMaxBackoffNanos = settings.sourceMaxBackoffNanos();
        this.summaryBatchSettings = settings.summaryBatchSettings();
        this.phaseSettings = settings.phaseSettings();
        this.usagePhaseObserver = settings.usagePhaseObserver();
    }

    /**
     * Notifies the tracker that a device's stream has started.
     *
     * @param deviceId the device whose stream started
     * @param streamId the id of the stream that started; recorded onto the {@link AssetUsage}
     *                 only when this call is the one that opens it (the asset's first
     *                 currently-active device) — see the class javadoc
     */
    public void onStreamStarted(DeviceId deviceId, StreamId streamId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(streamId, "streamId must not be null");
        assetDirectory.findByDevice(deviceId).ifPresent(asset -> deviceStreamStarted(asset, streamId));
    }

    /**
     * Notifies the tracker that a device's stream has stopped.
     *
     * @param deviceId the device whose stream stopped
     */
    public void onStreamStopped(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        assetDirectory.findByDevice(deviceId).ifPresent(this::deviceStreamStopped);
    }

    /**
     * Opens (or promotes) a usage for {@code assetId} directly, with no video stream ever required
     * — the explicit operator verb docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2/R2
     * introduces for a telemetry-only aircraft, or one being prepared before streaming, that an
     * operator wants to mark "in use" without a stream ever starting. Stamped with {@link
     * UsageOrigin#OPERATOR} and no {@code streamId} (same "or {@code null} for a legacy/streamless
     * usage" honesty {@link AssetUsage#streamId()} already documents).
     *
     * <h2>Telemetry (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P4, closes
     * docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md &sect;B4)</h2>
     * Once the usage above is resolved, this method opens a {@link TelemetrySourcePort} subscription
     * — via the same {@link #subscribeTelemetry} path {@link #deviceStreamStarted} uses — for every
     * one of the asset's {@link Capability#TELEMETRY}-capable devices a registered source supports.
     * Before this, a telemetry-only aircraft's MAVLink gateway only ever opened as a side effect of a
     * <em>video</em> stream starting, so it could never become commandable without being paired with
     * a video device purely to trigger that subscription (the workaround B4 documented). {@link
     * #subscribeTelemetry} is idempotent per device: calling it here never double-opens a device a
     * running stream already subscribed, and {@link #deviceStreamStarted}'s own call likewise never
     * double-opens one this method already claimed — see that method's own javadoc.
     *
     * <h2>Collision: a stream is already running</h2>
     * If the asset already has an open usage whose origin is not already {@link
     * UsageOrigin#OPERATOR} (i.e. a video stream opened it), this call <b>promotes</b> that usage
     * to {@link UsageOrigin#OPERATOR} rather than opening a second one or rejecting the call — an
     * operator explicitly asserting "I am using this asset" is a stronger, more specific signal
     * than the side effect that happened to open the session first, and promoting is what makes
     * {@link #onStreamStopped}'s later no-op-on-OPERATOR guard the correct behavior instead of a
     * bug: without promotion, a stream stopping while the operator considers the asset engaged
     * would silently end the session out from under them. The pre-existing {@code streamId} is
     * left untouched either way — {@link AssetUsage#streamId()} is recorded once, at genuine open
     * time, and never changed afterward (see that field's own javadoc); a promoted usage keeps
     * whichever stream opened it as an honest historical fact.
     *
     * <p>Idempotent: engaging an asset that is already {@link UsageOrigin#OPERATOR}-engaged returns
     * the existing usage unchanged; its telemetry subscriptions (already open) are left exactly as
     * they were.
     *
     * @param assetId the asset to engage
     * @return the open usage, now attributed to {@link UsageOrigin#OPERATOR} (newly opened,
     *         promoted, or already engaged)
     * @throws NullPointerException     if {@code assetId} is {@code null}
     * @throws NoSuchElementException   if no asset has that id
     * @throws IllegalStateException    if the asset is not in service
     */
    public AssetUsage engage(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Asset asset = requireActiveAsset(assetId); // devices() needed below to open telemetry -- see class javadoc
        Tracking tracking = trackingByAsset.computeIfAbsent(assetId, id -> new Tracking());
        AssetUsage result;
        UsageId openedUsageId = null;
        UsagePhase openedPhase = null;
        boolean openedNow = false;
        AssetUsage toPersist = null;
        synchronized (tracking) {
            if (tracking.usage == null) {
                tracking.usage = usageSessionService.open(assetId, null, UsageOrigin.OPERATOR,
                        phaseSettings.clock().get());
                openedUsageId = tracking.usage.id();
                openedPhase = tracking.usage.phase();
                openedNow = true;
                result = tracking.usage;
            } else if (tracking.usage.origin() != UsageOrigin.OPERATOR) {
                tracking.usage = tracking.usage.withOrigin(UsageOrigin.OPERATOR);
                toPersist = tracking.usage;
                result = tracking.usage;
            } else {
                result = tracking.usage; // already engaged: idempotent no-op
            }
        }
        if (toPersist != null) {
            result = usageSessionService.save(toPersist);
        }
        if (openedNow) {
            notifyPhaseObserver(assetId, openedUsageId, null, openedPhase);
        }
        // docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4, closes
        // docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §B4: engage carries its own
        // telemetry traffic now, independent of any video stream -- subscribeTelemetry claims each
        // device id before opening it, so this is a safe no-op for a device a running stream (or an
        // earlier engage call) already subscribed.
        subscribeTelemetry(asset, tracking);
        return result;
    }

    /**
     * Ends {@code assetId}'s operator-engaged usage — the inverse of {@link #engage}
     * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2/R2).
     *
     * <h2>Collision: a stream is still running</h2>
     * If a device/stream is still active for the asset when the operator disengages, this does
     * <b>not</b> close the usage: doing so would leave the still-running stream's telemetry with no
     * usage to fold into, and leave nothing to ever close it once the stream eventually does stop.
     * Instead it <b>demotes</b> the usage back to {@link UsageOrigin#STREAM} — the operator's claim
     * on the session is withdrawn, but the session itself lives on exactly as if the stream had
     * opened it, which (from the runtime's point of view) is now the whole truth: nothing but the
     * stream is still watching it. Every telemetry subscription this call's {@link #engage} opened
     * is left exactly as it was — a running stream may still be reading from the very same
     * subscription (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P4), so nothing here
     * ever tears one down while a device is active. The ordinary stream-driven close in {@link
     * #onStreamStopped} takes over from there once the last device actually stops. Only when no
     * device is currently active does this method close the usage outright, via the same {@code
     * FlightPhaseRule#onSessionClosed} transform {@link #onStreamStopped} uses — which also
     * unsubscribes/closes every telemetry subscription still open for the asset, exactly like an
     * ordinary stream-driven close does.
     *
     * <p>A no-op — returns {@link Optional#empty()} — if the asset has no currently open usage, or
     * its open usage was not opened/promoted by {@link #engage} in the first place (an ordinary
     * STREAM-origin usage the operator never touched is simply not this method's concern); a caller
     * need not check {@link #engage} was ever called before calling this.
     *
     * @param assetId the asset to disengage
     * @return the usage as left by this call — closed, or demoted and still open — or {@link
     *         Optional#empty()} if there was no operator-engaged usage to act on
     * @throws NullPointerException   if {@code assetId} is {@code null}
     * @throws NoSuchElementException if no asset has that id
     */
    public Optional<AssetUsage> disengage(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        assetDirectory.find(assetId).orElseThrow(() -> new NoSuchElementException("Unknown asset: " + assetId.value()));
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return Optional.empty();
        }
        AssetUsage demoted = null;
        AssetUsage usageToClose = null;
        UsageId closedUsageId = null;
        UsagePhase closedPhase = null;
        UsagePhase previousPhase = null;
        UsagePhase closedPhaseFired = null;
        Instant endedAt = null;
        AssetUsage result = null;
        boolean nothingToDisengage = false;
        synchronized (tracking) {
            if (tracking.usage == null || tracking.usage.origin() != UsageOrigin.OPERATOR) {
                nothingToDisengage = true;
            } else if (tracking.activeDevices > 0) {
                tracking.usage = tracking.usage.withOrigin(UsageOrigin.STREAM);
                demoted = tracking.usage;
            } else {
                UsagePhase priorPhase = tracking.usage.phase();
                closedPhase = toUsagePhase(phaseSettings.rule().onSessionClosed(toFlightPhase(priorPhase)));
                closedUsageId = tracking.usage.id();
                usageToClose = tracking.usage;
                endedAt = phaseSettings.clock().get();
                tracking.usage = null;
                tracking.unflushedSummaryUpdates = 0;
                cancelPendingSummaryFlush(tracking);
                if (closedPhase != priorPhase) {
                    previousPhase = priorPhase;
                    closedPhaseFired = closedPhase;
                }
            }
        }
        if (nothingToDisengage) {
            return Optional.empty();
        }
        if (demoted != null) {
            result = usageSessionService.save(demoted);
        }
        if (usageToClose != null) {
            unsubscribeTelemetry(tracking);
            result = usageSessionService.close(usageToClose, closedPhase, endedAt);
            if (closedPhaseFired != null) {
                notifyPhaseObserver(assetId, closedUsageId, previousPhase, closedPhaseFired);
            }
        }
        return Optional.ofNullable(result);
    }

    /**
     * Resolves {@code assetId}, rejecting an unknown or deactivated asset — the same "asset must
     * exist and be in service" guard {@code DefaultAssetStreamService#require}/{@code #startStream}
     * apply before starting a stream, reused here so {@link #engage} refuses exactly the same way a
     * stream start would.
     */
    private Asset requireActiveAsset(AssetId assetId) {
        Asset asset = assetDirectory.find(assetId)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + assetId.value()));
        if (!asset.isActive()) {
            throw new IllegalStateException("Asset is not in service: " + asset.displayName());
        }
        return asset;
    }

    /**
     * Re-evaluates {@code assetId}'s open usage phase against how long it has been since the last
     * telemetry sample, without requiring a new sample to arrive
     * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3's silence-driven transitions — {@code
     * IN_FLIGHT -> LINK_LOST} and {@code LINK_LOST -> ABANDONED} — neither of which any sample can
     * carry, since both fire on the <em>absence</em> of one). A no-op if the asset has no currently
     * open usage, or the open usage has never received a sample (link age is only meaningful once
     * there has been a first sample to go quiet since).
     *
     * <p>Not wired to a live scheduler by this wave (O7) — see this class's MODULE.md for the
     * deferred production wiring; this method exists so the silence-driven transition itself is
     * directly testable without waiting out a real silence/abandon window.
     *
     * @param assetId the asset to re-evaluate
     */
    public void evaluateLinkHealth(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return;
        }
        AssetUsage updated = null;
        UsageId usageId = null;
        UsagePhase previousPhase = null;
        UsagePhase nextUsagePhase = null;
        synchronized (tracking) {
            if (tracking.usage == null || tracking.lastSample == null) {
                return;
            }
            Instant now = phaseSettings.clock().get();
            Duration linkAge = Duration.between(tracking.lastSample.at(), now);
            if (linkAge.isNegative()) {
                linkAge = Duration.ZERO;
            }
            UsagePhase priorPhase = tracking.usage.phase();
            FlightPhase nextPhase = phaseSettings.rule().nextPhase(toFlightPhase(priorPhase),
                    tracking.lastSample.flightState(), linkAge, tracking.activeVideoStreams);
            UsagePhase mappedPhase = toUsagePhase(nextPhase);
            if (mappedPhase != priorPhase) {
                usageId = tracking.usage.id();
                updated = usageSessionService.updatePhase(tracking.usage, mappedPhase);
                tracking.usage = updated;
                previousPhase = priorPhase;
                nextUsagePhase = mappedPhase;
            }
        }
        if (updated != null) {
            usageSessionService.save(updated);
            notifyPhaseObserver(assetId, usageId, previousPhase, nextUsagePhase);
        }
    }

    /**
     * Resolves the asset that owns {@code deviceId}, if any — best-effort, used by {@code
     * DetectionEventEngine} (docs/plans/done/MVP2-PLAN.md §E, E-a) to stamp a {@code DetectionEvent}'s {@code
     * assetId} at open time.
     *
     * @param deviceId the device to resolve
     * @return the owning asset's id, or {@link Optional#empty()} if the device has no owning asset
     */
    public Optional<AssetId> resolveAsset(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return assetDirectory.findByDevice(deviceId).map(Asset::id);
    }

    /**
     * Best-effort freshest known position for {@code assetId}'s currently open usage
     * (docs/plans/done/MVP2-PLAN.md §E, E-a) — the same {@code lastPosition} an {@link AssetUsage}
     * accumulates as telemetry samples arrive (see {@link #applySample}), read back rather than
     * queried fresh from flight's telemetry trail directly, which has no "give me the
     * latest sample" shape to ask for one. Empty when the asset has no currently open usage, or
     * the open usage has not yet received a positioned telemetry sample.
     *
     * @param assetId the asset to inspect
     * @return the freshest position, or {@link Optional#empty()} if unavailable
     */
    public Optional<GeoPosition> latestPosition(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return Optional.empty();
        }
        synchronized (tracking) {
            return tracking.usage == null ? Optional.empty() : Optional.ofNullable(tracking.usage.lastPosition());
        }
    }

    /**
     * The most recent telemetry sample ever received for {@code assetId} (docs/plans/done/MVP3-PLAN.md C-a) —
     * used to derive a fleet-summary attention row's battery percent and telemetry staleness.
     *
     * <p>Deliberately <b>not</b> scoped to the currently open usage the way {@link
     * #latestPosition(AssetId)} is: that method answers "where is it right now", which is honestly
     * unknowable once streaming stops, but staleness ("how long since we last heard from this
     * asset") is most useful exactly once an asset has gone quiet — so this method keeps returning
     * the last sample it ever saw for the asset rather than going empty the moment its usage closes.
     * The underlying {@link Tracking} instance is never evicted from {@link #trackingByAsset} once
     * created, so the sample survives across the asset's whole tracked lifetime, not just one usage.
     *
     * @param assetId the asset to inspect
     * @return the freshest sample, or {@link Optional#empty()} if the asset has never reported
     *         telemetry (including one that has never streamed at all)
     */
    public Optional<Telemetry> latestTelemetry(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return Optional.empty();
        }
        synchronized (tracking) {
            return Optional.ofNullable(tracking.lastSample);
        }
    }

    private void deviceStreamStarted(Asset asset, StreamId streamId) {
        Tracking tracking = trackingByAsset.computeIfAbsent(asset.id(), id -> new Tracking());
        boolean usageOpenedNow;
        boolean firstActiveDevice;
        UsageId openedUsageId = null;
        UsagePhase openedPhase = null;
        synchronized (tracking) {
            tracking.activeDevices++;
            tracking.activeVideoStreams++;
            firstActiveDevice = tracking.activeDevices == 1;
            // docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2/R2: a usage may already be open
            // because the operator engaged before any device went active (see #engage) -- opening a
            // usage and a device becoming the asset's first active one are no longer the same event,
            // so each gets its own boolean rather than reusing "first active device" for both.
            usageOpenedNow = tracking.usage == null;
            if (usageOpenedNow) {
                tracking.usage =
                        usageSessionService.open(asset.id(), streamId, UsageOrigin.STREAM, phaseSettings.clock().get());
                openedUsageId = tracking.usage.id();
                openedPhase = tracking.usage.phase();
            }
        }
        if (firstActiveDevice) {
            subscribeTelemetry(asset, tracking);
        }
        if (usageOpenedNow) {
            notifyPhaseObserver(asset.id(), openedUsageId, null, openedPhase);
        }
    }

    private void deviceStreamStopped(Asset asset) {
        Tracking tracking = trackingByAsset.get(asset.id());
        if (tracking == null) {
            return;
        }
        boolean allDevicesStopped;
        boolean tearDownTelemetry;
        AssetUsage usageToClose = null;
        UsageId closedUsageId = null;
        UsagePhase closedPhase = null;
        UsagePhase previousPhase = null;
        UsagePhase closedPhaseFired = null;
        Instant endedAt = null;
        synchronized (tracking) {
            if (tracking.activeDevices > 0) {
                tracking.activeDevices--;
            }
            if (tracking.activeVideoStreams > 0) {
                tracking.activeVideoStreams--;
            }
            allDevicesStopped = tracking.activeDevices == 0;
            if (allDevicesStopped && tracking.usage != null) {
                if (tracking.usage.origin() == UsageOrigin.OPERATOR) {
                    // docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2/R2 collision rule: an
                    // operator-engaged usage survives every device going inactive -- only #disengage
                    // closes it. docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4: its
                    // telemetry subscriptions now survive too (see tearDownTelemetry below) -- #engage
                    // exists precisely so commandability outlives any one stream, so the last stream
                    // stopping must never silently undo it.
                } else {
                    // docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3: the explicit-close half of the
                    // state machine -- IN_FLIGHT/LINK_LOST both close to ABANDONED (the platform stopped
                    // watching while the aircraft was, so far as it knew, airborne), everything else to
                    // CLOSED. The transform + persist itself is warehouse's job (UsageSessionService#close,
                    // docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3); this class only decides
                    // *which* phase to close to.
                    UsagePhase priorPhase = tracking.usage.phase();
                    closedPhase = toUsagePhase(phaseSettings.rule().onSessionClosed(toFlightPhase(priorPhase)));
                    closedUsageId = tracking.usage.id();
                    usageToClose = tracking.usage;
                    endedAt = phaseSettings.clock().get();
                    tracking.usage = null;
                    // the close below is written synchronously; nothing scheduled for the coalesced
                    // summary write should still fire against a now-null tracking.usage afterwards.
                    tracking.unflushedSummaryUpdates = 0;
                    cancelPendingSummaryFlush(tracking);
                    if (closedPhase != priorPhase) {
                        previousPhase = priorPhase;
                        closedPhaseFired = closedPhase;
                    }
                }
            }
            // docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4: read tracking.usage's
            // CURRENT state (after the branch above may have nulled it) -- telemetry only tears down
            // once nothing needs it any more: no active device AND no surviving OPERATOR-origin
            // (#engage'd) usage. Only #disengage ever closes an OPERATOR usage's telemetry.
            tearDownTelemetry = allDevicesStopped
                    && !(tracking.usage != null && tracking.usage.origin() == UsageOrigin.OPERATOR);
        }
        if (tearDownTelemetry) {
            unsubscribeTelemetry(tracking);
        }
        if (usageToClose != null) {
            usageSessionService.close(usageToClose, closedPhase, endedAt);
            if (closedPhaseFired != null) {
                notifyPhaseObserver(asset.id(), closedUsageId, previousPhase, closedPhaseFired);
            }
        }
    }

    /**
     * Opens a {@link TelemetrySourcePort} subscription for every one of {@code asset}'s {@link
     * Capability#TELEMETRY}-capable devices a registered source supports — called from both {@link
     * #deviceStreamStarted} (on the asset's first currently-active device) and {@link #engage}
     * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P4), which can reach the same
     * asset in either order or concurrently.
     *
     * <p><b>Idempotent per device.</b> Each {@code deviceId} is claimed under {@code tracking}'s
     * monitor (a plain {@link Set#add} — fast, in-memory) before this method does anything else with
     * it; only the caller that wins the claim actually opens a subscription. This is what lets {@link
     * #engage} and {@link #deviceStreamStarted} call this method for the same asset in any order —
     * whichever runs second finds every device it would have opened already claimed and does
     * nothing — without either one needing to know which of the two opened what. The claim is
     * released only by {@link #unsubscribeTelemetry}, alongside the subscriptions themselves.
     */
    private void subscribeTelemetry(Asset asset, Tracking tracking) {
        for (DeviceId deviceId : asset.devices()) {
            boolean claimed;
            synchronized (tracking) {
                claimed = tracking.telemetryDeviceIds.add(deviceId);
            }
            if (!claimed) {
                continue; // already open, or claimed by the other caller racing for the same asset
            }
            Optional<Device> deviceOpt = assetDirectory.findDevice(deviceId);
            if (deviceOpt.isEmpty()) {
                continue;
            }
            Device device = deviceOpt.get();
            if (!device.capabilities().contains(Capability.TELEMETRY)) {
                continue;
            }
            for (TelemetrySourcePort source : telemetrySources) {
                if (source.supports(device)) {
                    TelemetrySubscriber subscriber = new TelemetrySubscriber(asset.id());
                    // docs/plans/done/MVP2-PLAN.md §S, S-a: supervised exactly like the video source (see this
                    // class's own javadoc for why no PIPELINE_ERROR is published here).
                    SupervisedPublisher<Telemetry> supervised = new SupervisedPublisher<>(() -> source.open(device),
                            cause -> { }, retryScheduler, sourceInitialBackoffNanos, sourceMaxBackoffNanos);
                    supervised.subscribe(subscriber);
                    synchronized (tracking) {
                        tracking.telemetrySubscriptions.add(
                                new TelemetrySubscription(source, deviceId, subscriber, supervised));
                    }
                    break; // first matching source wins, mirroring VideoSourceRegistry's selection rule
                }
            }
        }
    }

    /**
     * Unsubscribes and releases every telemetry subscription currently open for the asset —
     * called once nothing (neither an active device nor an {@link UsageOrigin#OPERATOR}-origin
     * usage {@link #engage} opened) still needs them, see {@link #deviceStreamStopped}/{@link
     * #disengage}'s own call sites for exactly when that is.
     * {@link SupervisedPublisher#stop()} runs synchronously here — cheap, in-memory, and it must
     * happen before this method returns so a pending scheduled reopen can never race a legitimate
     * close (docs/plans/done/MVP2-PLAN.md §S, S-a, same reasoning as {@code DefaultStreamService#stop}). The
     * actual {@link Flow.Subscription#cancel()}/{@link TelemetrySourcePort#close} calls run on a
     * background thread instead, for the same reason {@code DefaultStreamService} defers its own
     * source teardown: an adapter's {@code close()} is not guaranteed to be fast, and this method is
     * itself called synchronously from {@code DefaultStreamService#stop}, which must return promptly.
     * Also clears {@link Tracking#telemetryDeviceIds} — {@link #subscribeTelemetry}'s per-device
     * claim set — so a later re-engage/restream starts every device's claim fresh.
     */
    private void unsubscribeTelemetry(Tracking tracking) {
        List<TelemetrySubscription> subscriptions;
        synchronized (tracking) {
            subscriptions = List.copyOf(tracking.telemetrySubscriptions);
            tracking.telemetrySubscriptions.clear();
            tracking.telemetryDeviceIds.clear();
        }
        for (TelemetrySubscription subscription : subscriptions) {
            subscription.supervisedPublisher().stop();
        }
        if (subscriptions.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("telemetry-teardown").start(() -> {
            for (TelemetrySubscription subscription : subscriptions) {
                subscription.subscriber().cancel();
                subscription.source().close(subscription.deviceId());
            }
        });
    }

    /**
     * Persists one telemetry sample and folds it into its usage's summary —
     * the single write path for both, per the original port's javadoc note about
     * later batching.
     */
    private void applySample(AssetId assetId, Telemetry sample) {
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return;
        }
        UsageId usageId;
        AssetUsage updated;
        UsagePhase observedPreviousPhase = null;
        UsagePhase observedNextPhase = null;
        synchronized (tracking) {
            if (tracking.usage == null) {
                return; // usage already closed: drop a straggling sample rather than reopen it
            }
            usageId = tracking.usage.id();
            GeoPosition position = toPosition(sample);
            // docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3: the sample-driven half of the state
            // machine -- zero link age (a sample just arrived), streamCount scoped to genuine video
            // streams only (never activeDevices, which also counts a device active with no video
            // stream of its own, and is untouched entirely by an operator #engage -- see
            // Tracking#activeVideoStreams' own note).
            UsagePhase priorPhase = tracking.usage.phase();
            FlightPhase nextPhase = phaseSettings.rule().nextPhase(toFlightPhase(priorPhase), sample.flightState(),
                    Duration.ZERO, tracking.activeVideoStreams);
            UsagePhase mappedPhase = toUsagePhase(nextPhase);
            updated = usageSessionService.fold(tracking.usage, position, mappedPhase);
            tracking.usage = updated;
            tracking.lastSample = sample; // docs/plans/done/MVP3-PLAN.md C-a: outlives the usage, see latestTelemetry's javadoc
            if (mappedPhase != priorPhase) { // docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4/O11: fire only on a genuine transition
                observedPreviousPhase = priorPhase;
                observedNextPhase = mappedPhase;
            }
        }
        telemetryService.record(usageId, sample);
        registerSummaryUpdate(assetId, tracking, updated);
        if (liveUpdatePublisherPort != null) { // docs/plans/done/REALTIME-PLAN.md §4
            liveUpdatePublisherPort.publishTelemetryAppended(assetId, sample);
        }
        if (telemetryObserver != null) { // docs/plans/done/OPS-CORE-PLAN.md §G — geofence, wired in vision-app
            telemetryObserver.accept(assetId, sample);
        }
        if (observedNextPhase != null) {
            notifyPhaseObserver(assetId, usageId, observedPreviousPhase, observedNextPhase);
        }
    }

    /**
     * Writes (or defers) the folded {@code updated} summary per {@link #summaryBatchSettings}
     * (docs/plans/done/SCALE-100-PLAN.md S4 item 3). Safe to defer, unlike the durable {@link
     * TelemetryService#record} call right before it: this write is a recomputable running
     * counter on {@link AssetUsage}, not a historical record, so losing an unflushed one to a crash
     * only leaves the summary briefly stale — the very next sample folds a fresh one from the same
     * in-memory {@link Tracking#usage}, not from whatever was last durably written.
     *
     * @param assetId  the tracked asset, to key the scheduled flush by
     * @param tracking {@code assetId}'s tracking state; must already be the one {@code updated} was
     *                 folded into
     * @param updated  the just-folded summary; written as-is only in {@linkplain
     *                 UsageSummaryBatchSettings#isImmediate() immediate} mode, where it is by
     *                 definition also the freshest. Every batched path writes {@link
     *                 Tracking#usage} instead — see below and {@link #flushUsageSummary}.
     */
    private void registerSummaryUpdate(AssetId assetId, Tracking tracking, AssetUsage updated) {
        if (summaryBatchSettings.isImmediate()) {
            usageSessionService.save(updated);
            return;
        }
        AssetUsage toWrite = null;
        synchronized (tracking) {
            tracking.unflushedSummaryUpdates++;
            if (tracking.pendingSummaryFlush == null) {
                // first unflushed fold since the last write: arm the time bound so the summary
                // catches up on its own even if no further sample ever arrives for this usage.
                tracking.pendingSummaryFlush = retryScheduler.schedule(() -> flushUsageSummary(assetId),
                        summaryBatchSettings.batchWindowMillis(), TimeUnit.MILLISECONDS);
            }
            if (tracking.unflushedSummaryUpdates >= summaryBatchSettings.batchSizeSamples()) {
                tracking.unflushedSummaryUpdates = 0;
                cancelPendingSummaryFlush(tracking);
                // the freshest fold, not this thread's `updated` — two subscription threads can
                // reach the size bound out of order, and CLAUDE.md rule 9 says the newest wins.
                toWrite = tracking.usage;
            }
        }
        if (toWrite != null) {
            usageSessionService.save(toWrite);
        }
    }

    /**
     * Fired by {@link #retryScheduler} once {@link UsageSummaryBatchSettings#batchWindowMillis()}
     * elapses. Reads {@link Tracking#usage} fresh rather than closing over the snapshot that armed
     * the timer (CLAUDE.md rule 9: the newest fold wins, not whichever one happened to schedule
     * this) — and no-ops if the size bound already flushed everything, or the usage has since
     * closed, in either case leaving nothing for this run to do.
     */
    private void flushUsageSummary(AssetId assetId) {
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return;
        }
        AssetUsage toWrite;
        synchronized (tracking) {
            tracking.pendingSummaryFlush = null;
            if (tracking.unflushedSummaryUpdates == 0) {
                return;
            }
            tracking.unflushedSummaryUpdates = 0;
            toWrite = tracking.usage;
        }
        if (toWrite != null) {
            usageSessionService.save(toWrite);
        }
    }

    /** Caller must hold {@code tracking}'s monitor. */
    private static void cancelPendingSummaryFlush(Tracking tracking) {
        if (tracking.pendingSummaryFlush != null) {
            tracking.pendingSummaryFlush.cancel(false);
            tracking.pendingSummaryFlush = null;
        }
    }

    private static GeoPosition toPosition(Telemetry sample) {
        if (sample.latitude() == null || sample.longitude() == null) {
            return null;
        }
        return new GeoPosition(sample.latitude(), sample.longitude(), sample.altitudeMeters());
    }

    /**
     * Maps warehouse's persisted {@link UsagePhase} onto flight's {@link FlightPhase} so {@code
     * FlightPhaseRule} can run against it — see {@link UsagePhaseSettings} for why this translation
     * lives here rather than in either context module directly. Exhaustive by construction: both
     * enums are deliberately kept name-parallel (see {@code UsagePhase}'s own javadoc), and the
     * compiler enforces it the moment either one gains/loses a value.
     */
    private static FlightPhase toFlightPhase(UsagePhase phase) {
        return switch (phase) {
            case PREFLIGHT -> FlightPhase.PREFLIGHT;
            case IN_FLIGHT -> FlightPhase.IN_FLIGHT;
            case LINK_LOST -> FlightPhase.LINK_LOST;
            case POSTFLIGHT -> FlightPhase.POSTFLIGHT;
            case ABANDONED -> FlightPhase.ABANDONED;
            case CLOSED -> FlightPhase.CLOSED;
        };
    }

    /** The inverse of {@link #toFlightPhase(UsagePhase)}, run on every {@code FlightPhaseRule} verdict before it is folded onto an {@link AssetUsage}. */
    private static UsagePhase toUsagePhase(FlightPhase phase) {
        return switch (phase) {
            case PREFLIGHT -> UsagePhase.PREFLIGHT;
            case IN_FLIGHT -> UsagePhase.IN_FLIGHT;
            case LINK_LOST -> UsagePhase.LINK_LOST;
            case POSTFLIGHT -> UsagePhase.POSTFLIGHT;
            case ABANDONED -> UsagePhase.ABANDONED;
            case CLOSED -> UsagePhase.CLOSED;
        };
    }

    /**
     * Invokes {@link #usagePhaseObserver} for one open/transition, called with the lock already
     * released (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11) — mirrors how {@link
     * #telemetryObserver} is invoked outside {@code tracking}'s monitor in {@link #applySample}, so
     * an observer that takes a while (or one day acquires its own lock) can never deadlock against
     * this class's own per-asset synchronization.
     *
     * <p>Catches and logs every {@link RuntimeException} the observer raises — see {@link
     * UsagePhaseObserver}'s own javadoc for why a misbehaving observer must never break the
     * stream/telemetry event that triggered it.
     *
     * @param previous {@code null} exactly when {@code next} is the usage's opening phase
     */
    private void notifyPhaseObserver(AssetId assetId, UsageId usageId, UsagePhase previous, UsagePhase next) {
        try {
            usagePhaseObserver.onPhaseChanged(assetId, usageId, previous, next, phaseSettings.clock().get());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "usage " + usageId.value() + " phase observer threw for "
                    + previous + " -> " + next + ": " + e.getMessage(), e);
        }
    }

    /** Per-asset mutable tracking state; every access is synchronized on the instance itself. */
    private static final class Tracking {
        private int activeDevices;
        /** docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3: video streams only -- {@code FlightPhaseRule#nextPhase}'s {@code streamCount} contract, kept separate from {@link #activeDevices} so a telemetry-only asset's phase math never sees a nonzero stream count it never had. */
        private int activeVideoStreams;
        private AssetUsage usage;
        /** docs/plans/done/MVP3-PLAN.md C-a: the freshest sample ever seen, kept even once {@link #usage} closes — see {@link #latestTelemetry(AssetId)}. */
        private Telemetry lastSample;
        private final List<TelemetrySubscription> telemetrySubscriptions = new ArrayList<>();
        /** docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4: device ids currently claimed/open for telemetry -- guards {@code UsageTracker#subscribeTelemetry} against opening the same device's {@code TelemetrySourcePort} twice when {@code #engage} and a device stream starting race for the same asset. Cleared alongside {@link #telemetrySubscriptions} by {@code UsageTracker#unsubscribeTelemetry}. */
        private final Set<DeviceId> telemetryDeviceIds = new HashSet<>();
        /** docs/plans/done/SCALE-100-PLAN.md S4: folds into {@link #usage} not yet written via {@code usageSessionService.save}. */
        private int unflushedSummaryUpdates;
        /** docs/plans/done/SCALE-100-PLAN.md S4: the armed time-bound summary flush, if any — see {@code UsageTracker#registerSummaryUpdate}. */
        private ScheduledFuture<?> pendingSummaryFlush;
    }

    private record TelemetrySubscription(TelemetrySourcePort source, DeviceId deviceId,
                                          TelemetrySubscriber subscriber, SupervisedPublisher<Telemetry> supervisedPublisher) {
    }

    /**
     * Requests one sample at a time (mirroring {@code StreamPipeline}'s
     * request(1)-at-a-time style) and forwards each to {@link
     * #applySample(AssetId, Telemetry)}. A source-side error simply stops
     * this subscription's flow of samples; it must never propagate into the
     * stream start/stop call that (indirectly) created it.
     *
     * <p>{@code onSubscribe} is called again, updating {@link #subscription}, every time {@link
     * SupervisedPublisher} (docs/plans/done/MVP2-PLAN.md §S, S-a) reopens the source after an outage — this
     * class needs no reconnect logic of its own, it just keeps receiving the normal {@code
     * onSubscribe}/{@code onNext} traffic the wrapper forwards.
     */
    private final class TelemetrySubscriber implements Flow.Subscriber<Telemetry> {
        private final AssetId assetId;
        private volatile Flow.Subscription subscription;

        TelemetrySubscriber(AssetId assetId) {
            this.assetId = assetId;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(Telemetry item) {
            applySample(assetId, item);
            Flow.Subscription s = subscription;
            if (s != null) {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // docs/plans/done/MVP2-PLAN.md §S, S-a: unreachable in production -- subscribeTelemetry always
            // wraps the source in a SupervisedPublisher, which intercepts onError/onComplete
            // itself (to retry) and never forwards either one downstream. Left as a harmless no-op
            // (not e.g. an AssertionError) since this class still implements the public
            // Flow.Subscriber contract directly, and best-effort telemetry must never affect
            // stream/usage lifecycle even if something one day subscribes this unsupervised.
        }

        @Override
        public void onComplete() {
            // See onError above: unreachable in production, kept as a harmless no-op.
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
