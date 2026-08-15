package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MatchKey;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * The shared Family-A state machine (plan §2.2): <i>send X, await a reply matching a
 * {@link MatchKey} within a deadline, retry up to N times on silence, and let a reply itself decide
 * whether the exchange is done or should keep waiting a bit longer without a resend</i>. Command,
 * Mission (W6) and FTP (W6) are all instances of this one shape, differing only in timeout/retry
 * numbers and in how they build a payload and read a reply — see plan §2.2's table. This class is
 * deliberately generic over both: it is built once, here, as a standalone collaborator that
 * {@link CommandService} constructs and drives, not as a private helper folded into it.
 *
 * <h2>Why continuation-based, not thread-blocking</h2>
 * Every step is chained via {@link CompletableFuture#thenCompose}/{@code exceptionallyCompose}
 * rather than a thread parked on {@code Future#get}. A {@link Correlator}'s own waiter future
 * already completes asynchronously (matched by the RX thread that decoded the reply, or timed out by
 * the JDK's own delay scheduler — see {@code DefaultCorrelator}) — chaining onto it costs no thread
 * of our own, which matters because a broker-driven caller (plan §5.1) may have many exchanges in
 * flight at once and must not pay one blocked thread per outstanding command.
 *
 * <p><b>Duplicate replies are idempotent for free.</b> {@link Correlator#await}'s returned future is
 * consumed exactly once per registration and the key is released the moment it completes — a second,
 * late arrival of the same reply after this exchange has already moved on finds no live waiter and is
 * silently dropped by {@link Correlator}'s own implementation. This class adds no de-duplication of
 * its own because none is needed.
 *
 * <h2>A note on which thread a continuation runs on</h2>
 * {@link CompletableFuture} callbacks run, by default, on whichever thread completes the upstream
 * stage. A {@link Correlator} waiter is completed directly from the {@code MavlinkSession} reader
 * thread that decoded the matching frame (see that class's javadoc) — so a retry resend, or a
 * re-registration after a reply that only extended the deadline, can execute synchronously on that RX
 * thread rather than a thread of the caller's choosing. This is accepted for this wave: retries and
 * extensions are rare compared to the hot per-frame dispatch path {@code Dispatcher}'s own
 * must-not-block rule exists to protect, and a UDP {@link FrameSink#send} is normally non-blocking.
 * A caller that cannot accept this (e.g. wants every continuation off the RX thread) can wrap the
 * {@link FrameSink}/{@link Correlator} it hands to this class, or chain further with the {@code Async}
 * variants of {@code CompletableFuture} outside this class — not done here to avoid needing an
 * {@code Executor} this module has no opinion about.
 */
public final class RequestResponse {

    private final Correlator correlator;
    private final FrameSink sink;

    public RequestResponse(Correlator correlator, FrameSink sink) {
        this.correlator = Objects.requireNonNull(correlator, "correlator");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Builds the payload to send for a given 0-based attempt (0 = first send, 1.. = timeout retries). */
    @FunctionalInterface
    public interface AttemptPayload {
        Object forAttempt(int attempt);
    }

    /**
     * Classifies a reply that matched the {@link MatchKey}: {@code null} means the reply is
     * terminal (the exchange completes with it); a non-null, positive {@link Duration} means "keep
     * waiting on the same key for this long, without resending" (Family-A's {@code IN_PROGRESS}-style
     * continuation, plan §2.2).
     */
    @FunctionalInterface
    public interface ReplyClassifier {
        Duration extensionFor(MavFrame reply);
    }

    /**
     * Runs one Family-A exchange: sends {@code payload.forAttempt(0)} to {@code target}, awaits a
     * reply matching {@code key} within {@code timeout}. A reply hands control to {@code classifier}:
     * a {@code null} verdict completes the returned future with that reply; a {@link Duration}
     * verdict re-awaits the same key for that long, without resending. Silence (no reply of any kind
     * within {@code timeout}) retries — a fresh send via {@code payload.forAttempt(n)} — up to
     * {@code maxRetries} times; once exhausted, the returned future fails with the
     * {@link TimeoutException} the last attempt's wait ended with.
     *
     * @throws IllegalArgumentException if {@code maxRetries} is negative
     */
    public CompletableFuture<MavFrame> exchange(PeerId target, MatchKey key, Duration timeout, int maxRetries,
                                                 AttemptPayload payload, ReplyClassifier classifier) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(timeout, "timeout");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(classifier, "classifier");
        return sendAttempt(target, key, timeout, maxRetries, payload, classifier, 0);
    }

    private CompletableFuture<MavFrame> sendAttempt(PeerId target, MatchKey key, Duration timeout, int maxRetries,
                                                      AttemptPayload payload, ReplyClassifier classifier, int attempt) {
        CompletableFuture<MavFrame> waiter;
        try {
            // Registered before sending, per Correlator's own contract -- a very fast reply must
            // never be able to race ahead of our own registration.
            waiter = correlator.await(key, timeout);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        try {
            sink.send(payload.forAttempt(attempt), target);
        } catch (RuntimeException e) {
            correlator.cancel(key);
            return CompletableFuture.failedFuture(e);
        }
        int attemptNumber = attempt;
        // The retry-on-timeout handling is attached to `waiter` alone, BEFORE `continueOrComplete`
        // is chained on -- not to the combined result. If it were attached to the combined result,
        // a timeout inside `continueOrComplete`'s own extension chain (a DIFFERENT, terminal kind of
        // timeout -- see that method's own comment) would incorrectly be caught here too and treated
        // as eligible for a fresh resend.
        CompletableFuture<MavFrame> afterRetries = waiter.exceptionallyCompose(error ->
                retryOrFail(target, key, timeout, maxRetries, payload, classifier, attemptNumber, error));
        return afterRetries.thenCompose(frame -> continueOrComplete(key, frame, classifier));
    }

    private CompletableFuture<MavFrame> continueOrComplete(MatchKey key, MavFrame frame, ReplyClassifier classifier) {
        Duration extension = classifier.extensionFor(frame);
        if (extension == null) {
            return CompletableFuture.completedFuture(frame);
        }
        CompletableFuture<MavFrame> waiter;
        try {
            waiter = correlator.await(key, extension);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        // A timeout during an extension is a genuine, terminal failure -- it does NOT re-enter the
        // resend/retry path. Family-A's own rule (plan §2.2) is that IN_PROGRESS extends the
        // deadline, it never asks the requester to resend with a fresh confirmation.
        return waiter.thenCompose(next -> continueOrComplete(key, next, classifier));
    }

    private CompletableFuture<MavFrame> retryOrFail(PeerId target, MatchKey key, Duration timeout, int maxRetries,
                                                      AttemptPayload payload, ReplyClassifier classifier,
                                                      int attempt, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TimeoutException && attempt < maxRetries) {
            // A defensive, deterministic cancel before re-registering: Correlator#await's own
            // self-cleanup (releasing the just-timed-out waiter) and this exceptionallyCompose
            // callback are two independent dependents of the SAME timed-out future, and
            // CompletableFuture makes no ordering guarantee between them -- the self-cleanup can
            // genuinely still be pending when this callback runs, which would make the retry's own
            // await() below throw IllegalStateException ("a live waiter is already registered")
            // for a waiter that is, semantically, already dead. cancel() is idempotent and correct
            // either way: a no-op if cleanup already ran, and a real release if it has not yet.
            correlator.cancel(key);
            return sendAttempt(target, key, timeout, maxRetries, payload, classifier, attempt + 1);
        }
        CompletableFuture<MavFrame> failed = new CompletableFuture<>();
        failed.completeExceptionally(error);
        return failed;
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
