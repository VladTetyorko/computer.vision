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
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>A device with no owning asset ({@link AssetRepositoryPort#findByDeviceId}
 * returns empty) is tracked as a no-op: not every registered device need be
 * wrapped by an asset yet, and this collaborator must never fail a stream
 * start/stop over that.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #onStreamStarted(DeviceId, StreamId)} — resolves the device's
 *       owning asset; if this is the asset's <b>first</b> currently-active
 *       device, opens a new {@link AssetUsage} (persisted via {@link
 *       AssetUsageRepositoryPort}), stamping it with the given {@code
 *       streamId} (docs/plans/done/MVP2-PLAN.md §R, R-a2 — the live stream that opened
 *       it, later joined against {@link com.drones.vision.perception.domain.model.DetectionResult}
 *       for replay), and subscribes to a {@link TelemetrySourcePort} for
 *       each of the asset's {@link Capability#TELEMETRY}-capable devices
 *       that a registered source supports. An asset with several devices
 *       starting streams in succession still opens exactly <b>one</b> usage,
 *       carrying the <b>first</b> device's {@code streamId} — later devices'
 *       stream ids are not recorded.</li>
 *   <li>Each telemetry sample received while a usage is open is persisted via
 *       {@link TelemetryRepositoryPort#save} and folds into the usage's cheap
 *       summary — {@code startPosition} (the first sample carrying a
 *       position), {@code lastPosition} (the most recent one), and {@code
 *       sampleCount} — via {@link #applySample}, kept in one method per the
 *       port's "single write path" note. The durable {@link TelemetryRepositoryPort#save} call
 *       always happens on the sample's own thread; the summary write is coalesced onto {@link
 *       UsageSummaryBatchSettings}'s size-or-time bound (docs/plans/done/SCALE-100-PLAN.md S4) —
 *       see {@link #registerSummaryUpdate} for why that split is safe (the summary is a
 *       recomputable counter, not a historical record). The same method also hands the sample to
 *       the configured {@code telemetryObserver} (docs/plans/done/OPS-CORE-PLAN.md §G — geofence
 *       evaluation in practice), right alongside the existing persist/live-update steps.</li>
 *   <li>{@link #onStreamStopped(DeviceId)} — if this was the asset's
 *       <b>last</b> currently-active device, closes the open usage ({@link
 *       AssetUsage#closed(Instant)}) and unsubscribes/closes every telemetry
 *       subscription opened for it.</li>
 *   <li>{@link #onTelemetryDeviceDiscovered(DeviceId)} — the same "asset's first active device
 *       opens a usage" accounting as {@link #onStreamStarted}, for a telemetry-capable device with
 *       no video stream of its own (docs/plans/active/DRONE-ONBOARDING-PLAN.md §7, Wave O7): a
 *       telemetry-only aircraft must still get an {@link AssetUsage}, opened on its first sample
 *       rather than a video start that will never come.</li>
 * </ul>
 *
 * <h2>Phase (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7)</h2>
 * Every {@link AssetUsage} this class opens, folds a sample into, or closes also carries an
 * {@link UsagePhase}, computed by running {@link com.drones.vision.flight.domain.model.FlightPhaseRule}
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
 * a usage opens ({@link #deviceStreamStarted}/{@link #deviceTelemetryDiscovered}), notifies the
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
 * rest of the usage. Unlike the video path, this does <b>not</b> publish a {@code PIPELINE_ERROR}
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

    private final AssetRepositoryPort assetRepository;
    private final DeviceRepositoryPort deviceRepository;
    private final AssetUsageRepositoryPort usageRepository;
    private final TelemetryRepositoryPort telemetryRepository;
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

    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources, null);
    }

    /**
     * Same as the 5-argument constructor, plus a {@link TelemetryLiveUpdatePort} collaborator
     * (docs/plans/done/REALTIME-PLAN.md §4): every telemetry sample folded via {@link #applySample} is
     * announced through it.
     *
     * @param liveUpdatePublisherPort nullable, following the same convention as {@code
     *                                 DefaultStreamService}'s own collaborator of the same type:
     *                                 {@code null} means no live-update announcements.
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, null);
    }

    /**
     * Same as the 6-argument constructor, plus an observer of every telemetry sample folded via
     * {@link #applySample}, invoked right alongside the existing persist/live-update steps.
     *
     * <p>Deliberately a {@link BiConsumer} rather than the geofence collaborator this was written
     * for (docs/plans/done/OPS-CORE-PLAN.md §G): breach evaluation belongs to the flight context,
     * and a direct call from here made perception depend on it, closing the context cycle that
     * blocks module extraction (docs/plans/active/DOMAIN-SEPARATION-W1.md §5, C2). Perception now
     * declares the seam and {@code vision-app} wires {@code GeofenceMonitor::evaluate} into it —
     * the same functional-seam convention {@link SupervisedPublisher}'s {@code onOutageBegan}
     * already follows.
     *
     * @param telemetryObserver nullable, following the same convention as {@code
     *                          liveUpdatePublisherPort}: {@code null} means samples are only
     *                          persisted and announced, never observed. Must be cheap and must not
     *                          throw — it runs on the telemetry path.
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                         BiConsumer<AssetId, Telemetry> telemetryObserver) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, UsageSummaryBatchSettings.immediate());
    }

    /**
     * Same as the 7-argument constructor, plus how {@link #applySample} coalesces its usage-summary
     * write — see {@link UsageSummaryBatchSettings}'s own javadoc (docs/plans/done/SCALE-100-PLAN.md
     * S4). {@link UsageSummaryBatchSettings#immediate()} reproduces the 7-argument constructor's own
     * one-save-per-sample behavior exactly (what every pre-S4 caller, and this class's own tests,
     * still get); production wiring is meant to move to {@link UsageSummaryBatchSettings#defaults()}
     * once {@code vision-app} binds {@code vision.persistence.telemetry.*} to it.
     *
     * @param summaryBatchSettings how {@link #applySample} coalesces the summary write; never
     *                             {@code null}
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                         BiConsumer<AssetId, Telemetry> telemetryObserver,
                         UsageSummaryBatchSettings summaryBatchSettings) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, summaryBatchSettings, UsagePhaseSettings.defaults());
    }

    /**
     * Same as the 8-argument constructor, plus explicit {@link UsagePhaseSettings}
     * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7) — the deterministic clock and
     * {@code FlightPhaseRule} {@link #applySample}, {@link #deviceStreamStopped} and {@link
     * #evaluateLinkHealth(AssetId)} run to fold {@code AssetUsage.phase}. Every shorter public
     * constructor defaults this to {@link UsagePhaseSettings#defaults()}; {@code vision-app} is
     * meant to use this one once it binds {@code vision.flight.phase.*}.
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                         BiConsumer<AssetId, Telemetry> telemetryObserver,
                         UsageSummaryBatchSettings summaryBatchSettings, UsagePhaseSettings phaseSettings) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, summaryBatchSettings, phaseSettings,
                UsagePhaseObserver.NOOP);
    }

    /**
     * Same as the 9-argument constructor, plus a {@link UsagePhaseObserver} notified whenever an
     * open usage's phase is set for the first time or changes (docs/plans/active/DRONE-ONBOARDING-PLAN.md
     * §2.4, Wave O11 -- the flight passport). This is the constructor {@code vision-app} is meant to
     * use once it wires a real observer (e.g. one that calls {@code
     * VehicleProfileService#captureSnapshot} at PREFLIGHT/POSTFLIGHT); every shorter public
     * constructor still defaults this collaborator to {@link UsagePhaseObserver#NOOP}, so behavior
     * is unchanged for every pre-O11 call site.
     *
     * @param usagePhaseObserver never {@code null} -- pass {@link UsagePhaseObserver#NOOP} for "do
     *                           nothing", the same explicit-no-op idiom {@link UsagePhaseSettings}
     *                           and every other collaborator added since Wave O7 already follows in
     *                           this class
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                         BiConsumer<AssetId, Telemetry> telemetryObserver,
                         UsageSummaryBatchSettings summaryBatchSettings, UsagePhaseSettings phaseSettings,
                         UsagePhaseObserver usagePhaseObserver) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, SupervisedPublisher.INITIAL_BACKOFF_NANOS,
                SupervisedPublisher.MAX_BACKOFF_NANOS, summaryBatchSettings, phaseSettings, usagePhaseObserver);
    }

    /**
     * Test seam: same as the 7-argument constructor, with explicit (typically much smaller)
     * telemetry-source reopen backoff bounds so supervision-related tests don't have to wait out a
     * real 1s-30s backoff. Production always uses the 7-argument constructor's defaults.
     */
    UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                 AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                 List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                 BiConsumer<AssetId, Telemetry> telemetryObserver, long sourceInitialBackoffNanos,
                 long sourceMaxBackoffNanos) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, sourceInitialBackoffNanos, sourceMaxBackoffNanos,
                UsageSummaryBatchSettings.immediate());
    }

    /**
     * Test seam: same as the 9-argument (backoff) constructor, plus explicit {@link
     * UsageSummaryBatchSettings}. Delegates to the true canonical constructor with {@link
     * UsagePhaseSettings#defaults()} — summary-coalescing tests don't need to know phase tracking
     * exists at all.
     */
    UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                 AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                 List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                 BiConsumer<AssetId, Telemetry> telemetryObserver, long sourceInitialBackoffNanos,
                 long sourceMaxBackoffNanos, UsageSummaryBatchSettings summaryBatchSettings) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, sourceInitialBackoffNanos, sourceMaxBackoffNanos,
                summaryBatchSettings, UsagePhaseSettings.defaults());
    }

    /**
     * Test seam: same as the 10-argument constructor, plus explicit {@link UsagePhaseSettings} —
     * defaults {@link UsagePhaseObserver} to {@link UsagePhaseObserver#NOOP}, delegating to the true
     * canonical constructor below. Lets phase-transition tests use a fixed/steppable clock and small
     * silence/abandon windows instead of production's real ones, without needing to know the
     * phase-observer seam exists.
     */
    UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                 AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                 List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                 BiConsumer<AssetId, Telemetry> telemetryObserver, long sourceInitialBackoffNanos,
                 long sourceMaxBackoffNanos, UsageSummaryBatchSettings summaryBatchSettings,
                 UsagePhaseSettings phaseSettings) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, telemetryObserver, sourceInitialBackoffNanos, sourceMaxBackoffNanos,
                summaryBatchSettings, phaseSettings, UsagePhaseObserver.NOOP);
    }

    /**
     * The true canonical constructor every other one ultimately delegates to
     * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11 added {@code usagePhaseObserver}).
     */
    UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                 AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                 List<TelemetrySourcePort> telemetrySources, TelemetryLiveUpdatePort liveUpdatePublisherPort,
                 BiConsumer<AssetId, Telemetry> telemetryObserver, long sourceInitialBackoffNanos,
                 long sourceMaxBackoffNanos, UsageSummaryBatchSettings summaryBatchSettings,
                 UsagePhaseSettings phaseSettings, UsagePhaseObserver usagePhaseObserver) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.telemetryRepository = Objects.requireNonNull(telemetryRepository, "telemetryRepository must not be null");
        Objects.requireNonNull(telemetrySources, "telemetrySources must not be null");
        this.telemetrySources = List.copyOf(telemetrySources);
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
        this.telemetryObserver = telemetryObserver; // nullable: samples are merely persisted/announced when absent
        this.sourceInitialBackoffNanos = sourceInitialBackoffNanos;
        this.sourceMaxBackoffNanos = sourceMaxBackoffNanos;
        this.summaryBatchSettings = Objects.requireNonNull(summaryBatchSettings, "summaryBatchSettings must not be null");
        this.phaseSettings = Objects.requireNonNull(phaseSettings, "phaseSettings must not be null");
        this.usagePhaseObserver = Objects.requireNonNull(usagePhaseObserver, "usagePhaseObserver must not be null");
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
        assetRepository.findByDeviceId(deviceId).ifPresent(asset -> deviceStreamStarted(asset, streamId));
    }

    /**
     * Notifies the tracker that a device's stream has stopped.
     *
     * @param deviceId the device whose stream stopped
     */
    public void onStreamStopped(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        assetRepository.findByDeviceId(deviceId).ifPresent(this::deviceStreamStopped);
    }

    /**
     * Notifies the tracker that a {@link Capability#TELEMETRY}-capable device has begun reporting,
     * independent of any video stream (docs/plans/active/DRONE-ONBOARDING-PLAN.md §7, Wave O7: "a
     * session opens on first telemetry, not only on first stream") — a telemetry-only aircraft (no
     * video device at all) must still get an {@link AssetUsage}. Applies the same "asset's first
     * active device opens a usage" accounting {@link #onStreamStarted} uses, just with no {@code
     * streamId} to stamp — see {@link AssetUsage#streamId()}'s own "or {@code null} for a
     * legacy/streamless usage" contract; this is the other honest reason for a {@code null} one.
     *
     * <p>Idempotent per device: a repeated call for a device already counted active is a no-op, so
     * a caller need not track whether it has already announced a given device.
     *
     * @param deviceId the telemetry-capable device that has begun reporting
     */
    public void onTelemetryDeviceDiscovered(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        assetRepository.findByDeviceId(deviceId).ifPresent(asset -> deviceTelemetryDiscovered(asset, deviceId));
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
                updated = tracking.usage.withPhase(mappedPhase);
                tracking.usage = updated;
                previousPhase = priorPhase;
                nextUsagePhase = mappedPhase;
            }
        }
        if (updated != null) {
            usageRepository.save(updated);
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
        return assetRepository.findByDeviceId(deviceId).map(Asset::id);
    }

    /**
     * Best-effort freshest known position for {@code assetId}'s currently open usage
     * (docs/plans/done/MVP2-PLAN.md §E, E-a) — the same {@code lastPosition} an {@link AssetUsage}
     * accumulates as telemetry samples arrive (see {@link #applySample}), read back rather than
     * queried fresh from {@link TelemetryRepositoryPort} directly, which has no "give me the
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
        boolean openedNow;
        UsageId openedUsageId = null;
        UsagePhase openedPhase = null;
        synchronized (tracking) {
            tracking.activeDevices++;
            tracking.activeVideoStreams++;
            openedNow = tracking.activeDevices == 1;
            if (openedNow) {
                AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), phaseSettings.clock().get(), null,
                        null, null, 0, streamId);
                tracking.usage = usageRepository.save(usage);
                openedUsageId = tracking.usage.id();
                openedPhase = tracking.usage.phase();
            }
        }
        if (openedNow) {
            subscribeTelemetry(asset, tracking);
            notifyPhaseObserver(asset.id(), openedUsageId, null, openedPhase);
        }
    }

    /**
     * The telemetry-only counterpart to {@link #deviceStreamStarted}: same "first active device
     * opens a usage" accounting, driven by {@link #onTelemetryDeviceDiscovered} instead of a video
     * stream start, and deliberately never touches {@link Tracking#activeVideoStreams} — a
     * telemetry-only asset must keep {@code streamCount == 0} for {@code FlightPhaseRule}
     * throughout (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3), never mistaken for "nothing
     * happening" just because it opened a usage.
     */
    private void deviceTelemetryDiscovered(Asset asset, DeviceId deviceId) {
        Tracking tracking = trackingByAsset.computeIfAbsent(asset.id(), id -> new Tracking());
        boolean openedNow = false;
        boolean firstTimeForDevice;
        UsageId openedUsageId = null;
        UsagePhase openedPhase = null;
        synchronized (tracking) {
            firstTimeForDevice = tracking.activeTelemetryOnlyDevices.add(deviceId);
            if (firstTimeForDevice) {
                tracking.activeDevices++;
                openedNow = tracking.activeDevices == 1;
                if (openedNow) {
                    AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), phaseSettings.clock().get(), null,
                            null, null, 0, null);
                    tracking.usage = usageRepository.save(usage);
                    openedUsageId = tracking.usage.id();
                    openedPhase = tracking.usage.phase();
                }
            }
        }
        if (openedNow) {
            // Covers every telemetry-capable device the asset has right now, not just this one --
            // see subscribeTelemetry's own loop over asset.devices(), same as deviceStreamStarted.
            subscribeTelemetry(asset, tracking);
            notifyPhaseObserver(asset.id(), openedUsageId, null, openedPhase);
        }
    }

    private void deviceStreamStopped(Asset asset) {
        Tracking tracking = trackingByAsset.get(asset.id());
        if (tracking == null) {
            return;
        }
        AssetUsage closedUsage = null;
        UsageId closedUsageId = null;
        UsagePhase previousPhase = null;
        UsagePhase closedPhaseFired = null;
        synchronized (tracking) {
            if (tracking.activeDevices > 0) {
                tracking.activeDevices--;
            }
            if (tracking.activeVideoStreams > 0) {
                tracking.activeVideoStreams--;
            }
            if (tracking.activeDevices == 0 && tracking.usage != null) {
                // docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3: the explicit-close half of the
                // state machine -- IN_FLIGHT/LINK_LOST both close to ABANDONED (the platform stopped
                // watching while the aircraft was, so far as it knew, airborne), everything else to
                // CLOSED.
                UsagePhase priorPhase = tracking.usage.phase();
                UsagePhase closedPhase = toUsagePhase(phaseSettings.rule().onSessionClosed(toFlightPhase(priorPhase)));
                closedUsageId = tracking.usage.id();
                closedUsage = tracking.usage.closed(phaseSettings.clock().get()).withPhase(closedPhase);
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
        if (closedUsage != null) {
            unsubscribeTelemetry(tracking);
            usageRepository.save(closedUsage);
            if (closedPhaseFired != null) {
                notifyPhaseObserver(asset.id(), closedUsageId, previousPhase, closedPhaseFired);
            }
        }
    }

    private void subscribeTelemetry(Asset asset, Tracking tracking) {
        for (DeviceId deviceId : asset.devices()) {
            Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
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
     * Unsubscribes and releases every telemetry subscription opened for a now-closed usage.
     * {@link SupervisedPublisher#stop()} runs synchronously here — cheap, in-memory, and it must
     * happen before this method returns so a pending scheduled reopen can never race a legitimate
     * close (docs/plans/done/MVP2-PLAN.md §S, S-a, same reasoning as {@code DefaultStreamService#stop}). The
     * actual {@link Flow.Subscription#cancel()}/{@link TelemetrySourcePort#close} calls run on a
     * background thread instead, for the same reason {@code DefaultStreamService} defers its own
     * source teardown: an adapter's {@code close()} is not guaranteed to be fast, and this method is
     * itself called synchronously from {@code DefaultStreamService#stop}, which must return promptly.
     */
    private void unsubscribeTelemetry(Tracking tracking) {
        List<TelemetrySubscription> subscriptions;
        synchronized (tracking) {
            subscriptions = List.copyOf(tracking.telemetrySubscriptions);
            tracking.telemetrySubscriptions.clear();
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
     * the single write path for both, per this port's javadoc note about
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
            GeoPosition startPosition = tracking.usage.startPosition();
            GeoPosition lastPosition = tracking.usage.lastPosition();
            if (position != null) {
                if (startPosition == null) {
                    startPosition = position;
                }
                lastPosition = position;
            }
            // docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3: the sample-driven half of the state
            // machine -- zero link age (a sample just arrived), streamCount scoped to genuine video
            // streams only (never activeDevices, which also counts telemetry-only devices -- see
            // deviceTelemetryDiscovered).
            UsagePhase priorPhase = tracking.usage.phase();
            FlightPhase nextPhase = phaseSettings.rule().nextPhase(toFlightPhase(priorPhase), sample.flightState(),
                    Duration.ZERO, tracking.activeVideoStreams);
            UsagePhase mappedPhase = toUsagePhase(nextPhase);
            updated = tracking.usage.withPositions(startPosition, lastPosition)
                    .withSampleCount(tracking.usage.sampleCount() + 1)
                    .withPhase(mappedPhase);
            tracking.usage = updated;
            tracking.lastSample = sample; // docs/plans/done/MVP3-PLAN.md C-a: outlives the usage, see latestTelemetry's javadoc
            if (mappedPhase != priorPhase) { // docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4/O11: fire only on a genuine transition
                observedPreviousPhase = priorPhase;
                observedNextPhase = mappedPhase;
            }
        }
        telemetryRepository.save(usageId, sample);
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
     * TelemetryRepositoryPort#save} call right before it: this write is a recomputable running
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
            usageRepository.save(updated);
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
            usageRepository.save(toWrite);
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
            usageRepository.save(toWrite);
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
     * Maps warehouse's persisted {@link UsagePhase} onto flight's {@link FlightPhase} so {@link
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
        /** docs/plans/active/DRONE-ONBOARDING-PLAN.md §7, Wave O7: telemetry-only devices already counted toward {@link #activeDevices} via {@code UsageTracker#deviceTelemetryDiscovered} -- guards a repeated {@link UsageTracker#onTelemetryDeviceDiscovered} call for the same device from inflating the count. */
        private final Set<DeviceId> activeTelemetryOnlyDevices = new LinkedHashSet<>();
        /** docs/plans/done/SCALE-100-PLAN.md S4: folds into {@link #usage} not yet written via {@code usageRepository.save}. */
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
