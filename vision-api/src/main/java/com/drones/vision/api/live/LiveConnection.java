package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveConnectedResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One open {@code GET /api/live} connection: its {@link SseEmitter} plus the mutable set of
 * topics it currently cares about (docs/REALTIME-PLAN.md §4, item 2 — grown/shrunk in place by
 * {@code PATCH /api/live/{connectionId}/topics} without reconnecting).
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
    private final Set<LiveTopic> topics = ConcurrentHashMap.newKeySet();
    private final Object sendLock = new Object();

    LiveConnection(String id, SseEmitter emitter) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
    }

    String id() {
        return id;
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
