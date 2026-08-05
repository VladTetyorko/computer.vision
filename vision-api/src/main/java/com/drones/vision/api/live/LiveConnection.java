package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveConnectedResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.MapEventPayload;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * One open {@code GET /api/live} connection: its {@link SseEmitter}, the mutable set of topics it
 * currently cares about (docs/REALTIME-PLAN.md §4, item 2 — grown/shrunk in place by {@code PATCH
 * /api/live/{connectionId}/topics} without reconnecting), and the map-visibility predicate captured
 * for its viewer at connect time (docs/MAP-REWORK-PLAN.md §4.3).
 *
 * <h2>Threading</h2>
 * {@link #topics()} is a concurrent set — safe to read/mutate from the connecting request thread,
 * a later {@code PATCH} request thread, and the shared dispatcher thread broadcasting updates, all
 * without external locking. Every {@code send*}/{@link #heartbeat()} call serializes on one lock
 * per connection, since {@link SseEmitter#send} is not safe to call concurrently from two threads
 * for the same emitter.
 */
final class LiveConnection {

    private final String id;
    private final SseEmitter emitter;
    private final Predicate<String> mapVisibility;
    private final Set<LiveTopic> topics = ConcurrentHashMap.newKeySet();
    private final Object sendLock = new Object();

    /**
     * @param mapVisibility whether this connection's viewer may see a {@code map} event about a given
     *                      {@code layerId} — supplied by {@code LiveController} from {@link
     *                      MapVisibility#deliveryPredicate}, so the registry never has to resolve an
     *                      identity itself. Evaluated fresh on every event and every resume replay,
     *                      not snapshotted, so a grant change takes effect within the predicate's own
     *                      staleness bound.
     */
    LiveConnection(String id, SseEmitter emitter, Predicate<String> mapVisibility) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
        this.mapVisibility = Objects.requireNonNull(mapVisibility, "mapVisibility must not be null");
    }

    String id() {
        return id;
    }

    /**
     * Whether this connection should receive {@code envelope} at all.
     *
     * <p>Every topic except {@code map} broadcasts to everyone subscribed, so anything that is not a
     * {@link MapEventPayload} passes unconditionally — the check is keyed off the payload type
     * rather than the topic so a buffered envelope carries its own filtering information with it,
     * which is what makes {@code Last-Event-ID} resume re-filterable with no parallel bookkeeping.
     *
     * @param envelope the envelope about to be sent
     * @return {@code true} if this connection's viewer may see it
     */
    boolean mayReceive(LiveEnvelopeResponse envelope) {
        return !(envelope.payload() instanceof MapEventPayload payload) || mapVisibility.test(payload.layerId());
    }

    /**
     * @return the live, mutable topic set — always contains {@link LiveTopic#FLEET}/{@link
     *         LiveTopic#EVENT}, plus whatever {@code telemetry:<assetId>}/{@code
     *         detections:<assetId>} topics were requested at connect time or added since
     */
    Set<LiveTopic> topics() {
        return topics;
    }

    void sendConnected(LiveConnectedResponse connected) throws IOException {
        synchronized (sendLock) {
            emitter.send(SseEmitter.event().name("connection").data(connected, MediaType.APPLICATION_JSON));
        }
    }

    void send(LiveEnvelopeResponse envelope) throws IOException {
        synchronized (sendLock) {
            emitter.send(SseEmitter.event().id(Long.toString(envelope.seq())).data(envelope, MediaType.APPLICATION_JSON));
        }
    }

    /** A comment line (not a {@code data:} event — never reaches {@code EventSource.onmessage}) so proxies don't kill an idle connection. */
    void heartbeat() throws IOException {
        synchronized (sendLock) {
            emitter.send(SseEmitter.event().comment("keepalive"));
        }
    }

    void completeWithError(Throwable cause) {
        emitter.completeWithError(cause);
    }
}
