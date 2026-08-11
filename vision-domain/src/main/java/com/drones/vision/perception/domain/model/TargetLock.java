package com.drones.vision.perception.domain.model;

/**
 * Which object {@code FOLLOW} mode should hold (docs/plans/done/TRACKING-PLAN.md §4.A) — one of three
 * mutually exclusive forms: lock onto an existing track by id, lock onto a clicked point, or
 * release the current lock.
 *
 * <p>Restated on every frame; cv-service applies it only when {@code lockSeq} is strictly greater
 * than the last one it applied for the stream, making restatement idempotent by construction — a
 * dropped frame or a reconnect cannot desynchronize the lock (docs/plans/done/TRACKING-PLAN.md invariant
 * P2). {@code lockSeq} is allocated by the application layer's own monotonic counter, never by a
 * client; this record only rejects a negative one, it does not itself enforce monotonicity across
 * instances.
 *
 * @param lockSeq monotonic per stream; {@code 0} = no lock has ever been issued
 * @param trackId lock onto an existing track — the track-id form, mutually exclusive with {@code
 *                pointX}/{@code pointY} and {@code release}
 * @param pointX  normalized [0,1] click point x — the point form, requires {@code pointY} too,
 *                mutually exclusive with {@code trackId} and {@code release}
 * @param pointY  normalized [0,1] click point y — requires {@code pointX} too
 * @param release {@code true} = drop the current lock — the release form, mutually exclusive with
 *                {@code trackId} and {@code pointX}/{@code pointY}
 */
public record TargetLock(long lockSeq, Long trackId, Double pointX, Double pointY, boolean release) {

    public TargetLock {
        if (lockSeq < 0) {
            throw new IllegalArgumentException("TargetLock lockSeq must not be negative: " + lockSeq);
        }
        if ((pointX == null) != (pointY == null)) {
            throw new IllegalArgumentException("TargetLock pointX and pointY must both be set or both be null");
        }
        if (pointX != null) {
            requireUnitRange(pointX, "pointX");
            requireUnitRange(pointY, "pointY");
        }
        int forms = (trackId != null ? 1 : 0) + (pointX != null ? 1 : 0) + (release ? 1 : 0);
        if (forms != 1) {
            throw new IllegalArgumentException(
                    "TargetLock accepts exactly one of trackId/point/release, got " + forms + " form(s)");
        }
    }

    private static void requireUnitRange(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("TargetLock " + name + " must be within [0,1]: " + value);
        }
    }
}
