package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import java.time.Instant;

/**
 * A geolocated tactical mark on the shared operational picture (docs/plans/done/MAP-REWORK-PLAN.md §2.2,
 * superseding docs/plans/done/TACTICAL-MARKS-PLAN.md §1) — structurally, a point version of a {@link
 * GeofenceZone}: one {@link GeoPosition} plus a tactical {@link #kind()}, a short {@link
 * #label()}, an optional {@link #note()}, owned by the creator's group ({@link #ownership()}),
 * with a lifecycle {@link #status()} and a {@link #source()} describing how it was created.
 *
 * <p>Three additions from the original shape make this a COP object, not just a pin: {@link
 * #layerId()} (which {@link MapLayer} it lives on — visibility/write access is resolved from that,
 * not from {@link #ownership()} alone), {@link #affiliation()} (APP-6-inspired friend/enemy
 * symbology, orthogonal to {@link #kind()}), and {@link #verification()} (DELTA's
 * verify&rarr;confirm&rarr;share-wider review state).
 *
 * <p>Unlike {@link GeofenceZone} (global reference data with no owner), a mark is owned and
 * group-scoped — {@link #ownership()} mirrors how {@link Asset} carries {@link Ownership}, and
 * scope filtering over marks is the application layer's job ({@code MapAccessPolicy}), not this
 * record's.
 *
 * <p>A {@link MarkSource#DETECTION} mark is an <b>honest estimate</b> — projected from a drone's
 * pose via {@link GeoProjection#project}, with no gimbal/camera-intrinsics data — so every mark
 * stays editable ({@link #withDetails}) to let the operator correct it on the map.
 *
 * @param id           typed mark identity
 * @param layerId      the layer this mark lives on
 * @param position     where the mark is; altitude is typically {@code null} (ground point unknown)
 * @param kind          the tactical category — an icon/colour category, not a breach semantic
 * @param affiliation   friend/enemy affiliation for symbology
 * @param label         short human-readable label; must not be blank
 * @param note          optional free-text detail; blank normalizes to {@code null}
 * @param ownership     who created this mark and which group it belongs to
 * @param createdAt     when this mark was created; never changes afterward
 * @param status        whether this mark is still current
 * @param source        how this mark came to exist
 * @param verification  DELTA-style review state; see {@link Verification}
 */
public record Mark(MarkId id, LayerId layerId, GeoPosition position, MarkKind kind,
                    Affiliation affiliation, String label, String note, Ownership ownership,
                    Instant createdAt, MarkStatus status, MarkSource source, Verification verification) {

    public Mark {
        if (id == null) {
            throw new IllegalArgumentException("Mark id must not be null");
        }
        if (layerId == null) {
            throw new IllegalArgumentException("Mark layerId must not be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("Mark position must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Mark kind must not be null");
        }
        if (affiliation == null) {
            throw new IllegalArgumentException("Mark affiliation must not be null");
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
        if (verification == null) {
            throw new IllegalArgumentException("Mark verification must not be null");
        }
    }

    /**
     * The user who created this mark, derived from {@link #ownership()}.
     *
     * <p>Used both for display and to decide "creator can edit" (docs/plans/done/MAP-REWORK-PLAN.md §3) —
     * kept as a derived accessor rather than a separate component so identity never drifts from
     * ownership.
     *
     * @return the owning user's id
     */
    public UserId createdBy() {
        return ownership.ownerId();
    }

    /**
     * Returns a copy of this mark at a different position — a drag-correct, independent of
     * annotating it ({@link #withDetails}) or changing its lifecycle/review state.
     *
     * @param position the replacement position
     * @return a new {@code Mark} with {@code position} replaced
     */
    public Mark withPosition(GeoPosition position) {
        return new Mark(id, layerId, position, kind, affiliation, label, note, ownership, createdAt,
                status, source, verification);
    }

    /**
     * Returns a copy of this mark with edited descriptive fields.
     *
     * <p>Identity, layer, position, ownership, creation time, status and verification are
     * deliberately not editable here — moving a mark is its own act ({@link
     * #withPosition(GeoPosition)}), independent of annotating it, mirroring how {@link
     * GeofenceZone#withDetails} leaves the enabled flag untouched.
     *
     * @param label       the replacement label; must not be blank
     * @param note        the replacement note; blank normalizes to {@code null}
     * @param kind        the replacement kind
     * @param affiliation the replacement affiliation
     * @return a new {@code Mark} with those fields replaced
     */
    public Mark withDetails(String label, String note, MarkKind kind, Affiliation affiliation) {
        return new Mark(id, layerId, position, kind, affiliation, label, note, ownership, createdAt,
                status, source, verification);
    }

    /**
     * Returns a copy of this mark with a different lifecycle status (ACTIVE &harr; CLEARED).
     *
     * @param status the replacement status
     * @return a new {@code Mark} with {@code status} replaced
     */
    public Mark withStatus(MarkStatus status) {
        return new Mark(id, layerId, position, kind, affiliation, label, note, ownership, createdAt,
                status, source, verification);
    }

    /**
     * Returns a copy of this mark with a different review state (DELTA's verify&rarr;confirm
     * flow — see {@link Verification}).
     *
     * @param verification the replacement verification state
     * @return a new {@code Mark} with {@code verification} replaced
     */
    public Mark withVerification(Verification verification) {
        return new Mark(id, layerId, position, kind, affiliation, label, note, ownership, createdAt,
                status, source, verification);
    }

    /**
     * Returns a copy of this mark moved to a different layer — the effect of a promotion (e.g. to
     * the COP layer) or a plain re-file, everything else preserved.
     *
     * @param layerId the replacement layer id
     * @return a new {@code Mark} with {@code layerId} replaced
     */
    public Mark withLayer(LayerId layerId) {
        return new Mark(id, layerId, position, kind, affiliation, label, note, ownership, createdAt,
                status, source, verification);
    }
}
