package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveConnectedResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.MapEventPayload;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * One open {@code GET /api/live} connection: its {@link SseEmitter}, the mutable set of topics it
 * currently cares about (docs/plans/done/REALTIME-PLAN.md §4, item 2 — grown/shrunk in place by {@code PATCH
 * /api/live/{connectionId}/topics} without reconnecting), the {@link UserId} it belongs to
 * (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W3 — so {@code LiveUpdateRegistry#updateTopics} can
 * refuse a caller who does not own it), and the map-visibility/asset-visibility predicates captured
 * for its viewer at connect time (docs/plans/done/MAP-REWORK-PLAN.md §4.3; docs/plans/active/LIVE-SCOPE-PLAN.md
 * §2, W3).
 *
 * <h2>Threading</h2>
 * {@link #topics()} is a concurrent set — safe to read/mutate from the connecting request thread,
 * a later {@code PATCH} request thread, and the shared dispatcher thread broadcasting updates, all
 * without external locking. Every {@code send*}/{@link #heartbeat()} call still acquires {@link
 * #sendLock} around the actual {@link SseEmitter#send} call, since two writes to the same emitter
 * must never interleave on the wire — but {@link #sendLock} is a {@link ReentrantLock}, not {@code
 * synchronized}: this connection's write is dispatched onto a virtual thread (docs/plans/active/SCALE-100-PLAN.md
 * §5 S2 item 2, see {@code LiveUpdateRegistry}'s {@code connectionWriteExecutor}), and a {@code
 * synchronized} block held across a blocking I/O call pins that virtual thread's carrier for the
 * whole blocked duration regardless of contention — exactly the kind of stall this wave exists to
 * remove. {@link ReentrantLock} parks instead of pinning.
 *
 * <h2>Ordering under concurrent dispatch</h2>
 * A lock (fair or not) only excludes concurrent execution; it makes no promise about which waiting
 * thread runs next once dispatch runs on a virtual-thread-per-task executor with no shared, ordered
 * work queue — two envelopes submitted A-then-B could race to acquire the lock B-then-A. {@link
 * #enqueueSend}/{@link #enqueueHeartbeat} close that gap: each queues its write onto {@link
 * #writeChain}, a per-connection {@link CompletableFuture} chain, so a later write cannot even
 * <em>start</em> running until the earlier one has finished — ordering by construction, not by
 * scheduling luck. {@link #sendConnected} (the {@code connect()} handshake burst) is not chained —
 * it runs synchronously, once, before this connection is reachable by a concurrent dispatch for any
 * topic it did not yet subscribe to; {@link #sendLock} alone is enough to keep it from corrupting a
 * write that races in from another topic mid-burst.
 */
final class LiveConnection {

    private final String id;
    private final SseEmitter emitter;
    private final UserId ownerUserId;
    private final Predicate<String> mapVisibility;
    private final Predicate<AssetId> assetVisibility;
    private final Set<LiveTopic> topics = ConcurrentHashMap.newKeySet();
    private final ReentrantLock sendLock = new ReentrantLock();
    private final AtomicReference<CompletableFuture<Void>> writeChain =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    /**
     * @param ownerUserId     who opened this connection (docs/plans/active/LIVE-SCOPE-PLAN.md §2,
     *                        W3) — a plain identity value, never a live handle back to the request;
     *                        used only for {@code LiveUpdateRegistry#updateTopics}'s ownership check
     * @param mapVisibility   whether this connection's viewer may see a {@code map} event about a
     *                        given {@code layerId} — supplied by {@code LiveController} from {@link
     *                        MapVisibility#deliveryPredicate}, so the registry never has to resolve
     *                        an identity itself. Evaluated fresh on every event and every resume
     *                        replay, not snapshotted, so a grant change takes effect within the
     *                        predicate's own staleness bound.
     * @param assetVisibility whether this connection's viewer may currently see a per-asset topic's
     *                        {@link AssetId} — supplied by {@code LiveController} from {@code
     *                        LiveAssetAccess#deliveryPredicate} (docs/plans/active/LIVE-SCOPE-PLAN.md
     *                        §2, W3), same evaluated-fresh contract as {@code mapVisibility}
     */
    LiveConnection(String id, SseEmitter emitter, UserId ownerUserId, Predicate<String> mapVisibility,
                   Predicate<AssetId> assetVisibility) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.mapVisibility = Objects.requireNonNull(mapVisibility, "mapVisibility must not be null");
        this.assetVisibility = Objects.requireNonNull(assetVisibility, "assetVisibility must not be null");
    }

    String id() {
        return id;
    }

    /**
     * @return the {@link UserId} that opened this connection — {@code LiveUpdateRegistry#updateTopics}'s
     *         ownership check (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W3)
     */
    UserId ownerUserId() {
        return ownerUserId;
    }

    /**
     * Whether this connection should receive {@code envelope} at all.
     *
     * <p>Two independent filters, keyed off what the envelope actually carries rather than which
     * topic it arrived on — so a buffered envelope carries its own filtering information with it,
     * which is what makes {@code Last-Event-ID} resume re-filterable with no parallel bookkeeping:
     * <ul>
     *   <li>a {@link MapEventPayload} is gated by {@link #mapVisibility} on its {@code layerId}
     *       (docs/plans/done/MAP-REWORK-PLAN.md §4.3);</li>
     *   <li>anything else carrying a non-null {@link LiveEnvelopeResponse#assetId()} (a {@code
     *       telemetry}/{@code detections}/{@code geo} envelope) is gated by {@link #assetVisibility}
     *       (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W3) — this is the defense against a scope
     *       changing mid-connection (an assignment revoked) after a topic was legitimately
     *       subscribed to: {@code LiveController}/{@code LiveAssetAccess} already keep an
     *       unauthorized topic from ever being added (see their own javadoc), so this check is what
     *       keeps enforcing that decision for the rest of the connection's life, not only at the
     *       moment the topic was added.</li>
     * </ul>
     * Everything else (no asset id, not a map payload — {@code fleet}/{@code event}/{@code
     * devices}/{@code detection-events}) passes unconditionally.
     *
     * @param envelope the envelope about to be sent
     * @return {@code true} if this connection's viewer may see it
     */
    boolean mayReceive(LiveEnvelopeResponse envelope) {
        if (envelope.payload() instanceof MapEventPayload payload) {
            return mapVisibility.test(payload.layerId());
        }
        if (envelope.assetId() != null) {
            return assetVisibility.test(AssetId.of(envelope.assetId()));
        }
        return true;
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
        sendLock.lock();
        try {
            emitter.send(SseEmitter.event().name("connection").data(connected, MediaType.APPLICATION_JSON));
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * Writes one already-serialized envelope (docs/plans/active/SCALE-100-PLAN.md §5 S2 item 1 — the caller
     * serializes once in {@code LiveUpdateRegistry#serialize} and hands the same {@code String} to
     * every subscribed connection, rather than each connection re-encoding the same object). {@code
     * seq} still carries the SSE event's own {@code id:} field so {@code Last-Event-ID} resume keeps
     * working unchanged.
     *
     * @param seq  the envelope's sequence number, sent as the SSE {@code id:} field
     * @param json the envelope, already serialized to JSON
     */
    void send(long seq, String json) throws IOException {
        sendLock.lock();
        try {
            emitter.send(SseEmitter.event().id(Long.toString(seq)).data(json, MediaType.APPLICATION_JSON));
        } finally {
            sendLock.unlock();
        }
    }

    /** A comment line (not a {@code data:} event — never reaches {@code EventSource.onmessage}) so proxies don't kill an idle connection. */
    void heartbeat() throws IOException {
        sendLock.lock();
        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * Queues a data-envelope write onto {@link #writeChain} (docs/plans/active/SCALE-100-PLAN.md §5 S2 item 2)
     * — see this class's "Ordering under concurrent dispatch" javadoc. The returned future completes
     * (successfully or exceptionally) once the write has actually run; it never completes exceptionally
     * with a checked type since {@link #send} is wrapped in {@link UncheckedIOException}, and it never
     * throws synchronously — the caller ({@code LiveUpdateRegistry#dispatchWrite}) attaches its own
     * timeout and failure handling.
     *
     * @param seq      the envelope's sequence number
     * @param json     the envelope, already serialized to JSON
     * @param executor where the write actually runs
     * @return a future completing when the write has run
     */
    CompletableFuture<Void> enqueueSend(long seq, String json, Executor executor) {
        return enqueueWrite(() -> send(seq, json), executor);
    }

    /** Queues a heartbeat write onto {@link #writeChain} — see {@link #enqueueSend}. */
    CompletableFuture<Void> enqueueHeartbeat(Executor executor) {
        return enqueueWrite(this::heartbeat, executor);
    }

    private CompletableFuture<Void> enqueueWrite(IoRunnable write, Executor executor) {
        return writeChain.updateAndGet(previous -> previous.thenRunAsync(() -> runOrThrow(write), executor));
    }

    private static void runOrThrow(IoRunnable write) {
        try {
            write.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void completeWithError(Throwable cause) {
        emitter.completeWithError(cause);
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }
}
