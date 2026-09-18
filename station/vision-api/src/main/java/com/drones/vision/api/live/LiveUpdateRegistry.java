package com.drones.vision.api.live;

import com.drones.vision.api.dto.ActiveStreamResponse;
import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.DetectionEventResponse;
import com.drones.vision.api.dto.DetectionResultResponse;
import com.drones.vision.api.dto.DeviceResponse;
import com.drones.vision.api.dto.DevicesSnapshotResponse;
import com.drones.vision.api.dto.DiscoveryCandidateResponse;
import com.drones.vision.api.dto.DiscoveryEventPayload;
import com.drones.vision.api.dto.CorrectionResponse;
import com.drones.vision.api.dto.EventResponse;
import com.drones.vision.api.dto.FrameLedgerResponse;
import com.drones.vision.api.dto.GeofenceZoneEventPayload;
import com.drones.vision.api.dto.GeofenceZoneResponse;
import com.drones.vision.api.dto.LinkGroupResponse;
import com.drones.vision.api.dto.LiveConnectedResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.LiveSubscriptionResponse;
import com.drones.vision.api.dto.MapEventPayload;
import com.drones.vision.api.dto.StreamTracksResponse;
import com.drones.vision.api.dto.SystemStatusResponse;
import com.drones.vision.api.dto.TelemetrySampleResponse;
import com.drones.vision.api.dto.UpdateLiveTopicsRequest;
import com.drones.vision.api.support.VisionApiProperties;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.TracksSnapshot;
import com.drones.vision.platform.Event;
import com.drones.vision.flight.domain.model.GeofenceZoneEvent;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.flight.domain.port.GeofenceLiveUpdatePort;
import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.port.LinkStateLiveUpdatePort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.flight.domain.port.TrackCorrectionLiveUpdatePort;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import com.drones.vision.api.controller.AssetController;
import com.drones.vision.api.controller.EventController;
import com.drones.vision.api.controller.StreamController;

/**
 * The {@code /api/live} connection registry (docs/plans/done/REALTIME-PLAN.md §4) — the one class
 * that implements every publishing context's live-update port ({@link FleetLiveUpdatePort}, {@link
 * TelemetryLiveUpdatePort}, {@link DetectionLiveUpdatePort}, {@link MapLiveUpdatePort}, {@link
 * EventLiveUpdatePort} — five ports the former god-port {@code LiveUpdatePublisherPort} split into,
 * docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b — plus a sixth, {@link
 * TrackCorrectionLiveUpdatePort}, added for visual geolocation's {@code geo:<assetId>} topic,
 * docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4/D11, a seventh, {@link GeofenceLiveUpdatePort},
 * added for the {@code zones} topic (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D2/
 * &sect;4.1, wave L3), and an eighth, {@link LinkStateLiveUpdatePort}, added for the {@code
 * links:<assetId>} topic (LINK-PAIRING-PLAN.md §3.4/§4 row L3)), a per-process ({@code single-instance
 * deployment}, per the plan) hub fanning application-layer announcements out to every subscribed
 * {@code SseEmitter}. An adapter is exactly the place that may depend on every context at once —
 * each context's application code still only ever holds the one port it actually calls. {@code
 * system} (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4) is
 * different: no context port backs it — {@link #publishSystemStatus} is called only by {@link
 * SystemStatusSampler}, a fellow {@code vision-api} class, not through a port at all, since a
 * server-side sampler (not a context's own write) is what decides when this topic changes.
 *
 * <h2>Topics</h2>
 * {@link LiveTopic#FLEET}/{@link LiveTopic#EVENT}/{@link LiveTopic#DEVICES}/{@link
 * LiveTopic#DETECTION_EVENTS}/{@link LiveTopic#MAP}/{@link LiveTopic#DISCOVERY}/{@link
 * LiveTopic#ZONES}/{@link LiveTopic#SYSTEM} are implicit and on for every connection;
 * {@code telemetry:<assetId>}/{@code detections:<assetId>} are opt-in (requested via the {@code
 * topics} query parameter at connect time, or added/removed later via {@link #updateTopics(String,
 * UpdateLiveTopicsRequest)}). {@code devices}/{@code detection-events} extend this channel beyond
 * its original R-c scope: {@code devices} lets {@code FleetStore} (vision-web) drop its 5s {@code
 * GET /api/devices}+{@code GET /api/streams} poll, and {@code detection-events} lets {@code
 * EventsStore} drop its {@code GET /api/events} poll — see each topic's own javadoc ({@link
 * LiveTopicKind#DEVICES}/{@link LiveTopicKind#DETECTION_EVENTS}) for why each is its own topic
 * rather than folded into {@code fleet}/{@code event}. {@code map} (docs/plans/done/MAP-REWORK-PLAN.md §4.3)
 * replaces the old {@code marks} topic outright — the whole common operational picture (marks,
 * drawings and layers), carrying its entity and lifecycle action inside the payload rather than as
 * twelve topic kinds, exactly like {@code detection-events} carries OPEN/CLOSED in one topic (see
 * {@link LiveTopicKind#MAP}).
 *
 * <h2>Scoped delivery — {@code map} and {@code fleet}</h2>
 * Every other topic broadcasts one envelope, unchanged, to every subscribed connection. {@code map}
 * and {@code fleet} do not: a {@code map} event is delivered only to connections whose viewer may
 * see its layer (docs/plans/done/MAP-REWORK-PLAN.md §4.3, the security-critical half of the
 * rework), and a {@code fleet} envelope's asset list is narrowed, per connection, to the assets that
 * connection's viewer may currently see — the same predicate {@code GET /api/assets} itself applies
 * ({@code AssetService#assets(VisibilityScope, boolean)}), reused rather than duplicated. Neither
 * decision is made here — this class never resolves an identity. {@code LiveController} captures the
 * connecting request's viewer and hands {@link #connect} a predicate over a {@code map} event's
 * {@code layerId} ({@link MapVisibility#deliveryPredicate}) and a predicate over an {@link AssetId}
 * ({@code LiveAssetAccess#deliveryPredicate}); both ride on the {@link LiveConnection} and are
 * consulted by {@link LiveConnection#project} on every broadcast <em>and</em> on every
 * snapshot/resume replay. Because each filter keys off the buffered envelope's own {@code
 * layerId}/asset list, a {@code Last-Event-ID} resume re-filters against what the viewer may see
 * <em>now</em>, with no parallel per-envelope bookkeeping to keep in step — {@link #fleetBuffer}
 * itself is never filtered at construction, for exactly this reason (see {@link
 * #freshFleetEnvelope()}).
 *
 * <p>{@link #broadcast} still serializes an envelope exactly once and hands that one {@code String}
 * to every connection whose {@link LiveConnection#project} returned the identical instance back —
 * true for every connection on every topic except a genuinely narrowed {@code map}/{@code fleet}
 * delivery, which is the only case that pays a second, per-connection re-serialize. See {@link
 * #broadcast}'s own javadoc.
 *
 * <h2>Snapshot-on-connect</h2>
 * Every topic is backed by a {@link LiveRingBuffer} (see that class for the FIFO-vs-latest-only
 * retention split). Three topics have a real live-query fallback when their buffer is still empty
 * (nothing has ever changed since this process started) rather than reporting "nothing yet" just
 * because the process just started:
 * <ul>
 *   <li>{@link LiveTopic#FLEET} — {@link AssetService#assets()}</li>
 *   <li>{@link LiveTopic#DEVICES} — {@link DeviceService#devices()} + {@link StreamService#streams()}</li>
 *   <li>{@link LiveTopic#DETECTION_EVENTS} — {@link DetectionEventRepositoryPort#findRecent}, the
 *       exact same source {@code EventController} reads for {@code GET /api/events}</li>
 * </ul>
 * Every other topic's "snapshot" is honestly just "whatever this process has buffered since it
 * started" (documented limitation: a viewer connecting for the first time to an asset's {@code
 * telemetry}/{@code detections} topic sees nothing until the next sample/result arrives, even if
 * the asset has been streaming all along; acceptable for a process-local, single-instance ring
 * buffer per the plan's own scope) — {@link LiveTopic#EVENT} and {@link LiveTopic#MAP} are the
 * always-on topics that stay in that "honestly limited" bucket. For {@code map} this is a
 * deliberate choice, not an oversight, and the scoping rework strengthens it: a live-query seed
 * would need extra {@code ObjectProvider<MarkService>}/{@code ObjectProvider<DrawingService>}
 * constructor parameters (the same circular-dependency shape {@code assetService}/{@code
 * deviceService}/{@code streamService}/{@code detectionEventRepositoryPort} already carry — those
 * services themselves depend on one of this class's five ports), pushing this constructor past the
 * five-parameter ceiling (see {@code .claude/skills/java-clean-code/SKILL.md} §3, and this class's
 * own {@link #freshFleetEnvelope()} javadoc, which already declines a similar addition for the same
 * reason) — <em>and</em> a seeded snapshot would have to be re-scoped per recipient, which a shared
 * buffer cannot express. A viewer's first connection instead relies on its own {@code GET
 * /api/map/layers}+{@code /marks}+{@code /drawings} reads for the current picture, each already
 * scoped correctly, before layering live deltas on top.
 *
 * <h2>Resume</h2>
 * A reconnecting {@code EventSource} sends back {@code Last-Event-ID} (this class's own {@code
 * seq}, a single counter shared across every topic — see {@link LiveEnvelopeResponse}'s javadoc
 * for why). Per subscribed topic: if the topic's buffer can resume from that seq with no gap, only
 * the entries after it are replayed; otherwise the same snapshot path as a fresh connect runs.
 * Adding two more topics changes nothing about this: every envelope on every topic still draws
 * from the exact same {@link #sequencer}, so one {@code Last-Event-ID} still resumes every
 * subscribed topic uniformly regardless of which combination a connection carries.
 *
 * <h2>Coalescing</h2>
 * {@link #publishTelemetryAppended}/{@link #publishDetections} never touch a connection directly —
 * they only enqueue into a small pending map, cheap and non-blocking for the calling stream-
 * pipeline/telemetry thread. A shared scheduler drains it roughly every {@link #coalesceMillis}ms
 * ({@link #flushPending()}): telemetry batches every sample appended since the
 * last flush into one {@code List<TelemetrySampleResponse>} envelope per asset; detections keep
 * only the latest result per asset (the pending map itself is a plain overwrite) — matching the
 * plan's "detections emit latest-frame-only" exactly. {@link #publishEvent}/{@link
 * #publishDetectionEvent} are not coalesced (both are comparatively rare) but are still dispatched
 * onto the shared scheduler rather than run on the caller's thread, keeping every method here
 * equally fire-and-forget.
 *
 * <p><b>{@link #publishFleetChanged()} is coalesced leading+trailing</b> (docs/plans/done/SCALE-100-PLAN.md
 * §5 S5): every asset/device/stream lifecycle write used to trigger its own full fleet+devices
 * recompute, so a bulk import of N assets recomputed the whole fleet N times. The first call after a
 * quiet period still dispatches immediately — a lone write is delivered with no added latency — but
 * any call landing within {@link #coalesceMillis}ms of that dispatch only marks the change as
 * pending rather than triggering a second recompute; {@link #flushPending()}'s own already-scheduled
 * tick performs one trailing recompute once the window closes, so the burst's true final state is
 * still delivered rather than silently dropped (CLAUDE.md rule 9 — newest data wins). A sustained
 * write storm therefore recomputes at most once per {@link #coalesceMillis}ms, the same cadence
 * telemetry/detections already get, instead of once per write.
 *
 * <p><b>Simplification, deliberate and documented</b>: coalescing runs once per topic, shared
 * across every connection subscribed to it, rather than genuinely independently per connection —
 * the plan's "per connection" framing is satisfied in effect (delivery is still batched to roughly
 * one envelope per {@link #coalesceMillis}ms per topic), while keeping exactly one canonical,
 * resumable sequence per topic instead of a connection-specific one, which would have made {@code
 * Last-Event-ID} resume ambiguous across two connections subscribed to the same topic.
 *
 * <p><b>{@code devices} extends {@code publishFleetChanged}, rather than adding a new port
 * method</b>: every asset/device/stream lifecycle seam that already calls {@link
 * #publishFleetChanged()} (asset/device CRUD via {@code LiveUpdateAuditTrail}, stream start/stop
 * via {@code LiveUpdateEventPublisher} — both {@code vision-app}) is exactly the set of seams the
 * {@code devices} topic also needs to refresh on, so this class's own implementation of that one
 * method now recomputes and broadcasts <em>both</em> the {@code fleet} and {@code devices}
 * snapshots in the same dispatch, instead of {@code vision-app} needing a second call site (and
 * {@link FleetLiveUpdatePort} a second, near-duplicate method) for what is, at every call site
 * that matters, the same fact: "fleet-level state changed, re-derive your own snapshot(s)".
 *
 * <h2>Connection writes</h2>
 * Everything above — sequencing ({@link #sequencer}), coalescing, and deciding which connections a
 * topic reaches — still happens on {@link #scheduler}'s single thread, so envelope ordering within
 * a topic is exactly the order {@link #scheduler} ran the code that appended/broadcast them.
 * What's off that thread (docs/plans/done/SCALE-100-PLAN.md §5 S2) is the actual write: {@link #broadcast}
 * serializes an envelope to JSON exactly once ({@link #serialize}) and hands that one {@code
 * String} to every subscribed {@link LiveConnection}, each of which queues its own write onto
 * {@link #connectionWriteExecutor} (one virtual thread per write) instead of blocking {@link
 * #scheduler} on {@code SseEmitter.send()} once per connection — a single slow/stalled client used
 * to hold up delivery to every other connection, and delay the next coalesce/heartbeat tick
 * besides. {@link LiveConnection#enqueueSend}/{@link LiveConnection#enqueueHeartbeat} still
 * guarantee one connection's own writes run in the order they were queued (see that class's
 * "Ordering under concurrent dispatch" javadoc), so no client ever sees its own stream reordered
 * even though many connections now write concurrently. {@link #dispatchWrite} bounds each write at
 * {@link #connectionWriteTimeoutMillis}ms; past that — whether the write itself stalled or an
 * earlier write still ahead of it in that connection's own chain is stuck — the connection is
 * unregistered, the same outcome an {@link IOException} from a dead client always produced.
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public final class LiveUpdateRegistry implements FleetLiveUpdatePort, TelemetryLiveUpdatePort,
        DetectionLiveUpdatePort, MapLiveUpdatePort, EventLiveUpdatePort, TrackCorrectionLiveUpdatePort,
        GeofenceLiveUpdatePort, LinkStateLiveUpdatePort {

    private static final System.Logger LOG = System.getLogger(LiveUpdateRegistry.class.getName());

    /**
     * Package-private compile-time default kept only because {@link LiveUpdateRegistryTest} (same
     * package) needs a static value to stub {@link DetectionEventRepositoryPort#findRecent}'s
     * {@code limit} argument against — derived from {@link VisionApiProperties.Live#defaults()}
     * rather than a second literal, so it can never drift from the real default. The bound a running
     * instance actually enforces is the property-driven {@link #detectionEventBufferCapacity}
     * instance field below, not this constant (the two are equal for any instance built with {@link
     * VisionApiProperties.Live#defaults()}, which is every test that references this field).
     */
    static final int DETECTION_EVENT_BUFFER_CAPACITY = VisionApiProperties.Live.defaults().detectionBuffer();

    /**
     * Same reasoning as {@link #DETECTION_EVENT_BUFFER_CAPACITY} — kept for {@link
     * LiveUpdateRegistryTest}'s write-timeout test, which needs a compile-time bound to wait past.
     * The real, property-driven bound is {@link #connectionWriteTimeoutMillis}.
     */
    static final long CONNECTION_WRITE_TIMEOUT_MILLIS = VisionApiProperties.Live.defaults().sendTimeout().toMillis();

    private final ObjectProvider<AssetService> assetService;
    private final ObjectProvider<DeviceService> deviceService;
    private final ObjectProvider<StreamService> streamService;
    private final StreamPublisherPort streamPublisherPort;
    private final ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort;
    private final ScheduledExecutorService scheduler;

    /**
     * The cadence/sizing knobs {@code vision.api.live.*} controls (docs/plans/done/SCALE-100-PLAN.md
     * §5 S7, finishing the extraction {@code VisionApiProperties.Live} already described but neither
     * this class nor {@code HlsProxyController} actually read). Millis/int rather than the {@link
     * VisionApiProperties.Live} record's own {@code Duration}s — every call site below predates this
     * wiring and is already expressed in millis ({@link #scheduler}'s {@code scheduleAtFixedRate},
     * {@link CompletableFuture#orTimeout}), so converting once here (in the constructor) keeps every
     * one of those call sites unchanged instead of threading {@code Duration} through them.
     */
    private final long coalesceMillis;
    private final long heartbeatMillis;
    private final long connectionWriteTimeoutMillis;
    private final long bufferEvictionMillis;
    private final int telemetryBufferCapacity;
    private final int eventBufferCapacity;
    private final int detectionEventBufferCapacity;
    private final int mapBufferCapacity;

    /** One JSON encode per envelope, not one per connection — see the class javadoc's "Connection writes" section. */
    private final JsonMapper jsonMapper = new JsonMapper();

    /**
     * Where a connection's own write actually runs, off {@link #scheduler} — a virtual thread per
     * write, unconditionally instantiated (not constructor-injected: this class's production
     * constructor is already at the five-parameter ceiling before counting this, see {@code
     * .claude/skills/java-clean-code/SKILL.md} §3 — its one settings-bundle parameter added for
     * docs/plans/done/SCALE-100-PLAN.md §5 S7 groups eight scalars rather than adding a sixth
     * loose one, the same precedent {@code MediamtxStreamPublisher}'s {@code PublishSettings}
     * bundle sets — and unlike {@link #scheduler} nothing here needs deterministic single-step test
     * control, only real concurrency to exercise).
     *
     * <p><b>{@code vision.api.live.dispatch-threads} — surveyed for S7, deliberately not added</b>:
     * {@link Executors#newVirtualThreadPerTaskExecutor()} spawns one platform-scheduled virtual
     * thread per submitted task and has no pool-size/thread-count concept to configure — there is no
     * bound a "thread count" setting could mean here, so a knob by that name would be read, stored,
     * and silently ignored, which is worse than no knob at all. The key stays a documented
     * non-decision: if this executor is ever swapped for a bounded platform {@link
     * java.util.concurrent.ThreadPoolExecutor} (the only circumstance under which a thread count
     * becomes a real, honest bound), that change is what should introduce {@code dispatch-threads},
     * not this wave.
     */
    private final ExecutorService connectionWriteExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicLong sequencer = new AtomicLong(0L);
    private final ConcurrentHashMap<String, LiveConnection> connections = new ConcurrentHashMap<>();

    private final LiveRingBuffer fleetBuffer = new LiveRingBuffer(1, true);
    private final LiveRingBuffer eventBuffer;
    private final LiveRingBuffer devicesBuffer = new LiveRingBuffer(1, true);
    private final LiveRingBuffer detectionEventsBuffer;
    private final LiveRingBuffer mapBuffer;
    /**
     * Shares {@link #eventBufferCapacity} rather than a dedicated property (docs/plans/active/
     * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4) — a discovery-inbox delta is exactly as infrequent
     * as a generic domain {@code Event}, so a second buffer-capacity knob would only duplicate the
     * existing one.
     */
    private final LiveRingBuffer discoveryBuffer;

    /**
     * Shares {@link #eventBufferCapacity} rather than a dedicated property, exactly like {@link
     * #discoveryBuffer} — a geofence-zone delta (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md
     * &sect;3 D2/&sect;4.1, wave L3) is exactly as infrequent as a generic domain {@code Event}, so a
     * second buffer-capacity knob would only duplicate the existing one.
     */
    private final LiveRingBuffer zonesBuffer;

    /**
     * Latest-only, capacity 1, like {@link #fleetBuffer}/{@link #devicesBuffer} — a fresh {@code
     * system} sample (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4)
     * always supersedes the last one, so no dedicated buffer-capacity property is needed either.
     */
    private final LiveRingBuffer systemBuffer = new LiveRingBuffer(1, true);

    /**
     * Per-asset buffers for {@code telemetry:<assetId>}/{@code detections:<assetId>} — {@link
     * #bufferFor} only ever adds an entry here ({@code computeIfAbsent}); {@link
     * #evictUnusedAssetBuffers()} is what keeps these two maps from retaining one buffer per asset
     * ever watched for the life of the process (docs/plans/done/SCALE-100-PLAN.md §5 S2 item 4).
     */
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> telemetryBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> detectionBuffers = new ConcurrentHashMap<>();
    /** Per-asset {@code geo:<assetId>} buffer (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4, D11) -- same eviction/capacity-1 treatment as {@link #detectionBuffers}. */
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> geoBuffers = new ConcurrentHashMap<>();
    /**
     * Per-asset {@code tracks:<assetId>}/{@code cv-trace:<assetId>} buffers (docs/plans/active/
     * CV-ORCHESTRATION-PLAN.md §4.4/§4.5/§5, wave W2) -- same eviction/capacity-1 treatment as
     * {@link #detectionBuffers}; both are populated from the very same {@link #pendingDetections}
     * drain in {@link #flushPending()}, never a second port call.
     */
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> tracksBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> cvTraceBuffers = new ConcurrentHashMap<>();
    /**
     * Per-asset {@code links:<assetId>} buffer (LINK-PAIRING-PLAN.md §3.4/§4 row L3) -- same
     * eviction/capacity-1 treatment as {@link #detectionBuffers}/{@link #geoBuffers}.
     */
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> linksBuffers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<AssetId, ConcurrentLinkedQueue<Telemetry>> pendingTelemetry =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, PendingDetection> pendingDetections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, TrackCorrection> pendingCorrections = new ConcurrentHashMap<>();

    /**
     * One asset's latest-only {@link #publishDetections} payload, held together so {@link
     * #flushPending()} builds the {@code detections}/{@code tracks}/{@code cv-trace} envelopes from
     * one coherent snapshot instead of three independent reads (docs/plans/active/
     * CV-ORCHESTRATION-PLAN.md §4.6/§4.9, waves W2.8/W9). {@code tracksSnapshot} is {@link
     * DetectionLiveUpdatePort#publishDetections}'s own per-result snapshot, carried verbatim -- this
     * class never re-folds or re-derives it. Widened from a bare {@code List<WorldObject>} in wave
     * W9 alongside the port itself (see that method's own javadoc); {@link #flushPending()} builds
     * the whole {@code tracks:} envelope from it via {@code StreamTracksResponse#from} (wave W9.1) --
     * the same assembly {@code StreamController#tracks} itself calls.
     */
    private record PendingDetection(DetectionResult result, TracksSnapshot tracksSnapshot) {
    }

    /**
     * {@link #publishFleetChanged()}'s coalescing window, in nanoseconds ({@link System#nanoTime()}
     * is monotonic and immune to wall-clock adjustment, unlike {@link System#currentTimeMillis()}) —
     * derived from {@link #coalesceMillis} rather than a second, independently-tunable field
     * (docs/plans/done/SCALE-100-PLAN.md §5 S5): a fleet/devices recompute is exactly as expensive
     * to run too often as a telemetry flush, so it shares that same window.
     */
    private final long fleetCoalesceWindowNanos;

    /**
     * The nanoTime at which {@link #publishFleetChanged()}'s current coalescing window closes.
     * {@code Long.MIN_VALUE} so the very first call always finds itself past the (nonexistent) prior
     * window and dispatches immediately, regardless of this JVM's {@link System#nanoTime()} origin
     * (which is arbitrary and not guaranteed positive). A caller only ever advances this via the
     * compare-and-set in {@link #publishFleetChanged()} — exactly one racing caller "wins" and opens
     * the next window, so at most one immediate recompute is dispatched per window.
     */
    private final AtomicLong fleetRecomputeWindowUntilNanos = new AtomicLong(Long.MIN_VALUE);

    /**
     * Set by {@link #publishFleetChanged()} whenever a call is coalesced away (landed inside an
     * already-open window) instead of dispatching its own recompute; {@link #flushPending()} checks
     * this on every tick and performs exactly one trailing recompute if it is set, guaranteeing the
     * window's last write is never silently dropped even if nothing calls {@link
     * #publishFleetChanged()} again (docs/plans/done/SCALE-100-PLAN.md §5 S5's "must not lose the last state").
     */
    private final AtomicBoolean fleetChangedDuringWindow = new AtomicBoolean(false);

    /**
     * {@code @Autowired} disambiguates this from the package-private test-seam constructor below
     * — Spring cannot pick one of two candidate constructors on its own.
     *
     * <p>{@code assetService}/{@code deviceService}/{@code streamService}/{@code
     * detectionEventRepositoryPort} are each an {@link ObjectProvider}, not the plain service/port
     * type, deliberately: every one of them sits on the other side of a genuine circular bean
     * dependency from this class. {@code DefaultAssetService}/{@code DefaultDeviceService} depend
     * on {@code AuditTrailPort}, which (when {@code vision.live.enabled=true}) {@code vision-app}
     * wraps in {@code LiveUpdateAuditTrail}, which depends on {@link FleetLiveUpdatePort}, which
     * resolves to this class; {@code DefaultStreamService} depends on {@link DetectionLiveUpdatePort}
     * directly; and the {@code detectionEventRepositoryPort} bean is itself wrapped in {@code
     * LiveUpdateDetectionEventRepository}, which depends on {@link DetectionLiveUpdatePort} too — the
     * four ports are distinct interfaces now, but every one of them still resolves to this same
     * class. A plain constructor-injected dependency on any of the four here would deadlock Spring's
     * bean graph at startup; deferring the actual lookup to {@link #freshFleetEnvelope()}/{@link
     * #freshDevicesEnvelope()}/{@link #seedDetectionEventsIfEmpty} (only ever called once the whole
     * context has finished starting) breaks every one of these cycles. {@code streamPublisherPort}
     * carries no such risk (neither {@code MediamtxStreamPublisher} nor {@code NoopStreamPublisher}
     * depends on any of this class's five ports), so it stays a plain constructor parameter. See
     * vision-app/MODULE.md's own Gotcha for the full chain and the exact {@code
     * UnsatisfiedDependencyException} this pattern resolves.
     *
     * <p>{@code live} is the one addition docs/plans/done/SCALE-100-PLAN.md §5 S7 makes to this
     * list — a settings bundle, not a collaborator, so it does not participate in any of the cycles
     * above and needs no {@link ObjectProvider} wrapper.
     */
    @Autowired
    public LiveUpdateRegistry(ObjectProvider<AssetService> assetService,
                              ObjectProvider<DeviceService> deviceService,
                              ObjectProvider<StreamService> streamService,
                              StreamPublisherPort streamPublisherPort,
                              ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort,
                              VisionApiProperties.Live live) {
        this(assetService, deviceService, streamService, streamPublisherPort, detectionEventRepositoryPort, live,
                defaultScheduler());
    }

    /**
     * Legacy 5-collaborator overload, defaulted to {@link VisionApiProperties.Live#defaults()} —
     * kept because {@code LiveControllerTest}/{@code LiveMapScopingTest} (package {@code
     * com.drones.vision.api.controller}, so only a {@code public} constructor is reachable there)
     * construct this class directly rather than through Spring, and predate docs/plans/done/SCALE-100-PLAN.md
     * §5 S7's properties wiring. Not {@code @Autowired}: Spring must have exactly one candidate
     * constructor to autowire, and the six-parameter overload above is the real production entry
     * point.
     */
    public LiveUpdateRegistry(ObjectProvider<AssetService> assetService,
                              ObjectProvider<DeviceService> deviceService,
                              ObjectProvider<StreamService> streamService,
                              StreamPublisherPort streamPublisherPort,
                              ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort) {
        this(assetService, deviceService, streamService, streamPublisherPort, detectionEventRepositoryPort,
                VisionApiProperties.Live.defaults());
    }

    /**
     * Test seam: an injectable scheduler so tests can trigger {@link #flushPending()}/{@link
     * #heartbeatAll()} directly instead of waiting on real timer ticks — defaulted to {@link
     * VisionApiProperties.Live#defaults()} since every existing caller of this constructor
     * (same-package {@link LiveUpdateRegistryTest}) predates the {@code live} parameter and asserts
     * against those defaults.
     */
    LiveUpdateRegistry(ObjectProvider<AssetService> assetService,
                        ObjectProvider<DeviceService> deviceService,
                        ObjectProvider<StreamService> streamService,
                        StreamPublisherPort streamPublisherPort,
                        ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort,
                        ScheduledExecutorService scheduler) {
        this(assetService, deviceService, streamService, streamPublisherPort, detectionEventRepositoryPort,
                VisionApiProperties.Live.defaults(), scheduler);
    }

    /**
     * The full constructor every other overload above ultimately delegates to — the one place
     * fields are actually assigned and {@link #scheduler}'s three fixed-rate tasks are scheduled.
     * Package-private test seam (both new parameters together): no test needs an injectable
     * scheduler with non-default {@code live} settings today, but the seam costs nothing to keep
     * available for one that later does.
     */
    LiveUpdateRegistry(ObjectProvider<AssetService> assetService,
                        ObjectProvider<DeviceService> deviceService,
                        ObjectProvider<StreamService> streamService,
                        StreamPublisherPort streamPublisherPort,
                        ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort,
                        VisionApiProperties.Live live,
                        ScheduledExecutorService scheduler) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionEventRepositoryPort =
                Objects.requireNonNull(detectionEventRepositoryPort, "detectionEventRepositoryPort must not be null");
        Objects.requireNonNull(live, "live must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.coalesceMillis = live.coalesce().toMillis();
        this.heartbeatMillis = live.heartbeat().toMillis();
        this.connectionWriteTimeoutMillis = live.sendTimeout().toMillis();
        this.bufferEvictionMillis = live.bufferEviction().toMillis();
        this.telemetryBufferCapacity = live.telemetryBuffer();
        this.eventBufferCapacity = live.eventBuffer();
        this.detectionEventBufferCapacity = live.detectionBuffer();
        this.mapBufferCapacity = live.mapBuffer();
        this.eventBuffer = new LiveRingBuffer(eventBufferCapacity, false);
        this.detectionEventsBuffer = new LiveRingBuffer(detectionEventBufferCapacity, false);
        this.mapBuffer = new LiveRingBuffer(mapBufferCapacity, false);
        this.discoveryBuffer = new LiveRingBuffer(eventBufferCapacity, false);
        this.zonesBuffer = new LiveRingBuffer(eventBufferCapacity, false);
        this.fleetCoalesceWindowNanos = TimeUnit.MILLISECONDS.toNanos(coalesceMillis);
        this.scheduler.scheduleAtFixedRate(this::flushPending, coalesceMillis, coalesceMillis, TimeUnit.MILLISECONDS);
        this.scheduler.scheduleAtFixedRate(this::heartbeatAll, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        this.scheduler.scheduleAtFixedRate(this::evictUnusedAssetBuffers, bufferEvictionMillis, bufferEvictionMillis,
                TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "live-update-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens a new connection: registers it, sends the {@code connection} handshake event, then
     * replays a snapshot-or-resume burst per subscribed topic — all synchronously, on the calling
     * (HTTP request) thread, before returning the emitter, since a one-time connect burst is cheap
     * and bounded (unlike the hot pipeline/telemetry paths {@link #publishDetections}/{@link
     * #publishTelemetryAppended} must never block).
     *
     * @param topicsParam     the raw {@code topics} query parameter value — comma-separated {@code
     *                        telemetry:<assetId>}/{@code detections:<assetId>} entries; {@code
     *                        null}/blank means none requested. {@link LiveTopic#FLEET}/{@link
     *                        LiveTopic#EVENT}/{@link LiveTopic#DEVICES}/{@link
     *                        LiveTopic#DETECTION_EVENTS}/{@link LiveTopic#MAP} are added
     *                        automatically regardless. {@code LiveController} has already filtered
     *                        this down to topics the caller may see (docs/plans/done/LIVE-SCOPE-PLAN.md
     *                        §2, W3, via {@code LiveAssetAccess#filterTopicsParam}) before calling
     *                        this method — this class trusts that filtering rather than repeating it.
     * @param lastEventId     the {@code Last-Event-ID} header value, parsed to a {@code seq}, or
     *                        {@code null} if absent (a fresh connection, not a resume)
     * @param userId          who this connection belongs to (docs/plans/done/LIVE-SCOPE-PLAN.md
     *                        §2, W3) — bound to the connection so a later {@link
     *                        #updateTopics(String, UpdateLiveTopicsRequest, UserId)} can refuse a
     *                        caller who does not own it. A plain identity value, not a live handle;
     *                        this class still never reads a security context of its own.
     * @param mapVisibility   whether this connection's viewer may see a {@code map} event about a
     *                        given {@code layerId} — supplied by the caller ({@code LiveController})
     *                        so this class never resolves an identity itself; applied to the
     *                        snapshot/resume burst below exactly as it is to every later broadcast
     * @param assetVisibility whether this connection's viewer may currently see a per-asset topic's
     *                        {@link AssetId} — supplied by the caller ({@code LiveController} via
     *                        {@code LiveAssetAccess#deliveryPredicate}, docs/plans/done/LIVE-SCOPE-PLAN.md
     *                        §2, W3), same treatment as {@code mapVisibility}: applied to the
     *                        snapshot/resume burst below and to every later broadcast, so a scope
     *                        change mid-connection (an assignment revoked) is enforced within the
     *                        predicate's own staleness bound rather than only at subscribe time
     * @return the emitter to return from the controller method
     * @throws IllegalArgumentException if {@code topicsParam} contains a malformed entry
     */
    public SseEmitter connect(String topicsParam, Long lastEventId, UserId userId, Predicate<String> mapVisibility,
                               Predicate<AssetId> assetVisibility) {
        Set<LiveTopic> requestedTopics = LiveTopic.parseTopicsParam(topicsParam);
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        LiveConnection connection = new LiveConnection(connectionId, emitter, userId, mapVisibility, assetVisibility);
        connection.topics().add(LiveTopic.FLEET);
        connection.topics().add(LiveTopic.EVENT);
        connection.topics().add(LiveTopic.DEVICES);
        connection.topics().add(LiveTopic.DETECTION_EVENTS);
        connection.topics().add(LiveTopic.MAP);
        connection.topics().add(LiveTopic.DISCOVERY);
        connection.topics().add(LiveTopic.ZONES);
        connection.topics().add(LiveTopic.SYSTEM);
        connection.topics().addAll(requestedTopics);
        connections.put(connectionId, connection);

        emitter.onCompletion(() -> connections.remove(connectionId));
        emitter.onTimeout(() -> connections.remove(connectionId));
        emitter.onError(cause -> connections.remove(connectionId));

        try {
            connection.sendConnected(new LiveConnectedResponse(connectionId, wireTopics(connection.topics())));
            for (LiveTopic topic : connection.topics()) {
                for (LiveEnvelopeResponse envelope : replayFor(topic, lastEventId)) {
                    LiveEnvelopeResponse projected = connection.project(envelope);
                    if (projected == null) {
                        continue;
                    }
                    String json = serialize(projected);
                    if (json != null) {
                        connection.send(projected.seq(), json);
                    }
                }
            }
        } catch (IOException e) {
            connections.remove(connectionId);
        }
        return emitter;
    }

    /**
     * Test seam: registers a connection around an already-constructed {@link SseEmitter} — a test
     * double that records or deliberately blocks on {@code send}, typically — bypassing the real
     * {@link #connect} handshake and snapshot burst, so a test can observe exactly what {@link
     * #broadcast}/{@link #heartbeatAll} write without a real servlet request/response round trip.
     *
     * @return the new connection's id
     */
    String register(SseEmitter emitter, Set<LiveTopic> topics, UserId userId, Predicate<String> mapVisibility,
                     Predicate<AssetId> assetVisibility) {
        String connectionId = UUID.randomUUID().toString();
        LiveConnection connection = new LiveConnection(connectionId, emitter, userId, mapVisibility, assetVisibility);
        connection.topics().addAll(topics);
        connections.put(connectionId, connection);
        return connectionId;
    }

    /**
     * Adds/removes topics on an already-open connection (docs/plans/done/REALTIME-PLAN.md §4, item 2) — a
     * newly-added topic immediately receives its own snapshot burst, exactly like a fresh {@link
     * #connect}, so a tile entering the screen catches up without reconnecting.
     *
     * @param connectionId the connection to update
     * @param request      topics to add/remove — {@code LiveController} has already filtered
     *                     {@code add} down to topics the caller may see (docs/plans/done/LIVE-SCOPE-PLAN.md
     *                     §2, W3, via {@code LiveAssetAccess#filterAdditions}) before calling this
     *                     method
     * @param callerUserId who is making this request (docs/plans/done/LIVE-SCOPE-PLAN.md §2, W3)
     * @return the connection's full topic set afterward
     * @throws NoSuchElementException if {@code connectionId} is unknown (already disconnected, or
     *                                 never existed), <b>or if it belongs to a different user</b> —
     *                                 collapsed into the same 404 so a caller who has merely learned
     *                                 another connection's id cannot distinguish "doesn't exist" from
     *                                 "isn't yours" (docs/plans/done/LIVE-SCOPE-PLAN.md §2, W3
     *                                 defect 2 — the same "existence hides itself" idiom W2 uses)
     */
    public LiveSubscriptionResponse updateTopics(String connectionId, UpdateLiveTopicsRequest request,
                                                  UserId callerUserId) {
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");
        LiveConnection connection = connections.get(connectionId);
        if (connection == null || !connection.ownerUserId().equals(callerUserId)) {
            throw new NoSuchElementException("Unknown live connection: " + connectionId);
        }
        for (String raw : request.remove()) {
            LiveTopic topic = LiveTopic.parse(raw);
            if (topic.kind() == LiveTopicKind.TELEMETRY || topic.kind() == LiveTopicKind.DETECTIONS
                    || topic.kind() == LiveTopicKind.CV_TRACE || topic.kind() == LiveTopicKind.LINKS) {
                // FLEET/EVENT/DEVICES/DETECTION_EVENTS/MAP stay on regardless -- see class javadoc. CV_TRACE
                // is deliberately removable (unlike GEO/TRACKS, left at this method's pre-existing scope) --
                // see TraceDemandPort's own javadoc for why closing a debug console must actually stop
                // paying the trace tier's cost, not just stop rendering it. LINKS is removable too --
                // vision-web's LinksStore ref-counts trackLinks/untrackLinks around the same generic
                // track()/untrack() helper cvTrace uses (live-store.ts), so a closed Links panel must
                // actually drop the subscription, not just stop rendering it.
                connection.topics().remove(topic);
            }
        }
        try {
            for (String raw : request.add()) {
                LiveTopic topic = LiveTopic.parse(raw);
                if (connection.topics().add(topic)) {
                    for (LiveEnvelopeResponse envelope : bufferFor(topic).snapshot()) {
                        LiveEnvelopeResponse projected = connection.project(envelope);
                        if (projected == null) {
                            continue;
                        }
                        String json = serialize(projected);
                        if (json != null) {
                            connection.send(projected.seq(), json);
                        }
                    }
                }
            }
        } catch (IOException e) {
            connections.remove(connectionId);
        }
        return new LiveSubscriptionResponse(connectionId, wireTopics(connection.topics()));
    }

    /**
     * Whether any open connection currently subscribes to {@code detections:<assetId>}
     * (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.5) — the SSE half of {@code
     * LiveAndPollDetectionDemand}'s two-protocol demand signal. A cockpit open on this asset is
     * exactly what this topic means (see this class's own javadoc), so one open connection with the
     * topic in its set is already "someone is watching."
     *
     * @param assetId the asset whose {@code detections} topic to check
     * @return {@code true} if at least one connection is subscribed
     */
    public boolean watchingDetections(AssetId assetId) {
        LiveTopic topic = LiveTopic.detections(assetId);
        return connections.values().stream().anyMatch(connection -> connection.topics().contains(topic));
    }

    /**
     * Whether any open connection currently subscribes to {@code cv-trace:<assetId>}
     * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4, wave W2) — the SSE half of {@code
     * LiveAndPollTraceDemand}'s two-protocol demand signal, exactly {@link
     * #watchingDetections(AssetId)}'s own reasoning applied to the warm trace tier instead of
     * ordinary detection output: a debug console subscribed to this topic is itself what keeps
     * {@code PipelineConfig#trace()} true for the stream it is inspecting.
     *
     * @param assetId the asset whose {@code cv-trace} topic to check
     * @return {@code true} if at least one connection is subscribed
     */
    public boolean watchingTrace(AssetId assetId) {
        LiveTopic topic = LiveTopic.cvTrace(assetId);
        return connections.values().stream().anyMatch(connection -> connection.topics().contains(topic));
    }

    /**
     * Whether any open connection is subscribed to <b>any</b> topic scoped to this asset — telemetry
     * or detections (docs/plans/done/STREAM-STATE-PLAN.md &sect;3.2). Read by {@code
     * LiveHlsAndReaderVideoDemand} as its "a cockpit is open on this asset" term.
     *
     * <p>Deliberately broader than {@link #watchingDetections(AssetId)}: that one asks whether
     * anyone wants <i>boxes</i>, which since docs/plans/done/CV-DEMAND-PLAN.md is off by default and so
     * says nothing about whether the video is being watched. Video has no SSE topic of its own — it
     * travels over HLS/WHEP — so an asset-scoped subscription is the closest thing this registry can
     * honestly offer, and it is only ever one OR-term among several.
     *
     * @param assetId the asset to check
     * @return {@code true} if at least one connection carries a topic scoped to it
     */
    public boolean watchingAsset(AssetId assetId) {
        if (assetId == null) {
            return false;
        }
        return connections.values().stream()
                .flatMap(connection -> connection.topics().stream())
                .anyMatch(topic -> assetId.equals(topic.assetId()));
    }

    /**
     * How many SSE connections are currently open — {@code live-updates}'s {@code
     * SubsystemStatusPort} plumbing (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2), read by {@code
     * LiveUpdateStatusProvider} (same package). This class has no external dependency to fail
     * against (it dispatches purely in-process), so its mere presence as a live bean already means
     * SSE dispatch is running; this count is reported as informational detail, not itself a
     * pass/fail signal.
     */
    int connectionCount() {
        return connections.size();
    }

    /**
     * Whether any topic buffer has ever dropped a retained envelope — informational, same reasoning
     * as {@link #connectionCount()}: a busy, healthy deployment drops routinely once a buffer is
     * past capacity, so this is surfaced as detail for an operator, never read as a fault.
     */
    boolean anyBufferEverDropped() {
        if (fleetBuffer.everDropped() || eventBuffer.everDropped() || devicesBuffer.everDropped()
                || detectionEventsBuffer.everDropped() || mapBuffer.everDropped() || discoveryBuffer.everDropped()
                || zonesBuffer.everDropped() || systemBuffer.everDropped()) {
            return true;
        }
        return telemetryBuffers.values().stream().anyMatch(LiveRingBuffer::everDropped)
                || detectionBuffers.values().stream().anyMatch(LiveRingBuffer::everDropped)
                || tracksBuffers.values().stream().anyMatch(LiveRingBuffer::everDropped)
                || cvTraceBuffers.values().stream().anyMatch(LiveRingBuffer::everDropped)
                || linksBuffers.values().stream().anyMatch(LiveRingBuffer::everDropped);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recomputes and broadcasts <em>both</em> the {@code fleet} (asset-centric) and {@code
     * devices} (device-list + active-stream-list) snapshots in one dispatch — see this class's own
     * javadoc for why {@code devices} extends this method rather than needing a second port call.
     *
     * <p><b>Coalesced leading+trailing</b> (docs/plans/done/SCALE-100-PLAN.md §5 S5 — see the class javadoc's
     * "Coalescing" section for the full reasoning): a call past the current window's close wins a
     * compare-and-set on {@link #fleetRecomputeWindowUntilNanos}, opens the next window, and
     * dispatches {@link #recomputeFleetAndDevices()} immediately, exactly as this method always did.
     * A call inside an already-open window (including one that lost the compare-and-set race to
     * another concurrent caller) only sets {@link #fleetChangedDuringWindow} — {@link
     * #flushPending()} owns the one trailing recompute that catches it.
     */
    @Override
    public void publishFleetChanged() {
        long now = System.nanoTime();
        long windowUntil = fleetRecomputeWindowUntilNanos.get();
        if (now >= windowUntil
                && fleetRecomputeWindowUntilNanos.compareAndSet(windowUntil, now + fleetCoalesceWindowNanos)) {
            scheduler.execute(this::recomputeFleetAndDevices);
        } else {
            fleetChangedDuringWindow.set(true);
        }
    }

    /**
     * The actual fleet+devices recompute-and-broadcast — the body {@link #publishFleetChanged()}
     * used to run unconditionally on every call; now shared with {@link #flushPending()}'s trailing
     * catch-up so both dispatch paths do exactly the same work.
     */
    private void recomputeFleetAndDevices() {
        LiveEnvelopeResponse fleetEnvelope = freshFleetEnvelope();
        fleetBuffer.append(fleetEnvelope);
        broadcast(LiveTopic.FLEET, fleetEnvelope);

        LiveEnvelopeResponse devicesEnvelope = freshDevicesEnvelope();
        devicesBuffer.append(devicesEnvelope);
        broadcast(LiveTopic.DEVICES, devicesEnvelope);
    }

    @Override
    public void publishTelemetryAppended(AssetId assetId, Telemetry sample) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(sample, "sample must not be null");
        pendingTelemetry.computeIfAbsent(assetId, id -> new ConcurrentLinkedQueue<>()).add(sample);
    }

    @Override
    public void publishDetections(AssetId assetId, DetectionResult result, TracksSnapshot tracksSnapshot) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(tracksSnapshot, "tracksSnapshot must not be null");
        // latest-only: a later put simply overwrites
        pendingDetections.put(assetId, new PendingDetection(result, tracksSnapshot));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Latest-only, exactly {@link #publishDetections}'s treatment -- a corrected fix supersedes
     * whatever this asset's previous fix said (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4, D11).
     */
    @Override
    public void publishCorrection(AssetId assetId, TrackCorrection correction) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(correction, "correction must not be null");
        pendingCorrections.put(assetId, correction); // latest-only: a later put simply overwrites
    }

    @Override
    public void publishEvent(Event event) {
        Objects.requireNonNull(event, "event must not be null");
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(), null,
                    LiveTopicKind.EVENT.wire(), EventResponse.from(event));
            eventBuffer.append(envelope);
            broadcast(LiveTopic.EVENT, envelope);
        });
    }

    @Override
    public void publishDetectionEvent(DetectionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = detectionEventEnvelope(event);
            detectionEventsBuffer.append(envelope);
            broadcast(LiveTopic.DETECTION_EVENTS, envelope);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Appends one envelope to the shared {@code map} buffer and broadcasts it — but only to the
     * connections whose viewer may see {@code event.layerId()} (docs/plans/done/MAP-REWORK-PLAN.md §4.3). The
     * buffered envelope itself is unfiltered, so it can be replayed to any later viewer and
     * re-filtered for them; see this class's "Scoped delivery" javadoc section.
     *
     * <p>Replaces the three {@code publishMarkCreated}/{@code publishMarkUpdated}/{@code
     * publishMarkCleared} methods it supersedes — the domain's {@code MapEvent} now carries the
     * entity and action, so one method covers marks, drawings and layers alike.
     */
    @Override
    public void publishMapEvent(MapEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(), null,
                    LiveTopicKind.MAP.wire(), MapEventPayload.from(event));
            mapBuffer.append(envelope);
            broadcast(LiveTopic.MAP, envelope);
        });
    }

    /**
     * Announces one discovery-inbox delta (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2
     * C4) — {@code action} is one of {@code "REPORTED"}/{@code "REGISTERED"}/{@code "DISMISSED"}/
     * {@code "RESTORED"}. Called by {@code vision-app}'s {@code LiveUpdateDiscoveryInboxService}
     * decorator, never directly by anything in this module, exactly like every other {@code
     * publish*} method here is called by a decorator or a context port implementation elsewhere.
     * Delta-only by construction: the caller decides when this is worth calling (a changed sweep
     * report, or an operator verb) — this method itself has no notion of "changed".
     *
     * @param action    what happened to {@code candidate}
     * @param candidate the candidate as it now stands
     */
    public void publishDiscoveryEvent(String action, DiscoveryCandidate candidate) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(), null,
                    LiveTopicKind.DISCOVERY.wire(), new DiscoveryEventPayload(action, DiscoveryCandidateResponse.from(candidate)));
            discoveryBuffer.append(envelope);
            broadcast(LiveTopic.DISCOVERY, envelope);
        });
    }

    /**
     * Publishes a fresh {@code devices} snapshot on its own, without also recomputing/broadcasting
     * {@code fleet} the way {@link #publishFleetChanged()}'s coalesced {@link
     * #recomputeFleetAndDevices()} does (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C6)
     * — called by {@code vision-app}'s {@code StreamStateObserver} wiring on every computed {@code
     * StreamState} transition, where only the device/stream list changed, not asset-level fleet
     * state.
     */
    public void publishDevicesSnapshot() {
        scheduler.execute(() -> {
            LiveEnvelopeResponse devicesEnvelope = freshDevicesEnvelope();
            devicesBuffer.append(devicesEnvelope);
            broadcast(LiveTopic.DEVICES, devicesEnvelope);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Appends one envelope to {@link #zonesBuffer} and broadcasts it, unfiltered, to every
     * subscribed connection — see {@link GeofenceLiveUpdatePort}'s own javadoc for why {@code zones}
     * needs no per-connection scoping. Called by {@code DefaultGeofenceService#create}/{@code
     * #update}/{@code #delete} (vision-flight), immediately after that service's own {@code
     * GeofenceMonitor#refresh()} — a rare, operator-driven write, not a hot path.
     */
    @Override
    public void publishZoneEvent(GeofenceZoneEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(), null,
                    LiveTopicKind.ZONES.wire(),
                    new GeofenceZoneEventPayload(event.action().name(), GeofenceZoneResponse.from(event.zone())));
            zonesBuffer.append(envelope);
            broadcast(LiveTopic.ZONES, envelope);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Appends one envelope to {@code assetId}'s own {@link #linksBuffers} entry and broadcasts it
     * (LINK-PAIRING-PLAN.md §3.4/§4 row L3) — dispatched directly, not coalesced like {@link
     * #publishTelemetryAppended}/{@link #publishDetections}: {@code DefaultLinkStateService} (vision-
     * flight) already calls this at most once per {@code linksFor}/{@code pin}/{@code release}, a
     * request-driven rate no busier than {@link #publishZoneEvent}'s own operator-driven writes, so
     * a second coalescing layer here would only add latency with nothing to save.
     */
    @Override
    public void publishLinks(AssetId assetId, LinkGroupView group) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(group, "group must not be null");
        scheduler.execute(() -> {
            LiveTopic topic = LiveTopic.links(assetId);
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                    assetId.value().toString(), LiveTopicKind.LINKS.wire(), LinkGroupResponse.from(group));
            bufferFor(topic).append(envelope);
            broadcast(topic, envelope);
        });
    }

    /**
     * Publishes a fresh {@code system} sample (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md
     * &sect;3 D3/&sect;4.2, wave L4) — appends to {@link #systemBuffer} and broadcasts it, unfiltered,
     * to every subscribed connection; same body shape as {@link #publishDevicesSnapshot()}. Called
     * only by {@link SystemStatusSampler}, on its own schedule, after that class has already decided
     * the sample changed — this method itself applies no change detection and is not backed by a
     * context port, unlike every other {@code publish*} method here (see this class's own javadoc).
     *
     * @param status the freshly-sampled system status, verbatim — the same shape {@code GET
     *               /api/system/status} returns
     */
    public void publishSystemStatus(SystemStatusResponse status) {
        Objects.requireNonNull(status, "status must not be null");
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(), null,
                    LiveTopicKind.SYSTEM.wire(), status);
            systemBuffer.append(envelope);
            broadcast(LiveTopic.SYSTEM, envelope);
        });
    }

    /**
     * Drains {@link #pendingTelemetry}/{@link #pendingDetections} and emits one coalesced envelope
     * per asset that had something pending, then performs {@link #publishFleetChanged()}'s trailing
     * recompute if a call was coalesced away during the current/previous window ({@link
     * #fleetChangedDuringWindow}) — see the class javadoc's "Coalescing" section. Package-private so
     * a test can call it directly/deterministically instead of waiting on the real timer.
     */
    void flushPending() {
        if (fleetChangedDuringWindow.compareAndSet(true, false)) {
            recomputeFleetAndDevices();
        }
        for (AssetId assetId : List.copyOf(pendingTelemetry.keySet())) {
            List<Telemetry> drained = drain(pendingTelemetry.get(assetId));
            if (drained.isEmpty()) {
                continue;
            }
            List<TelemetrySampleResponse> payload = drained.stream().map(TelemetrySampleResponse::from).toList();
            LiveTopic topic = LiveTopic.telemetry(assetId);
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                    assetId.value().toString(), LiveTopicKind.TELEMETRY.wire(), payload);
            bufferFor(topic).append(envelope);
            broadcast(topic, envelope);
        }
        for (AssetId assetId : List.copyOf(pendingDetections.keySet())) {
            PendingDetection pending = pendingDetections.remove(assetId);
            if (pending == null) {
                continue; // another flush already claimed it
            }
            DetectionResult result = pending.result();
            LiveTopic topic = LiveTopic.detections(assetId);
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                    assetId.value().toString(), LiveTopicKind.DETECTIONS.wire(), DetectionResultResponse.from(result));
            bufferFor(topic).append(envelope);
            broadcast(topic, envelope);
            // tracks/cv-trace ride the same PendingDetection, never a second port call (docs/plans/active/
            // CV-ORCHESTRATION-PLAN.md §4.5/§4.4/§4.6/§4.9, waves W2/W2.8/W9) -- see LiveTopicKind#TRACKS/
            // #CV_TRACE's own javadoc. tracks now carries the whole StreamTracksResponse snapshot (wave W9,
            // decision E25) -- the exact same assembly StreamController#tracks itself calls
            // (StreamTracksResponse#from), "one assembly, two transports" -- retiring the bare
            // WorldObjectResponse[] fold this topic used to carry, which made the per-stream /tracks poll
            // the only way to learn stats/latency/rate/follow.
            LiveTopic tracksTopic = LiveTopic.tracks(assetId);
            StreamTracksResponse tracksPayload = StreamTracksResponse.from(pending.tracksSnapshot(), Instant.now());
            LiveEnvelopeResponse tracksEnvelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                    assetId.value().toString(), LiveTopicKind.TRACKS.wire(), tracksPayload);
            bufferFor(tracksTopic).append(tracksEnvelope);
            broadcast(tracksTopic, tracksEnvelope);
            if (result.ledger().isPresent()) {
                LiveTopic cvTraceTopic = LiveTopic.cvTrace(assetId);
                LiveEnvelopeResponse cvTraceEnvelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                        assetId.value().toString(), LiveTopicKind.CV_TRACE.wire(),
                        FrameLedgerResponse.from(result.ledger().get()));
                bufferFor(cvTraceTopic).append(cvTraceEnvelope);
                broadcast(cvTraceTopic, cvTraceEnvelope);
            }
        }
        for (AssetId assetId : List.copyOf(pendingCorrections.keySet())) {
            TrackCorrection correction = pendingCorrections.remove(assetId);
            if (correction == null) {
                continue; // another flush already claimed it
            }
            LiveTopic topic = LiveTopic.geo(assetId);
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                    assetId.value().toString(), LiveTopicKind.GEO.wire(), CorrectionResponse.from(correction));
            bufferFor(topic).append(envelope);
            broadcast(topic, envelope);
        }
    }

    /**
     * Package-private so a test can trigger a heartbeat deterministically instead of waiting on the
     * real timer. Each connection's write is queued/dispatched exactly like {@link #broadcast}'s —
     * see the class javadoc's "Connection writes" section — so one connection with a stalled write
     * already queued ahead of its heartbeat never delays this method's return, nor any other
     * connection's heartbeat.
     */
    void heartbeatAll() {
        for (LiveConnection connection : connections.values()) {
            dispatchWrite(connection, connection.enqueueHeartbeat(connectionWriteExecutor));
        }
    }

    private static List<Telemetry> drain(ConcurrentLinkedQueue<Telemetry> queue) {
        List<Telemetry> drained = new ArrayList<>();
        Telemetry sample;
        while ((sample = queue.poll()) != null) {
            drained.add(sample);
        }
        return drained;
    }

    /**
     * Sends {@code envelope} to every connection subscribed to {@code topic}, each first run through
     * {@link LiveConnection#project} — the only two topics that projection can actually change are
     * {@code map} (may drop the envelope outright, if the viewer may not see its layer) and {@code
     * fleet} (may narrow its asset list down to what the viewer may currently see — never dropped
     * outright, since an empty list is the correct answer for a viewer with nothing visible); every
     * other topic's payload passes through unchanged.
     *
     * <p><b>Serialize-once, preserved</b> (docs/plans/done/SCALE-100-PLAN.md §5 S2 item 1): {@code
     * envelope} is still serialized exactly once, up front, into {@code shared}. A connection whose
     * projection returned the identical envelope instance — every connection on every topic except a
     * genuinely narrowed {@code map}/{@code fleet} delivery, including an unbounded/admin viewer on
     * {@code fleet} — reuses {@code shared} as-is; only a connection whose projection actually
     * narrowed the envelope pays a second, per-connection {@link #serialize} call. Dispatches one
     * write per matching connection onto {@link #connectionWriteExecutor} — see the class javadoc's
     * "Connection writes" section. This method itself never blocks on a connection's write, so a
     * stalled client cannot delay delivery to any other connection subscribed to the same topic, nor
     * the next scheduled tick.
     */
    private void broadcast(LiveTopic topic, LiveEnvelopeResponse envelope) {
        String shared = serialize(envelope);
        if (shared == null) {
            return; // already logged in serialize() -- nothing valid to send to anyone
        }
        for (LiveConnection connection : connections.values()) {
            if (!connection.topics().contains(topic)) {
                continue;
            }
            LiveEnvelopeResponse projected = connection.project(envelope);
            if (projected == null) {
                continue;
            }
            String json = (projected == envelope) ? shared : serialize(projected);
            if (json == null) {
                continue;
            }
            dispatchWrite(connection, connection.enqueueSend(projected.seq(), json, connectionWriteExecutor));
        }
    }

    /**
     * Serializes {@code envelope} to JSON exactly once (docs/plans/done/SCALE-100-PLAN.md §5 S2 item 1) so
     * {@link #broadcast} can hand the same {@code String} to every subscribed connection instead of
     * each one re-encoding the same object — {@code broadcast}'s cost used to grow with both the
     * number of connections and the size of the envelope; now only with the number of connections.
     *
     * @return the serialized envelope, or {@code null} if serialization failed (logged here; the
     *         caller treats {@code null} as "nothing valid to send" rather than propagating — an
     *         uncaught exception here would otherwise permanently kill {@link #flushPending}'s own
     *         {@code scheduleAtFixedRate} tick, taking every future topic down with it)
     */
    private String serialize(LiveEnvelopeResponse envelope) {
        try {
            return jsonMapper.writeValueAsString(envelope);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to serialize live envelope, type=" + envelope.type(), e);
            return null;
        }
    }

    /**
     * Bounds one already-queued connection write (docs/plans/done/SCALE-100-PLAN.md §5 S2 item 3): if it has
     * not completed within {@link #connectionWriteTimeoutMillis} — whether because the write
     * itself stalled or because an earlier write still ahead of it in {@link LiveConnection}'s own
     * chain is stuck — this connection is unregistered so a slow/dead client can never hold up
     * anyone else's delivery. {@link CompletableFuture#orTimeout} schedules its own timer rather
     * than blocking, so nothing here blocks the caller (always {@link #scheduler}'s thread).
     */
    private void dispatchWrite(LiveConnection connection, CompletableFuture<Void> write) {
        write.orTimeout(connectionWriteTimeoutMillis, TimeUnit.MILLISECONDS)
                .exceptionally(cause -> {
                    unregister(connection.id(), cause);
                    return null;
                });
    }

    private void unregister(String connectionId, Throwable cause) {
        LiveConnection removed = connections.remove(connectionId);
        if (removed != null) {
            removed.completeWithError(cause);
        }
    }

    /**
     * Removes any {@link #telemetryBuffers}/{@link #detectionBuffers} entry for an asset no
     * currently-open connection subscribes to anymore (docs/plans/done/SCALE-100-PLAN.md §5 S2 item 4) — see
     * those fields' own javadoc for why this sweep exists. Package-private so a test can trigger it
     * directly instead of waiting on the real {@link #bufferEvictionMillis}ms timer.
     */
    void evictUnusedAssetBuffers() {
        telemetryBuffers.keySet().retainAll(subscribedAssetIds(LiveTopicKind.TELEMETRY));
        detectionBuffers.keySet().retainAll(subscribedAssetIds(LiveTopicKind.DETECTIONS));
        geoBuffers.keySet().retainAll(subscribedAssetIds(LiveTopicKind.GEO));
        tracksBuffers.keySet().retainAll(subscribedAssetIds(LiveTopicKind.TRACKS));
        cvTraceBuffers.keySet().retainAll(subscribedAssetIds(LiveTopicKind.CV_TRACE));
        linksBuffers.keySet().retainAll(subscribedAssetIds(LiveTopicKind.LINKS));
    }

    private Set<AssetId> subscribedAssetIds(LiveTopicKind kind) {
        return connections.values().stream()
                .flatMap(connection -> connection.topics().stream())
                .filter(topic -> topic.kind() == kind)
                .map(LiveTopic::assetId)
                .collect(Collectors.toSet());
    }

    /** Package-private (rather than {@code private}) purely so a pure unit test in this package can exercise the resume-vs-snapshot decision directly, without going through a real {@code SseEmitter}. */
    List<LiveEnvelopeResponse> replayFor(LiveTopic topic, Long lastEventId) {
        LiveRingBuffer buffer = bufferFor(topic);
        if (buffer.isEmpty()) {
            seedIfEmpty(topic, buffer);
        }
        if (lastEventId != null && buffer.canResumeFrom(lastEventId)) {
            return buffer.since(lastEventId);
        }
        return buffer.snapshot();
    }

    /**
     * The one-per-kind live-query fallback for a topic whose buffer has never been appended to —
     * see the class javadoc's "Snapshot-on-connect" section. {@link LiveTopicKind#EVENT}/{@link
     * LiveTopicKind#TELEMETRY}/{@link LiveTopicKind#DETECTIONS} have no such fallback (nothing to
     * seed an empty buffer with), so they simply fall through unchanged.
     */
    private void seedIfEmpty(LiveTopic topic, LiveRingBuffer buffer) {
        switch (topic.kind()) {
            case FLEET -> buffer.append(freshFleetEnvelope());
            case DEVICES -> buffer.append(freshDevicesEnvelope());
            case DETECTION_EVENTS -> seedDetectionEventsIfEmpty(buffer);
            default -> { } // EVENT/TELEMETRY/DETECTIONS/GEO/TRACKS/CV_TRACE/MAP/DISCOVERY/ZONES: honestly-limited, nothing to seed -- see class javadoc's "Snapshot-on-connect" section for why MAP specifically stays in this bucket. SYSTEM needs no case either, for a different reason: SystemStatusSampler's first tick runs at startup delay 0, so systemBuffer is populated before any connection can arrive.
        }
    }

    /** Package-private for the same reason as {@link #replayFor} — lets a pure unit test inspect a topic's buffered state directly. */
    LiveRingBuffer bufferFor(LiveTopic topic) {
        return switch (topic.kind()) {
            case FLEET -> fleetBuffer;
            case EVENT -> eventBuffer;
            case DEVICES -> devicesBuffer;
            case DETECTION_EVENTS -> detectionEventsBuffer;
            case MAP -> mapBuffer;
            case DISCOVERY -> discoveryBuffer;
            case ZONES -> zonesBuffer;
            case SYSTEM -> systemBuffer;
            case TELEMETRY -> telemetryBuffers.computeIfAbsent(topic.assetId(),
                    id -> new LiveRingBuffer(telemetryBufferCapacity, false));
            case DETECTIONS -> detectionBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
            case GEO -> geoBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
            case TRACKS -> tracksBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
            case CV_TRACE -> cvTraceBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
            case LINKS -> linksBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
        };
    }

    /**
     * {@code hasImage} is always {@code false}, and {@code firmware}/{@code totalFlightSeconds}/{@code
     * custody.custodianName} are always absent, on this live snapshot — deliberately, not an oversight
     * (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2; docs/plans/active/
     * WAREHOUSE-UX-PLAN.md D5; docs/plans/active/INVENTORY-REWORK-PLAN.md D3): this
     * class's production constructor is already at the five-parameter ceiling (see {@code
     * .claude/skills/java-clean-code/SKILL.md} §3) before adding another collaborator just for this,
     * and neither {@code AssetImageRepositoryPort} nor {@code com.drones.vision.api.support.AssetRowFacts}
     * carries a per-asset lifecycle event of its own to announce a change through anyway (unlike
     * {@code AuditTrailPort}/{@code EventPublisherPort}, both already decorated for exactly this
     * purpose in {@code vision-app}). A viewer relying on the SSE {@code fleet} topic for these four
     * fields needs the plain {@code GET /api/assets} REST read instead, which computes them
     * correctly (see {@code AssetController}). {@code deviceCount} is not in that company — it comes
     * straight off the {@code AssetSummary} being snapshotted, so it is accurate here too.
     */
    private LiveEnvelopeResponse freshFleetEnvelope() {
        List<AssetSummaryResponse> snapshot = assetService.getObject().assets().stream()
                .map(summary -> AssetSummaryResponse.from(summary, false, null, null, null))
                .toList();
        return new LiveEnvelopeResponse(sequencer.incrementAndGet(), null, LiveTopicKind.FLEET.wire(), snapshot);
    }

    /**
     * Builds a fresh {@code devices} snapshot from {@link DeviceService#devices()} (default,
     * non-archived — matching {@code GET /api/devices}'s own default) + {@link
     * StreamService#streams()}, mapping each active stream's viewer URLs through {@link
     * #streamPublisherPort} exactly like {@code StreamController#list} already does — both now via
     * {@link ActiveStreamResponse#from}, so a field added to the REST poll can no longer go missing
     * from the snapshot the SPA actually prefers (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.5).
     */
    private LiveEnvelopeResponse freshDevicesEnvelope() {
        List<DeviceResponse> devices =
                deviceService.getObject().devices().stream().map(DeviceResponse::from).toList();
        StreamService streams0 = streamService.getObject();
        List<ActiveStreamResponse> streams = streams0.streams().stream()
                .map(stream -> ActiveStreamResponse.from(stream, viewUrl(stream.streamId()),
                        whepUrl(stream.streamId()), streams0.detectionState(stream.streamId()).orElse(null)))
                .toList();
        DevicesSnapshotResponse snapshot = new DevicesSnapshotResponse(devices, streams);
        return new LiveEnvelopeResponse(sequencer.incrementAndGet(), null, LiveTopicKind.DEVICES.wire(), snapshot);
    }

    /**
     * Seeds an empty {@code detection-events} buffer from {@link
     * DetectionEventRepositoryPort#findRecent} — the exact same source {@code EventController}
     * reads for {@code GET /api/events} — appended oldest-first (the port returns newest-first) so
     * the buffer's own causal ordering/{@code seq} assignment stays consistent with every other
     * append.
     */
    private void seedDetectionEventsIfEmpty(LiveRingBuffer buffer) {
        List<DetectionEvent> newestFirst =
                detectionEventRepositoryPort.getObject().findRecent(null, detectionEventBufferCapacity);
        List<DetectionEvent> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        for (DetectionEvent event : oldestFirst) {
            buffer.append(detectionEventEnvelope(event));
        }
    }

    private LiveEnvelopeResponse detectionEventEnvelope(DetectionEvent event) {
        return new LiveEnvelopeResponse(sequencer.incrementAndGet(), null, LiveTopicKind.DETECTION_EVENTS.wire(),
                DetectionEventResponse.from(event));
    }

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    private String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }

    private static List<String> wireTopics(Set<LiveTopic> topics) {
        return topics.stream().map(LiveTopic::wire).sorted().toList();
    }
}
