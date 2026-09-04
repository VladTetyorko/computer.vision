package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.StreamState;

/**
 * Notified by {@link DefaultStreamService} on every genuine {@link StreamState} transition it
 * computes (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C6) — the {@code
 * UsagePhaseObserver}/O12 shape: a single method, a {@link #NOOP} no-op constant instead of a
 * nullable "off" parameter, and a caller that swallows a misbehaving implementation's exception
 * rather than letting it break the pipeline (see {@code DefaultStreamService}'s own javadoc for
 * exactly where this is invoked and how failures are isolated).
 *
 * <p><b>Edge-triggered, not sampled.</b> This fires only when a freshly computed state differs from
 * the previously computed one for the same stream — never on the state a stream is first observed
 * in (nothing to compare against yet), and never again for an unchanged value on a repeated read.
 * {@code from}/{@code to} are therefore always different.
 *
 * <p>Fired synchronously, on whatever thread computed the transition ({@link
 * DefaultStreamService#streams()}/{@link DefaultStreamService#streamState(StreamId)}'s caller) —
 * implementations that need to do real work should hand off rather than block that thread.
 */
@FunctionalInterface
public interface StreamStateObserver {

    /**
     * A no-op observer — the default when nothing needs to react to state transitions.
     */
    StreamStateObserver NOOP = (streamId, from, to) -> {
        // deliberately does nothing
    };

    /**
     * @param streamId the stream whose computed state changed
     * @param from     the previously computed state
     * @param to       the newly computed state; never equal to {@code from}
     */
    void streamStateChanged(StreamId streamId, StreamState from, StreamState to);
}
