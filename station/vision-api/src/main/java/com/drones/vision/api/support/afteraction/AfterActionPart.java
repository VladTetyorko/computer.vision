package com.drones.vision.api.support.afteraction;

import java.util.Objects;

/**
 * One row of an after-action manifest's {@code parts} array (docs/plans/done/AFTER-ACTION-PLAN.md
 * &sect;3.1): what this evidence category resolved to, how much of it there is, and — for anything
 * other than an unqualified {@link AfterActionPartState#PRESENT} — why.
 *
 * @param part  which evidence category this row describes
 * @param state what happened resolving it
 * @param count how many items this part carries; {@code 0} for {@link AfterActionPartState#ABSENT}
 *              and {@link AfterActionPartState#FORBIDDEN}, never negative
 * @param note  human-readable qualifier; {@code null} only when {@code state ==
 *              PRESENT} and there is nothing to qualify (docs/plans/done/AFTER-ACTION-PLAN.md
 *              &sect;3.1's own rule) — every other state, and a {@code PRESENT} marks row (D5), carries one
 */
public record AfterActionPart(AfterActionPartKind part, AfterActionPartState state, int count, String note) {

    public AfterActionPart {
        Objects.requireNonNull(part, "part must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (count < 0) {
            throw new IllegalArgumentException("AfterActionPart count must not be negative: " + count);
        }
    }
}
