package com.drones.vision.api.dto;

import java.time.Instant;

/**
 * One seat's wire shape (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.6, frozen) — either half of
 * {@link SeatsResponse}.
 *
 * <p>Deliberately carries <b>no</b> {@code @JsonInclude}: a free seat serializes as four explicit
 * {@code null}s, not an omitted object — "an explicit {@code null} is the honest 'nobody'" (&sect;3.6).
 * {@link #free()} is exactly that shape.
 *
 * @param holderUserId      the holder's raw id, or {@code null} if free
 * @param holderDisplayName the holder's display name (falling back to the raw id if the user has
 *                          since been removed), or {@code null} if free
 * @param acquiredAt        when the current hold began, or {@code null} if free
 * @param expiresAt         when the current hold lapses absent a renewal, or {@code null} if free
 * @param mine              whether the <em>caller</em> holds this seat; always {@code false} when free
 */
public record SeatHolderResponse(String holderUserId, String holderDisplayName, Instant acquiredAt,
                                  Instant expiresAt, boolean mine) {

    private static final SeatHolderResponse FREE = new SeatHolderResponse(null, null, null, null, false);

    /** The frozen "nobody holds this seat" shape — four explicit nulls, {@code mine: false}. */
    public static SeatHolderResponse free() {
        return FREE;
    }
}
