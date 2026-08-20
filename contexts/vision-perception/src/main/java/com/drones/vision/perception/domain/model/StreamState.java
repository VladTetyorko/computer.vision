package com.drones.vision.perception.domain.model;

/**
 * Whether a running stream's <b>video</b> is actually flowing (docs/plans/active/STREAM-STATE-PLAN.md
 * &sect;2.1), made legible instead of left for a client to reconstruct by latching on a poll gap
 * &mdash; the guess {@code cockpit-facade.ts} had to make while {@code GET /api/streams} carried no
 * state at all.
 *
 * <p><b>This enum reports video flow, never detection.</b> It is the third of three deliberately
 * separate axes: this one (are frames arriving?), {@link DetectionState} (are both CV gates open?),
 * and {@code AssetUsage} (did this session happen, and when did it end?). A stream may be
 * {@link #LIVE} while detection reads {@code OFF}, {@code IDLE_NO_VIEWERS} or {@code RUNNING}
 * alike, and {@link #STALLED} says nothing whatever about the detector &mdash; collapsing any two
 * of the three rebuilds exactly the ambiguity {@link DetectionState} was added to remove.
 *
 * <p><b>There is deliberately no {@code STOPPED}.</b> A stopped stream is not a running stream in
 * another state: it is absent from {@code StreamService#streams()} and present in {@code AssetUsage}
 * with an {@code endedAt}. A {@code STOPPED} member here would make one enum answer two axes.
 */
public enum StreamState {

    /**
     * Started, but no frame has been observed yet. Not a handshake state &mdash; {@code
     * StreamService#start} is synchronous and the pipeline is already subscribed when it returns, so
     * this is the genuine "opened, nothing has arrived" window, normally over within a frame period.
     */
    STARTING,

    /** A frame arrived within the staleness window. */
    LIVE,

    /**
     * Frames were arriving and stopped, with no reopen in progress. A fault worth surfacing: unlike
     * {@link #RECONNECTING}, nothing is currently trying to fix it.
     */
    STALLED,

    /**
     * The source's {@code SupervisedPublisher} is in reopen backoff (docs/plans/done/MVP2-PLAN.md
     * &sect;S, S-a). Also a fault, but a self-healing one, and a reader must present it differently
     * from {@link #STALLED} &mdash; the stream is expected to recover on its own.
     */
    RECONNECTING,

    /**
     * A <b>proxied</b> source (docs/plans/active/MEDIA-SOT-PLAN.md D4): the active publisher dials the
     * device itself, this JVM opens no {@code VideoSourcePort}, and no frame ever reaches it &mdash;
     * so liveness is genuinely unknowable from here.
     *
     * <p><b>Explicitly not a fault.</b> "I cannot see" is not "it is broken", and a reader that
     * renders this as a red light is reporting a defect that does not exist. This state is also why
     * {@link #STARTING} cannot double as "no frames yet": for a proxied stream that condition holds
     * forever, and a {@code STARTING} that never ends is a lie with a timestamp on it.
     */
    UNOBSERVED;

    /**
     * Resolves the state from the four facts a running stream can actually report about itself.
     *
     * <p>Order matters and is pinned by the plan: {@link #RECONNECTING} outranks {@link #STARTING}
     * and {@link #STALLED} because while the supervisor is retrying, "no frames" is <i>explained</i>,
     * and reporting the symptom over its known cause throws information away.
     *
     * <p>Pure and total by construction &mdash; it takes primitives rather than a pipeline so it can
     * be exercised directly, and every input combination yields a state.
     *
     * @param sourceObservable   whether this JVM opened a video source at all; {@code false} for a
     *                           proxied stream, which can only ever be {@link #UNOBSERVED}
     * @param reconnecting       whether the source's supervisor is currently in reopen backoff
     * @param framesObserved     how many frames the pipeline has seen; {@code 0} means none yet
     * @param nanosSinceLastFrame nanoseconds since the last frame arrived, {@link Long#MAX_VALUE}
     *                           when none ever has
     * @param staleAfterNanos    how long without a frame counts as {@link #STALLED}
     * @return the one state these facts describe, never {@code null}
     */
    public static StreamState resolve(boolean sourceObservable, boolean reconnecting, long framesObserved,
                                       long nanosSinceLastFrame, long staleAfterNanos) {
        if (!sourceObservable) {
            return UNOBSERVED;
        }
        if (reconnecting) {
            return RECONNECTING;
        }
        if (framesObserved == 0L) {
            return STARTING;
        }
        return nanosSinceLastFrame > staleAfterNanos ? STALLED : LIVE;
    }
}
