package com.drones.vision.api.live;

import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.DetectionResultResponse;
import com.drones.vision.api.dto.EventResponse;
import com.drones.vision.api.dto.LiveConnectedResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.LiveSubscriptionResponse;
import com.drones.vision.api.dto.TelemetrySampleResponse;
import com.drones.vision.api.dto.UpdateLiveTopicsRequest;
import com.drones.vision.application.AssetService;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
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

/**
 * The {@code /api/live} connection registry (docs/REALTIME-PLAN.md §4) — the one {@link
 * LiveUpdatePublisherPort} implementation, a per-process ({@code single-instance deployment},
 * per the plan) hub fanning application-layer announcements out to every subscribed {@code
 * SseEmitter}.
 *
 * <h2>Topics</h2>
 * {@link LiveTopic#FLEET}/{@link LiveTopic#EVENT} are implicit and on for every connection;
 * {@code telemetry:<assetId>}/{@code detections:<assetId>} are opt-in (requested via the {@code
 * topics} query parameter at connect time, or added/removed later via {@link
 * #updateTopics(String, UpdateLiveTopicsRequest)}).
 *
 * <h2>Snapshot-on-connect</h2>
 * Every topic is backed by a {@link LiveRingBuffer} (see that class for the FIFO-vs-latest-only
 * retention split). For {@link LiveTopic#FLEET} specifically, an empty buffer (nothing has ever
 * changed since this process started) is refreshed with one real, live {@link
 * AssetService#assets()} query before replay — every other topic's "snapshot" is honestly just
 * "whatever this process has buffered since it started" (documented limitation: a viewer
 * connecting for the first time to an asset's {@code telemetry}/{@code detections} topic sees
 * nothing until the next sample/result arrives, even if the asset has been streaming all along;
 * acceptable for a process-local, single-instance ring buffer per the plan's own scope).
 *
 * <h2>Resume</h2>
 * A reconnecting {@code EventSource} sends back {@code Last-Event-ID} (this class's own {@code
 * seq}, a single counter shared across every topic — see {@link LiveEnvelopeResponse}'s javadoc
 * for why). Per subscribed topic: if the topic's buffer can resume from that seq with no gap, only
 * the entries after it are replayed; otherwise the same snapshot path as a fresh connect runs.
 *
 * <h2>Coalescing</h2>
 * {@link #publishTelemetryAppended}/{@link #publishDetections} never touch a connection directly —
 * they only enqueue into a small pending map, cheap and non-blocking for the calling stream-
 * pipeline/telemetry thread. A shared scheduler drains it roughly every {@value
 * #COALESCE_MILLIS}ms ({@link #flushPending()}): telemetry batches every sample appended since the
 * last flush into one {@code List<TelemetrySampleResponse>} envelope per asset; detections keep
 * only the latest result per asset (the pending map itself is a plain overwrite) — matching the
 * plan's "detections emit latest-frame-only" exactly. {@link #publishFleetChanged}/{@link
 * #publishEvent} are not coalesced (both are comparatively rare) but are still dispatched onto the
 * shared scheduler rather than run on the caller's thread, keeping every method here equally
 * fire-and-forget.
 *
 * <p><b>Simplification, deliberate and documented</b>: coalescing runs once per topic, shared
 * across every connection subscribed to it, rather than genuinely independently per connection —
 * the plan's "per connection" framing is satisfied in effect (delivery is still batched to roughly
 * one envelope per {@value #COALESCE_MILLIS}ms per topic), while keeping exactly one canonical,
 * resumable sequence per topic instead of a connection-specific one, which would have made {@code
 * Last-Event-ID} resume ambiguous across two connections subscribed to the same topic.
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public final class LiveUpdateRegistry implements LiveUpdatePublisherPort {

    /** How often {@link #flushPending()} drains coalesced telemetry/detections (docs/REALTIME-PLAN.md §4, item 3). */
    static final long COALESCE_MILLIS = 150L;

    /** How often {@link #heartbeatAll()} sends a keepalive comment (docs/REALTIME-PLAN.md §4, item 3). */
    static final long HEARTBEAT_MILLIS = 15_000L;

    /** Retained samples per asset's {@code telemetry} topic (FIFO — see {@link LiveRingBuffer}). */
    static final int TELEMETRY_BUFFER_CAPACITY = 50;

    /** Retained events on the shared {@code event} topic (FIFO — see {@link LiveRingBuffer}). */
    static final int EVENT_BUFFER_CAPACITY = 300;

    private final ObjectProvider<AssetService> assetService;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong sequencer = new AtomicLong(0L);
    private final ConcurrentHashMap<String, LiveConnection> connections = new ConcurrentHashMap<>();

    private final LiveRingBuffer fleetBuffer = new LiveRingBuffer(1, true);
    private final LiveRingBuffer eventBuffer = new LiveRingBuffer(EVENT_BUFFER_CAPACITY, false);
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> telemetryBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, LiveRingBuffer> detectionBuffers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<AssetId, ConcurrentLinkedQueue<Telemetry>> pendingTelemetry =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssetId, DetectionResult> pendingDetections = new ConcurrentHashMap<>();

    /**
     * {@code @Autowired} disambiguates this from the package-private test-seam constructor below
     * — Spring cannot pick one of two candidate constructors on its own.
     *
     * <p>{@code assetService} is an {@link ObjectProvider}, not a plain {@link AssetService},
     * deliberately: {@code DefaultAssetService} depends on {@code AuditTrailPort}, which — when
     * {@code vision.live.enabled=true} — is wrapped in {@code LiveUpdateAuditTrail}, which depends
     * on this exact port, which resolves to this class. A plain constructor-injected {@code
     * AssetService} here would make that a genuine circular bean dependency at context-startup
     * time; deferring the actual lookup to {@link #freshFleetEnvelope()} (only ever called well
     * after the whole context has finished starting) breaks the cycle without changing anything
     * about when a fleet snapshot is actually computed.
     */
    @Autowired
    public LiveUpdateRegistry(ObjectProvider<AssetService> assetService) {
        this(assetService, defaultScheduler());
    }

    /** Test seam: an injectable scheduler so tests can trigger {@link #flushPending()}/{@link #heartbeatAll()} directly instead of waiting on real timer ticks. */
    LiveUpdateRegistry(ObjectProvider<AssetService> assetService, ScheduledExecutorService scheduler) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
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
     * @param topicsParam the raw {@code topics} query parameter value — comma-separated {@code
     *                     telemetry:<assetId>}/{@code detections:<assetId>} entries; {@code
     *                     null}/blank means none requested. {@link LiveTopic#FLEET}/{@link
     *                     LiveTopic#EVENT} are added automatically regardless.
     * @param lastEventId  the {@code Last-Event-ID} header value, parsed to a {@code seq}, or
     *                     {@code null} if absent (a fresh connection, not a resume)
     * @return the emitter to return from the controller method
     * @throws IllegalArgumentException if {@code topicsParam} contains a malformed entry
     */
    public SseEmitter connect(String topicsParam, Long lastEventId) {
        Set<LiveTopic> requestedTopics = LiveTopic.parseTopicsParam(topicsParam);
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        LiveConnection connection = new LiveConnection(connectionId, emitter);
        connection.topics().add(LiveTopic.FLEET);
        connection.topics().add(LiveTopic.EVENT);
        connection.topics().addAll(requestedTopics);
        connections.put(connectionId, connection);

        emitter.onCompletion(() -> connections.remove(connectionId));
        emitter.onTimeout(() -> connections.remove(connectionId));
        emitter.onError(cause -> connections.remove(connectionId));

        try {
            connection.sendConnected(new LiveConnectedResponse(connectionId, wireTopics(connection.topics())));
            for (LiveTopic topic : connection.topics()) {
                for (LiveEnvelopeResponse envelope : replayFor(topic, lastEventId)) {
                    connection.send(envelope);
                }
            }
        } catch (IOException e) {
            connections.remove(connectionId);
        }
        return emitter;
    }

    /**
     * Adds/removes topics on an already-open connection (docs/REALTIME-PLAN.md §4, item 2) — a
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
                connection.topics().remove(topic); // FLEET/EVENT stay on regardless -- see class javadoc
            }
        }
        try {
            for (String raw : request.add()) {
                LiveTopic topic = LiveTopic.parse(raw);
                if (connection.topics().add(topic)) {
                    for (LiveEnvelopeResponse envelope : bufferFor(topic).snapshot()) {
                        connection.send(envelope);
                    }
                }
            }
        } catch (IOException e) {
            connections.remove(connectionId);
        }
        return new LiveSubscriptionResponse(connectionId, wireTopics(connection.topics()));
    }

    @Override
    public void publishFleetChanged() {
        scheduler.execute(() -> {
            LiveEnvelopeResponse envelope = freshFleetEnvelope();
            fleetBuffer.append(envelope);
            broadcast(LiveTopic.FLEET, envelope);
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

    private void broadcast(LiveTopic topic, LiveEnvelopeResponse envelope) {
        for (LiveConnection connection : connections.values()) {
            if (!connection.topics().contains(topic)) {
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
        if (topic.kind() == LiveTopicKind.FLEET && buffer.isEmpty()) {
            buffer.append(freshFleetEnvelope());
        }
        if (lastEventId != null && buffer.canResumeFrom(lastEventId)) {
            return buffer.since(lastEventId);
        }
        return buffer.snapshot();
    }

    /** Package-private for the same reason as {@link #replayFor} — lets a pure unit test inspect a topic's buffered state directly. */
    LiveRingBuffer bufferFor(LiveTopic topic) {
        return switch (topic.kind()) {
            case FLEET -> fleetBuffer;
            case EVENT -> eventBuffer;
            case TELEMETRY -> telemetryBuffers.computeIfAbsent(topic.assetId(),
                    id -> new LiveRingBuffer(TELEMETRY_BUFFER_CAPACITY, false));
            case DETECTIONS -> detectionBuffers.computeIfAbsent(topic.assetId(), id -> new LiveRingBuffer(1, true));
        };
    }

    private LiveEnvelopeResponse freshFleetEnvelope() {
        List<AssetSummaryResponse> snapshot =
                assetService.getObject().assets().stream().map(AssetSummaryResponse::from).toList();
        return new LiveEnvelopeResponse(sequencer.incrementAndGet(), null, LiveTopicKind.FLEET.wire(), snapshot);
    }

    private static List<String> wireTopics(Set<LiveTopic> topics) {
        return topics.stream().map(LiveTopic::wire).sorted().toList();
    }
}
