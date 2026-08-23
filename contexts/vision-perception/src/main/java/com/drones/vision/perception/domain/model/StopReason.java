package com.drones.vision.perception.domain.model;

/**
 * Why a stream stopped (docs/plans/done/STREAM-STATE-PLAN.md &sect;3.2) &mdash; carried on the
 * {@code STREAM_STOPPED} event so a stop that <b>the system chose</b> can be told apart from one an
 * operator asked for.
 *
 * <p>This distinction is not cosmetic. Before the idle policy existed, every stop had a human behind
 * it, so "Stream stopped" was a complete explanation. A stream that vanishes on its own with that
 * same message reads as a crash &mdash; an operator's next move is to go looking for a fault that is
 * not there, and the honest answer ("nobody had been watching for ten minutes") is exactly the one
 * thing the message did not say.
 *
 * @see com.drones.vision.perception.application.stream.IdleStreamReaper
 */
public enum StopReason {
    /** A person (or an API client acting for one) asked for it &mdash; {@code DELETE /api/streams/{id}}. */
    OPERATOR,
    /**
     * The idle policy stopped it: no video demand for {@code vision.streams.idle.timeout}. Not a
     * fault, and deliberately <b>not</b> reported as one.
     */
    IDLE_NO_VIEWERS
}
