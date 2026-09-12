package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One open {@code DetectStream} bidi call for a single {@link StreamId}: owns the request observer,
 * the write lock guarding it, and the sequence&rarr;future correlation map for responses still in
 * flight. Promoted out of {@link GrpcDetectionPort} to a top-level class with zero references back
 * to its creator — everything it needs ({@code asyncStub}, the owning {@code sessions} map, and the
 * per-future response timeout) is passed in explicitly.
 *
 * <h2>Correlation</h2>
 * Each frame is sent as one {@code FrameRequest} keyed in a {@code ConcurrentHashMap<Long,
 * CompletableFuture<DetectionResult>>} by {@code sequence} (per-stream-monotonic). The matching
 * {@code DetectionResponse} (correlated back by its own {@code sequence} field, not by re-parsing the
 * wire-echoed {@code stream_id}) completes that specific future — proven under out-of-order server
 * replies. The mapped {@link DetectionResult#streamId()} is always this session's own (domain) {@link
 * StreamId}, never a re-parse of the response's wire {@code stream_id} string, so correlation never
 * depends on the server echoing that field correctly.
 *
 * <p>gRPC stream observers are not thread-safe: every write to the request observer (including the
 * lazy open) is serialized through {@link #writeLock}. Concurrent {@link #send} calls for the same
 * session are therefore safe; different sessions never contend on the same lock.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>Transport failure</b> ({@code onError} from the server): every pending future fails
 *   immediately with the transport exception, and this session drops itself from the owning map —
 *   the next {@code detect()} for this {@link StreamId} transparently opens a fresh call.</li>
 *   <li><b>Server ends the stream normally</b> ({@code onCompleted} with no error): treated the same
 *   as a transport failure — a live bidi call is never expected to complete server-side on its own.</li>
 *   <li><b>Hung or unreachable service, including a session opened while cv-service is down
 *   entirely</b> (no error, just silence): a bidi call has no natural per-call deadline, so every
 *   pending future instead gets its own {@code responseTimeoutMillis} timeout ({@link
 *   CompletableFuture#orTimeout}). The first future to time out cancels the underlying call, fails
 *   every other pending future for this session immediately, and drops the session — so the next
 *   {@code detect()} always opens a fresh call instead of reusing one that may never recover.</li>
 *   <li><b>A response whose {@code stream_id} names a different stream</b> (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 *   R4 surprise 2): logged at WARNING (naming both ids) and dropped — the pending future for that
 *   sequence, if any, is left in the map untouched and times out on its own, exactly like a sequence
 *   that never got any response at all. An <em>empty</em> {@code stream_id} is not a mismatch: it is
 *   a cv-service that never set the field, a legitimate older peer this check must not break.</li>
 * </ul>
 *
 * <p><b>Teardown is idempotent</b> ({@link #torndown}, an {@link AtomicBoolean} CAS guard): a timeout
 * and a transport error (or an explicit {@link #endAndClose}) racing each other resolve to exactly
 * one teardown — whichever trigger wins does the real work, every other trigger for the same session
 * is a no-op.
 *
 * <h2>Stream lifecycle</h2>
 * {@link #endAndClose} fails any still-pending futures with a {@link CancellationException},
 * half-closes the request observer (best-effort), and drops this session from the owning map. It is
 * the explicit-hook counterpart to the failure paths above — used when a caller (e.g. {@code
 * GrpcDetectionPort#streamEnded}) knows the stream has ended, rather than waiting for a timeout or
 * transport signal to discover it.
 */
final class DetectionStreamSession {

    private static final System.Logger LOG = System.getLogger(DetectionStreamSession.class.getName());

    private final StreamId streamId;
    private final InferenceGrpc.InferenceStub asyncStub;
    private final ConcurrentHashMap<StreamId, DetectionStreamSession> sessions;
    private final long responseTimeoutMillis;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Long, CompletableFuture<DetectionResult>> pending = new ConcurrentHashMap<>();

    /**
     * Guards session teardown so it runs exactly once, however it is triggered (transport {@code
     * onError}/{@code onCompleted}, a pending future's own response timeout, or an explicit {@link
     * #endAndClose}) — these race genuinely (e.g. a timeout firing on one gRPC executor thread just
     * as {@code onError} lands on another), so whichever gets here first tears the session down and
     * every other trigger becomes a no-op instead of double-failing futures or double-cancelling the
     * call.
     */
    private final AtomicBoolean torndown = new AtomicBoolean(false);

    private volatile StreamObserver<FrameRequest> requestObserver;

    /**
     * @param sessions              the owning {@link GrpcDetectionPort}'s session map; this session
     *                               removes itself from it on teardown
     * @param responseTimeoutMillis per-pending-future response timeout, in milliseconds (see class
     *                               javadoc's "Hung or unreachable service" case)
     */
    DetectionStreamSession(StreamId streamId, InferenceGrpc.InferenceStub asyncStub,
                           ConcurrentHashMap<StreamId, DetectionStreamSession> sessions,
                           long responseTimeoutMillis) {
        this.streamId = streamId;
        this.asyncStub = asyncStub;
        this.sessions = sessions;
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    CompletionStage<DetectionResult> send(long sequence, FrameRequest request) {
        CompletableFuture<DetectionResult> future = new CompletableFuture<>();
        pending.put(sequence, future);
        future.orTimeout(responseTimeoutMillis, TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> {
            pending.remove(sequence, future);
            if (error instanceof TimeoutException timeout) {
                onResponseTimeout(sequence, timeout);
            }
        });

        synchronized (writeLock) {
            try {
                openIfNeeded().onNext(request);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "Failed to send frame " + sequence + " on detection stream " + streamId, e);
                failAllAndDrop(e);
            }
        }
        return future;
    }

    /** Must be called while holding {@link #writeLock}. */
    private StreamObserver<FrameRequest> openIfNeeded() {
        StreamObserver<FrameRequest> observer = requestObserver;
        if (observer == null) {
            LOG.log(System.Logger.Level.INFO, () -> "Opening detection stream for " + streamId);
            observer = asyncStub.detectStream(new ResponseHandler());
            requestObserver = observer;
        }
        return observer;
    }

    private void onResponse(DetectionResponse response) {
        // R4 surprise 2 (docs/plans/active/CV-ORCHESTRATION-PLAN.md): stream_id has always been on the
        // wire and, until this wave, never read. Checked BEFORE pending.remove: a mismatch must leave
        // the pending future (if any) untouched in the map rather than completed, so it times out
        // exactly like a sequence that genuinely never got any response -- via that future's own
        // orTimeout, armed independently of map membership back in #send. Removing it here first would
        // not shorten that timeout (nothing gates it on map presence), it would only make a dropped
        // response indistinguishable from a real completion for the map's own bookkeeping. An empty
        // wire stream_id is not a mismatch -- it is a server that never set the field, a legitimate
        // older peer this check must not break.
        String wireStreamId = response.getStreamId();
        if (!wireStreamId.isEmpty() && !wireStreamId.equals(streamId.value().toString())) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Detection response stream_id mismatch: session is for " + streamId
                            + " but response carried " + wireStreamId + " for sequence " + response.getSequence()
                            + "; dropping it");
            return;
        }
        CompletableFuture<DetectionResult> future = pending.remove(response.getSequence());
        if (future == null) {
            return; // already timed out, or an unrecognized/late sequence -- nothing to complete
        }
        try {
            future.complete(DetectionFrameCodec.decode(streamId, response));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
    }

    /**
     * A connection-refused (or any other transport) failure looks identical every time it happens —
     * same status code, same message, same stack — so a full stack trace at WARN on every teardown is
     * pure noise once cv-service has been down for more than an instant (docs/plans/active/CV-RECONNECT-PLAN.md
     * &sect;3.2). The one-line WARN carries the {@link Status} code and message, which is everything
     * an operator needs to recognize "cv-service is down" at a glance; the full throwable (for anyone
     * who does need the stack) still goes to DEBUG. "will retry with backoff" replaces the old "will
     * reopen on next detect()" wording — with a {@link CvChannelSupervisor} gating {@link
     * GrpcDetectionPort#detect}, backoff (not the next arbitrary probe) is what actually governs when
     * a fresh call gets a chance to succeed.
     */
    private void onTransportError(Throwable t) {
        Status status = Status.fromThrowable(t);
        LOG.log(System.Logger.Level.WARNING, () -> "Detection stream for " + streamId + " failed ("
                + status.getCode() + "): " + t.getMessage() + "; will retry with backoff");
        LOG.log(System.Logger.Level.DEBUG, () -> "Full failure detail for detection stream " + streamId, t);
        failAllAndDrop(t);
    }

    private void onServerCompleted() {
        failAllAndDrop(new IllegalStateException("Detection stream for " + streamId + " completed unexpectedly"));
    }

    /**
     * A pending future's own response timeout fired. A live, healthy session always gets either a
     * response or a transport {@code onError} well within that window, so this means the session
     * itself is presumed dead — most notably the case a session opened while the CV service was down
     * entirely: the call can sit half-open with no {@code onError} ever delivered. Treated exactly
     * like a transport error: the call is cancelled and every other still-pending future for this
     * stream fails immediately instead of waiting out its own timer.
     */
    private void onResponseTimeout(long sequence, TimeoutException cause) {
        LOG.log(System.Logger.Level.WARNING,
                () -> "Detection stream for " + streamId + " timed out waiting for a response to frame "
                        + sequence + "; treating session as dead and reopening on next detect()");
        if (failAllAndDrop(cause)) {
            cancelCall(cause);
        }
    }

    /**
     * Fails every still-pending future and drops this session so the next {@code detect()} reopens,
     * exactly once (see {@link #torndown}).
     *
     * @return {@code true} if this call actually performed the teardown (i.e. it won the race);
     * {@code false} if the session was already torn down by another trigger, in which case there is
     * nothing left to do.
     */
    private boolean failAllAndDrop(Throwable cause) {
        if (!torndown.compareAndSet(false, true)) {
            return false;
        }
        sessions.remove(streamId, this);
        pending.values().forEach(future -> future.completeExceptionally(cause));
        return true;
    }

    /** Best-effort: cancels the underlying call so a dead/hung transport is discarded instead of lingering. */
    private void cancelCall(Throwable cause) {
        StreamObserver<FrameRequest> observer = requestObserver;
        if (observer instanceof ClientCallStreamObserver<FrameRequest> clientCallObserver) {
            try {
                clientCallObserver.cancel("Detection stream for " + streamId + " timed out", cause);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "Ignoring error cancelling detection stream for " + streamId, e);
            }
        }
    }

    /** Fails pending futures, half-closes the request observer, and drops this session. Idempotent. */
    void endAndClose() {
        if (!torndown.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(streamId, this);
        pending.values().forEach(future -> future.completeExceptionally(
                new CancellationException("Detection stream for " + streamId + " ended")));
        StreamObserver<FrameRequest> observer = requestObserver;
        if (observer != null) {
            try {
                observer.onCompleted();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "Ignoring error half-closing detection stream for " + streamId, e);
            }
        }
    }

    private final class ResponseHandler implements StreamObserver<DetectionResponse> {
        @Override
        public void onNext(DetectionResponse response) {
            onResponse(response);
        }

        @Override
        public void onError(Throwable t) {
            onTransportError(t);
        }

        @Override
        public void onCompleted() {
            onServerCompleted();
        }
    }
}
