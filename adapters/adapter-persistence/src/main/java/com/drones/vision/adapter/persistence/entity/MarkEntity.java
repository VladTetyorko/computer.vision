package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.Affiliation;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Verification.VerificationState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code marks} — mirrors {@link com.drones.vision.domain.model.Mark} field-for-field
 * (docs/TACTICAL-MARKS-PLAN.md §3, reworked by docs/MAP-REWORK-PLAN.md §4.4 / {@code
 * V12__map_layers.sql}); {@code MarkMapper} owns the mapping in both directions.
 *
 * <p><strong>The V12 rework adds five columns.</strong> {@code layer_id} (which layer the mark lives
 * on — no FK, see below), {@code affiliation} (the domain {@link Affiliation}: "whose it is", split
 * out of what {@code kind} used to conflate), and the three-column {@code Verification} value
 * flattened the same way {@code ownership} already is: {@code verification_state} plus a nullable
 * {@code verified_by}/{@code verified_at} pair, which are non-null exactly when the state is {@code
 * CONFIRMED}/{@code REJECTED} — an invariant the domain record enforces on read-back, not the schema.
 *
 * <p>Unlike {@link GeofenceZoneEntity#polygon} (a jsonb {@code List<GeoPosition>}), a mark's
 * {@code position} is a single point, so it is flattened to plain {@code latitude}/{@code
 * longitude}/{@code altitude_meters} columns — same "flatten a small value type into columns"
 * choice {@link AssetUsageEntity} makes for {@code GeoPosition} (rather than {@link
 * GeofenceZoneEntity}'s jsonb-column choice for a whole polygon). {@code ownership} is flattened
 * to {@code owner_id}/{@code group_id}, the same choice {@link AssetEntity} makes for {@link
 * com.drones.vision.domain.model.Ownership}.
 *
 * <p>{@code kind}/{@code status}/{@code source} reuse the domain {@link MarkKind}/{@link
 * MarkStatus}/{@link MarkSource} enums directly in {@code @Enumerated(EnumType.STRING)} fields —
 * the same "domain enums reused directly in entity fields" convention {@link GeofenceZoneEntity#kind}
 * already follows.
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of this
 * schema (see MODULE.md's Conventions); {@code owner_id}/{@code group_id}/{@code layer_id} are plain
 * UUID columns with no referential check, matching the in-memory reference repository's own lack of
 * one. {@code layer_id} is indexed, since "every mark on the layers I can see" is the access path.
 */
@Entity
@Table(name = "marks")
public class MarkEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "layer_id", nullable = false)
    private UUID layerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private MarkKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "affiliation", nullable = false, length = 16)
    private Affiliation affiliation;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "note")
    private String note;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MarkStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private MarkSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 16)
    private VerificationState verificationState;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** JPA only. */
    protected MarkEntity() {
    }

    public MarkEntity(UUID id, UUID layerId, MarkKind kind, Affiliation affiliation, String label, String note,
                       double latitude, double longitude, Double altitudeMeters, UUID ownerId, UUID groupId,
                       Instant createdAt, MarkStatus status, MarkSource source,
                       VerificationState verificationState, UUID verifiedBy, Instant verifiedAt) {
        this.id = id;
        this.layerId = layerId;
        this.kind = kind;
        this.affiliation = affiliation;
        this.label = label;
        this.note = note;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.createdAt = createdAt;
        this.status = status;
        this.source = source;
        this.verificationState = verificationState;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = verifiedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID layerId() {
        return layerId;
    }

    public MarkKind kind() {
        return kind;
    }

    public Affiliation affiliation() {
        return affiliation;
    }

    public String label() {
        return label;
    }

    public String note() {
        return note;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public Double altitudeMeters() {
        return altitudeMeters;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID groupId() {
        return groupId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public MarkStatus status() {
        return status;
    }

    public MarkSource source() {
        return source;
    }

    public VerificationState verificationState() {
        return verificationState;
    }

    public UUID verifiedBy() {
        return verifiedBy;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }
}
