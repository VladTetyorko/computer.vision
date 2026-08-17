package com.drones.vision.api.live;

import com.drones.vision.api.dto.ActiveStreamResponse;
import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.DetectionEventResponse;
import com.drones.vision.api.dto.DetectionResultResponse;
import com.drones.vision.api.dto.DeviceResponse;
import com.drones.vision.api.dto.DevicesSnapshotResponse;
import com.drones.vision.api.dto.EventResponse;
import com.drones.vision.api.dto.LiveConnectedResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.LiveSubscriptionResponse;
import com.drones.vision.api.dto.MapEventPayload;
import com.drones.vision.api.dto.TelemetrySampleResponse;
import com.drones.vision.api.dto.UpdateLiveTopicsRequest;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.platform.Event;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import com.drones.vision.api.controller.AssetController;
import com.drones.vision.api.controller.EventController;
import com.drones.vision.api.controller.StreamController;

/**
 * The {@code /api/live} connection registry (docs/plans/done/REALTIME-PLAN.md §4) — the one class
 * that implements every publishing context's live-update port ({@link FleetLiveUpdatePort}, {@link
 * TelemetryLiveUpdatePort}, {@link DetectionLiveUpdatePort}, {@link MapLiveUpdatePort}, {@link
 * EventLiveUpdatePort} — five ports the former god-port {@code LiveUpdatePublisherPort} split into,
 * docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b), a per-process ({@code single-instance
 * deployment}, per the plan) hub fanning application-layer announcements out to every subscribed
 * {@code SseEmitter}. An adapter is exactly the place that may depend on every context at once —
 * each context's application code still only ever holds the one port it actually calls.
 *
 * <h2>Topics</h2>
 * {@link LiveTopic#FLEET}/{@link LiveTopic#EVENT}/{@link LiveTopic#DEVICES}/{@link
 * LiveTopic#DETECTION_EVENTS}/{@link LiveTopic#MAP} are implicit and on for every connection;
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
 * <h2>Scoped delivery — {@code map} only</h2>
 * Every topic above {@code map} broadcasts one envelope to every subscribed connection. {@code map}
 * does not: an event is delivered only to connections whose viewer may see its layer
 * (docs/plans/done/MAP-REWORK-PLAN.md §4.3, the security-critical half of the rework). The decision is
 * <strong>not</strong> made here — this class never resolves an identity. {@code LiveController}
 * captures the connecting request's viewer and hands {@link #connect} a predicate over an event's
 * {@code layerId} ({@link MapVisibility#deliveryPredicate}); the predicate rides on the {@link
 * LiveConnection} and is consulted by {@link LiveConnection#mayReceive} on every broadcast
 * <em>and</em> on every snapshot/resume replay. Because the filter keys off the buffered {@code
 * MapEventPayload}'s own {@code layerId}, a {@code Last-Event-ID} resume re-filters against what the
 * viewer may see <em>now</em>, with no parallel per-envelope bookkeeping to keep in step.
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
 * pipeline/telemetry thread. A shared scheduler drains it roughly every {@value
 * #COALESCE_MILLIS}ms ({@link #flushPending()}): telemetry batches every sample appended since the
 * last flush into one {@code List<TelemetrySampleResponse>} envelope per asset; detections keep
 * only the latest result per asset (the pending map itself is a plain overwrite) — matching the
 * plan's "detections emit latest-frame-only" exactly. {@link #publishFleetChanged}/{@link
 * #publishEvent}/{@link #publishDetectionEvent} are not coalesced (all three are comparatively
 * rare) but are still dispatched onto the shared scheduler rather than run on the caller's thread,
 * keeping every method here equally fire-and-forget.
 *
 * <p><b>Simplification, deliberate and documented</b>: coalescing runs once per topic, shared
 * across every connection subscribed to it, rather than genuinely independently per connection —
 * the plan's "per connection" framing is satisfied in effect (delivery is still batched to roughly
 * one envelope per {@value #COALESCE_MILLIS}ms per topic), while keeping exactly one canonical,
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
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public final class LiveUpdateRegistry implements FleetLiveUpdatePort, TelemetryLiveUpdatePort,
        DetectionLiveUpdatePort, MapLiveUpdatePort, EventLiveUpdatePort {

    /** How often {@link #flushPending()} drains coalesced telemetry/detections (docs/plans/done/REALTIME-PLAN.md §4, item 3). */
    static final long COALESCE_MILLIS = 150L;

    /** How often {@link #heartbeatAll()} sends a keepalive comment (docs/plans/done/REALTIME-PLAN.md §4, item 3). */
    static final long HEARTBEAT_MILLIS = 15_000L;

    /** Retained samples per asset's {@code telemetry} topic (FIFO — see {@link LiveRingBuffer}). */
    static final int TELEMETRY_BUFFER_CAPACITY = 50;

    /** Retained events on the shared {@code event} topic (FIFO — see {@link LiveRingBuffer}). */
    static final int EVENT_BUFFER_CAPACITY = 300;

    /**
     * Retained entries on the shared {@code detection-events} topic (FIFO — see {@link
     * LiveRingBuffer}), and the {@code limit} used to seed it from {@link
     * DetectionEventRepositoryPort#findRecent} when empty. Matches {@link #EVENT_BUFFER_CAPACITY}
     * for the same reason: generous resume slack for a cross-stream, comparatively low-rate feed
     * (debounced occurrences, not raw per-frame results).
     */
    static final int DETECTION_EVENT_BUFFER_CAPACITY = 300;

    /**
     * Retained entries on the shared {@code map} topic (FIFO — see {@link LiveRingBuffer}) — same
     * capacity as {@link #EVENT_BUFFER_CAPACITY}/{@link #DETECTION_EVENT_BUFFER_CAPACITY} for the
     * same reason (generous resume slack for a comparatively low-rate, individually-meaningful feed).
     *
     * <p>The buffer is shared across every connection and holds <em>unfiltered</em> envelopes; the
     * per-connection {@link LiveConnection#mayReceive} check is applied on the way out, on both the
     * broadcast and the replay path, so one canonical resumable sequence serves viewers at different
     * access levels without ever handing one of them another's event.
     */
    static final int MAP_BUFFER_CAPACITY = 300;

    private final ObjectProvider<AssetService> assetService;
    private final ObjectProvider<DeviceService> deviceService;
    private final ObjectProvider<StreamService> streamService;
    private final StreamPublisherPort streamPublisherPort;
    private final ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong sequencer = new AtomicLong(0L);
    private final ConcurrentHashMap<String, LiveConnection> connections = new ConcurrentHashMap<>();

    private final LiveRingBuffer fleetBuffer = new LiveRingBuffer(1, true);
    private final LiveRingBuffer eventBuffer = new LiveRingBuffer(EVENT_BUFFER_CAPACITY, false);
    private final LiveRingBuffer devicesBuffer = new LiveRingBuffer(1, true);
    private final LiveRingBuffer detectionEventsBuffer = new LiveRingBuffer(DETECTION_EVENT_BUFFER_CAPACITY, false);
    private final LiveRingBuffer mapBuffer = new LiveRingBuffer(MAP_BUFFER_CAPACITY, false);
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> telemetryBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> detectionBuffers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<AssetId, ConcurrentLinkedQueue<Telemetry>> pendingTelemetry =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, DetectionResult> pendingDetections = new ConcurrentHashMap<>();

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
     */
    @Autowired
    public LiveUpdateRegistry(ObjectProvider<AssetService> assetService,
                              ObjectProvider<DeviceService> deviceService,
                              ObjectProvider<StreamService> streamService,
                              StreamPublisherPort streamPublisherPort,
                              ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort) {
        this(assetService, deviceService, streamService, streamPublisherPort, detectionEventRepositoryPort,
                defaultScheduler());
    }

    /** Test seam: an injectable scheduler so tests can trigger {@link #flushPending()}/{@link #heartbeatAll()} directly instead of waiting on real timer ticks. */
    LiveUpdateRegistry(ObjectProvider<AssetService> assetService,
                        ObjectProvider<DeviceService> deviceService,
                        ObjectProvider<StreamService> streamService,
                        StreamPublisherPort streamPublisherPort,
                        ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort,
                        ScheduledExecutorService scheduler) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionEventRepositoryPort =
                Objects.requireNonNull(detectionEventRepositoryPort, "detectionEventRepositoryPort must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.scheduler.scheduleAtFixedRate(this::flushPending, COALESCE_MILLIS, COALESCE_MILLIS, TimeUnit.MILLISECONDS);
        this.scheduler.scheduleAtFixedRate(this::heartbeatAll, HEARTBEAT_MILLIS, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
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
     * @param topicsParam   the raw {@code topics} query parameter value — comma-separated {@code
     *                       telemetry:<assetId>}/{@code detections:<assetId>} entries; {@code
     *                       null}/blank means none requested. {@link LiveTopic#FLEET}/{@link
     *                       LiveTopic#EVENT}/{@link LiveTopic#DEVICES}/{@link
     *                       LiveTopic#DETECTION_EVENTS}/{@link LiveTopic#MAP} are added
     *                       automatically regardless.
     * @param lastEventId   the {@code Last-Event-ID} header value, parsed to a {@code seq}, or
     *                       {@code null} if absent (a fresh connection, not a resume)
     * @param mapVisibility whether this connection's viewer may see a {@code map} event about a
     *                       given {@code layerId} — supplied by the caller ({@code LiveController})
     *                       so this class never resolves an identity itself; applied to the
     *                       snapshot/resume burst below exactly as it is to every later broadcast
     * @return the emitter to return from the controller method
     * @throws IllegalArgumentException if {@code topicsParam} contains a malformed entry
     */
    public SseEmitter connect(String topicsParam, Long lastEventId, Predicate<String> mapVisibility) {
        Set<LiveTopic> requestedTopics = LiveTopic.parseTopicsParam(topicsParam);
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        LiveConnection connection = new LiveConnection(connectionId, emitter, mapVisibility);
        connection.topics().add(LiveTopic.FLEET);
        connection.topics().add(LiveTopic.EVENT);
        connection.topics().add(LiveTopic.DEVICES);
        connection.topics().add(LiveTopic.DETECTION_EVENTS);
        connection.topics().add(LiveTopic.MAP);
        connection.topics().addAll(requestedTopics);
        connections.put(connectionId, connection);

        emitter.onCompletion(() -> connections.remove(connectionId));
        emitter.onTimeout(() -> connections.remove(connectionId));
        emitter.onError(cause -> connections.remove(connectionId));

        try {
            connection.sendConnected(new LiveConnectedResponse(connectionId, wireTopics(connection.topics())));
            for (LiveTopic topic : connection.topics()) {
                for (LiveEnvelopeResponse envelope : replayFor(topic, lastEventId)) {
                    if (connection.mayReceive(envelope)) {
                        connection.send(envelope);
                    }
                }
            }
        } catch (IOException e) {
            connections.remove(connectionId);
        }
        return emitter;
    }

    /**
     * Adds/removes topics on an already-open connection (docs/plans/done/REALTIME-PLAN.md §4, item 2) — a
     * newly-added topic immediately receives its own snapshot burst, exactly like a fresh {@link
     * #connect}, so a tile entering the screen catches up without reconnecting.
     *
     * @param connectionId the connection to update
     * @param request      topics to add/remove
     * @return the connection's full topic set afterward
     * @throws NoSuchElementException if {@code connectionId} is unknown (already disconnected, or
     *                                 never existed)
     */
    public LiveSubscriptionResponse updateTopics(String connectionId, UpdateLiveTopicsRequest request) {
        LiveConnection connection = connections.get(connectionId);
        if (connection == null) {
            throw new NoSuchElementException("Unknown live connection: " + connectionId);
        }
        for (String raw : request.remove()) {
            LiveTopic topic = LiveTopic.parse(raw);
            if (topic.kind() == LiveTopicKind.TELEMETRY || topic.kind() == LiveTopicKind.DETECTIONS) {
                connection.topics().remove(topic); // FLEET/EVENT/DEVICES/DETECTION_EVENTS/MAP stay on regardless -- see class javadoc
            }
        }
        try {
            for (String raw : request.add()) {
                LiveTopic topic = LiveTopic.parse(raw);
                if (connection.topics().add(topic)) {
                    for (LiveEnvelopeResponse envelope : bufferFor(topic).snapshot()) {
                        if (connection.mayReceive(envelope)) {
                            connection.send(envelope);
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
     * (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.5) — the SSE half of {@code
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
     * {@inheritDoc}
     *
     * <p>Recomputes and broadcasts <em>both</em> the {@code fleet} (asset-centric) and {@code
     * devices} (device-list + active-stream-list) snapshots in one dispatch — see this class's own
     * javadoc for why {@code devices} extends this method rather than needing a second port call.
     */
    @Override
    public void publishFleetChanged() {
        scheduler.execute(() -> {
            LiveEnvelopeResponse fleetEnvelope = freshFleetEnvelope();
            fleetBuffer.append(fleetEnvelope);
            broadcast(LiveTopic.FLEET, fleetEnvelope);

            LiveEnvelopeResponse devicesEnvelope = freshDevicesEnvelope();
            devicesBuffer.append(devicesEnvelope);
            broadcast(LiveTopic.DEVICES, devicesEnvelope);
        });
    }

    @Override
    public void publishTelemetryAppended(AssetId assetId, Telemetry sample) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(sample, "sample must not be null");
        pendingTelemetry.computeIfAbsent(assetId, id -> new ConcurrentLinkedQueue<>()).add(sample);
    }

    @Override
    public void publishDetections(AssetId assetId, DetectionResult result) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        pendingDetections.put(assetId, result); // latest-only: a later put simply overwrites
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
     * Drains {@link #pendingTelemetry}/{@link #pendingDetections} and emits one coalesced envelope
     * per asset that had something pending — see the class javadoc's "Coalescing" section. Package-
     * private so a test can call it directly/deterministically instead of waiting on the real timer.
     */
    void flushPending() {
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
            DetectionResult result = pendingDetections.remove(assetId);
            if (result == null) {
                continue; // another flush already claimed it
            }
            LiveTopic topic = LiveTopic.detections(assetId);
            LiveEnvelopeResponse envelope = new LiveEnvelopeResponse(sequencer.incrementAndGet(),
                    assetId.value().toString(), LiveTopicKind.DETECTIONS.wire(), DetectionResultResponse.from(result));
            bufferFor(topic).append(envelope);
            broadcast(topic, envelope);
        }
    }

    /** Package-private so a test can trigger a heartbeat deterministically instead of waiting on the real timer. */
    void heartbeatAll() {
        for (LiveConnection connection : connections.values()) {
            try {
                connection.heartbeat();
            } catch (IOException e) {
                unregister(connection.id(), e);
            }
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
     * Sends {@code envelope} to every connection subscribed to {@code topic} <em>and</em> permitted
     * to receive it — the second condition only ever excludes anything on the {@code map} topic (see
     * {@link LiveConnection#mayReceive}); every other topic's payload passes unconditionally.
     */
    private void broadcast(LiveTopic topic, LiveEnvelopeResponse envelope) {
        for (LiveConnection connection : connections.values()) {
            if (!connection.topics().contains(topic) || !connection.mayReceive(envelope)) {
                continue;
            }
            try {
                connection.send(envelope);
            } catch (IOException e) {
                unregister(connection.id(), e);
            }
        }
    }

    private void unregister(String connectionId, Throwable cause) {
        LiveConnection removed = connections.remove(connectionId);
        if (removed != null) {
            removed.completeWithError(cause);
        }
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
            default -> { } // EVENT/TELEMETRY/DETECTIONS/MAP: honestly-limited, nothing to seed -- see class javadoc's "Snapshot-on-connect" section for why MAP specifically stays in this bucket
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
            case TELEMETRY -> telemetryBuffers.computeIfAbsent(topic.assetId(),
                    id -> new LiveRingBuffer(TELEMETRY_BUFFER_CAPACITY, false));
            case DETECTIONS -> detectionBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
        };
    }

    /**
     * {@code hasImage} is always {@code false} on this live snapshot — deliberately, not an
     * oversight (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2): this class already sits at the
     * five-constructor-parameter ceiling (see {@code .claude/skills/java-clean-code/SKILL.md} §3),
     * and {@code AssetImageRepositoryPort} carries no per-asset lifecycle event of its own to
     * announce a change through anyway (unlike {@code AuditTrailPort}/{@code EventPublisherPort},
     * both already decorated for exactly this purpose in {@code vision-app}). A viewer relying on
     * the SSE {@code fleet} topic for {@code hasImage} needs the plain {@code GET /api/assets}
     * REST read instead, which computes it correctly (see {@code AssetController}).
     */
    private LiveEnvelopeResponse freshFleetEnvelope() {
        List<AssetSummaryResponse> snapshot = assetService.getObject().assets().stream()
                .map(summary -> AssetSummaryResponse.from(summary, false))
                .toList();
        return new LiveEnvelopeResponse(sequencer.incrementAndGet(), null, LiveTopicKind.FLEET.wire(), snapshot);
    }

    /**
     * Builds a fresh {@code devices} snapshot from {@link DeviceService#devices()} (default,
     * non-archived — matching {@code GET /api/devices}'s own default) + {@link
     * StreamService#streams()}, mapping each active stream's viewer URLs through {@link
     * #streamPublisherPort} exactly like {@code StreamController#list} already does.
     */
    private LiveEnvelopeResponse freshDevicesEnvelope() {
        List<DeviceResponse> devices =
                deviceService.getObject().devices().stream().map(DeviceResponse::from).toList();
        List<ActiveStreamResponse> streams = streamService.getObject().streams().stream()
                .map(stream -> new ActiveStreamResponse(stream.streamId().value().toString(),
                        stream.deviceId().value().toString(), stream.startedAt(), viewUrl(stream.streamId()),
                        whepUrl(stream.streamId()), stream.burnedIn()))
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
                detectionEventRepositoryPort.getObject().findRecent(null, DETECTION_EVENT_BUFFER_CAPACITY);
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
