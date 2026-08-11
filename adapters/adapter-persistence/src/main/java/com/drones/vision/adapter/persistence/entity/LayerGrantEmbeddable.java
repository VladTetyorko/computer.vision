package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.AccessLevel;
import com.drones.vision.domain.model.LayerGrant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of the {@code map_layer_grants} element-collection table (docs/plans/done/MAP-REWORK-PLAN.md §4.4).
 *
 * <p><strong>Why an adapter-local class instead of reusing the domain {@link LayerGrant}
 * record.</strong> Every other collection in this module stores its domain record tree directly (as
 * jsonb, via Hibernate's Jackson-backed {@code FormatMapper}). Grants are a real table instead —
 * they are the one collection whose individual rows are a security decision, worth having queryable
 * and auditable in SQL — and a JPA {@code @Embeddable} must be a mutable class with a no-arg
 * constructor, which a record cannot satisfy (JPA 3.2 §2.5). So this is the one place a persistence-
 * local mirror type is unavoidable; it is a straight 1:1 of {@link LayerGrant}'s three components,
 * converted in {@code MapLayerMapper}.
 *
 * <p>{@code equals}/{@code hashCode} are defined because Hibernate needs them for element-collection
 * change detection (which rows to delete and re-insert on a merge) — the compiler would not have
 * complained, but the collection would have been rewritten wholesale on every save.
 */
@Embeddable
public class LayerGrantEmbeddable {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private LayerGrant.SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private AccessLevel level;

    protected LayerGrantEmbeddable() {
    }

    public LayerGrantEmbeddable(LayerGrant.SubjectType subjectType, UUID subjectId, AccessLevel level) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.level = level;
    }

    public LayerGrant.SubjectType subjectType() {
        return subjectType;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public AccessLevel level() {
        return level;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LayerGrantEmbeddable that)) {
            return false;
        }
        return subjectType == that.subjectType && Objects.equals(subjectId, that.subjectId)
                && level == that.level;
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectType, subjectId, level);
    }
}
