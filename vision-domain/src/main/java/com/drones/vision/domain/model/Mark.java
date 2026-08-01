package com.drones.vision.domain.model;

import java.time.Instant;

/**
 * A geolocated tactical mark on the shared operational picture (docs/TACTICAL-MARKS-PLAN.md §1) —
 * structurally, a point version of a {@link GeofenceZone}: one {@link GeoPosition} plus a tactical
 * {@link #kind()}, a short {@link #label()}, an optional {@link #note()}, owned by the creator's
 * group ({@link #ownership()}), with a lifecycle {@link #status()} and a {@link #source()}
 * describing how it was created.
 *
 * <p>Unlike {@link GeofenceZone} (global reference data with no owner), a mark is owned and
 * group-scoped — {@link #ownership()} mirrors how {@link Asset} carries {@link Ownership}, and
 * scope filtering over marks is the application layer's job (docs/TACTICAL-MARKS-PLAN.md §2), not
 * this record's.
 *
 * <p>A {@link MarkSource#DETECTION} mark is an <b>honest estimate</b> — projected from a drone's
 * pose via {@link GeoProjection#project}, with no gimbal/camera-intrinsics data — so every mark
 * stays editable ({@link #withDetails}) to let the operator correct it on the map.
 *
 * @param id         typed mark identity
 * @param position   where the mark is; altitude is typically {@code null} (ground point unknown)
 * @param kind        the tactical category — an icon/colour category, not a breach semantic
 * @param label       short human-readable label; must not be blank
 * @param note        optional free-text detail; blank normalizes to {@code null}
 * @param ownership   who created this mark and which group it belongs to
 * @param createdAt   when this mark was created; never changes afterward
 * @param status      whether this mark is still current
 * @param source      how this mark came to exist
 */
public record Mark(MarkId id, GeoPosition position, MarkKind kind, String label, String note,
                    Ownership ownership, Instant createdAt, MarkStatus status, MarkSource source) {

    public Mark {
        if (id == null) {
            throw new IllegalArgumentException("Mark id must not be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("Mark position must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Mark kind must not be null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Mark label must not be blank");
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
        if (ownership == null) {
            throw new IllegalArgumentException("Mark ownership must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Mark createdAt must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("Mark status must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("Mark source must not be null");
        }
    }

    /**
     * The user who created this mark, derived from {@link #ownership()}.
     *
     * <p>Used both for display and to decide "creator can clear" (docs/TACTICAL-MARKS-PLAN.md
     * §2/Roles) — kept as a derived accessor rather than a separate component so identity never
     * drifts from ownership.
     *
     * @return the owning user's id
     */
    public UserId createdBy() {
        return ownership.ownerId();
    }

    /**
     * Returns a copy of this mark with edited descriptive fields.
     *
     * <p>Identity, ownership, creation time and status are deliberately not editable here —
     * clearing/reopening a mark is its own act ({@link #withStatus(MarkStatus)}), independent of
     * annotating it or dragging it to a corrected position, mirroring how {@link
     * GeofenceZone#withDetails} leaves the enabled flag untouched.
     *
     * @param kind     the replacement kind
     * @param label    the replacement label; must not be blank
     * @param note     the replacement note; blank normalizes to {@code null}
     * @param position the replacement position
     * @return a new {@code Mark} with those fields replaced
     */
    public Mark withDetails(MarkKind kind, String label, String note, GeoPosition position) {
        return new Mark(id, position, kind, label, note, ownership, createdAt, status, source);
    }

    /**
     * Returns a copy of this mark with a different lifecycle status (ACTIVE &harr; CLEARED).
     *
     * @param status the replacement status
     * @return a new {@code Mark} with {@code status} replaced
     */
    public Mark withStatus(MarkStatus status) {
        return new Mark(id, position, kind, label, note, ownership, createdAt, status, source);
    }
}
