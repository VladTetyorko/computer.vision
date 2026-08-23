package com.drones.vision.adapter.publishhls;

/**
 * Thrown when a mediamtx v3 Control API call ({@link MediamtxControlApi}) fails in a way {@link
 * MediamtxProxyPublisher} cannot silently recover from: an unreachable/misconfigured mediamtx, an
 * unexpected HTTP status, or — the case wave M0 found blocking (docs/conclusions/CV-PULL-SPIKE.md
 * &sect;5) — mediamtx's Control API rejecting the caller's IP/credentials outright.
 *
 * <h2>Where this is allowed to propagate</h2>
 * Unlike {@link MediamtxStreamPublisher}'s "nothing escapes {@code publish}/{@code
 * streamStarted}/{@code streamEnded}" posture (a continuous push that must tolerate a flaky
 * mediamtx mid-stream), {@link MediamtxProxyPublisher#streamStarted} lets this propagate
 * deliberately: a proxied path that never became ready must fail the start call, not hand a viewer
 * a URL that plays nothing (docs/plans/done/MEDIA-SOT-PLAN.md &sect;12's own named risk, and the
 * concrete reason this type exists rather than being swallowed and logged). {@link
 * MediamtxProxyPublisher#streamEnded}, by contrast, still catches and logs this at {@code WARNING} —
 * teardown must not block a caller just because mediamtx could not be reached to clean up after
 * itself, mirroring {@link MediamtxStreamPublisher}'s own resilience posture for that half of the
 * lifecycle.
 */
public final class MediamtxControlApiException extends RuntimeException {

    public MediamtxControlApiException(String message) {
        super(message);
    }

    public MediamtxControlApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
