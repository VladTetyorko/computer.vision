package com.drones.vision.api.support.afteraction;

/**
 * What happened when resolving one part of an after-action package (docs/plans/active/
 * AFTER-ACTION-PLAN.md D3). Four states, deliberately not collapsed into each other:
 *
 * <ul>
 *   <li>{@link #PRESENT} — resolved in full; {@code count} &gt; 0 in every case except {@link
 *       #ABSENT}'s own {@code count == 0}.</li>
 *   <li>{@link #ABSENT} — resolved successfully, but there is genuinely nothing: no data exists
 *       for this flight. Not an error.</li>
 *   <li>{@link #TRUNCATED} — resolved, but the underlying source is known to hold more than what
 *       is included (docs/plans/done/AFTER-ACTION-PLAN.md D7) — a downstream lossiness, declared
 *       rather than silently shipped.</li>
 *   <li>{@link #FORBIDDEN} — the caller's role may not read this part at all. Deliberately distinct
 *       from {@link #ABSENT}: "there is nothing" and "you may not look" are different facts, and a
 *       referee must be able to tell them apart.</li>
 * </ul>
 */
public enum AfterActionPartState {
    PRESENT,
    ABSENT,
    TRUNCATED,
    FORBIDDEN
}
