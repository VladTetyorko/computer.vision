package com.drones.vision.identity.domain.model;

/**
 * The seat a pilot&rarr;asset assignment grants (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B1)
 * — the answer to CREW-CONTROL-PLAN.md's IC-2 ("may this person fly, or only run the camera, on
 * this one aircraft"). Deliberately <strong>not</strong> a {@link Role}: a role is global, a seat is
 * per-aircraft, and the same person routinely holds {@link #PILOT} on one asset and {@link #CREW} on
 * another — a global enum could never express that even before the reordering concerns {@link Role}
 * carries. No ordinal semantics: unlike {@link Role}, declaration order here means nothing and must
 * never be compared with {@link Enum#compareTo(Enum)}.
 *
 * <p>Held on {@code com.drones.vision.identity.domain.port.AssignmentRepositoryPort}'s assignment
 * link, one seat per (pilot, asset) pair — not a set of seats, since a single link either grants the
 * wider seat ({@link #PILOT}, which subsumes the camera) or the narrower one ({@link #CREW}).
 */
public enum AssignmentRole {

    /** May hold the flight seat and the camera seat on this asset — the full, pre-existing assignment
     * shape every row defaulted to before this type existed. */
    PILOT,

    /** May hold only the camera seat on this asset — payload/CV/tracking/marks, never the stick. */
    CREW
}
