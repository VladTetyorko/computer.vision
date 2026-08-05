package com.drones.vision.domain.model;

import java.time.Instant;

/**
 * The verification state of a {@link Mark} (docs/MAP-REWORK-PLAN.md §2.1) — DELTA's
 * verify&rarr;confirm&rarr;share-wider flow: anyone in scope contributes an unverified mark on a
 * team layer, a manager reviews it, and a {@code CONFIRMED} mark is a candidate for promotion to
 * the shared COP layer.
 *
 * @param state      whether this mark has been reviewed, and how
 * @param verifiedBy who reviewed it; {@code null} while {@link VerificationState#UNVERIFIED},
 *                   required once {@link VerificationState#CONFIRMED}/{@link
 *                   VerificationState#REJECTED}
 * @param verifiedAt when it was reviewed; same nullability as {@link #verifiedBy()}
 */
public record Verification(VerificationState state, UserId verifiedBy, Instant verifiedAt) {

    public Verification {
        if (state == null) {
            throw new IllegalArgumentException("Verification state must not be null");
        }
        if (state == VerificationState.CONFIRMED || state == VerificationState.REJECTED) {
            if (verifiedBy == null) {
                throw new IllegalArgumentException(
                        "Verification verifiedBy must not be null when state is " + state);
            }
            if (verifiedAt == null) {
                throw new IllegalArgumentException(
                        "Verification verifiedAt must not be null when state is " + state);
            }
        }
    }

    /**
     * The default verification state a freshly created mark starts in — reviewed by no one, yet.
     *
     * @return an {@code UNVERIFIED} verification with no reviewer/time
     */
    public static Verification unverified() {
        return new Verification(VerificationState.UNVERIFIED, null, null);
    }

    /** How far along DELTA's verify&rarr;confirm&rarr;share-wider flow a mark is. */
    public enum VerificationState {
        /** Contributed but not yet reviewed by anyone with MANAGE access to its layer. */
        UNVERIFIED,
        /** Reviewed and accepted as accurate — a candidate for promotion to the COP layer. */
        CONFIRMED,
        /** Reviewed and rejected as inaccurate/duplicate. */
        REJECTED
    }
}
