package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.Membership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code users} — mirrors {@link com.drones.vision.domain.model.User} field-for-field
 * (docs/plans/done/U-AUTH-PLAN.md, wave 3); {@link com.drones.vision.adapter.persistence.JpaUserRepository}
 * owns the mapping in both directions.
 *
 * <p>{@code memberships} stores the whole {@code List<Membership>} as one jsonb column via
 * Hibernate's native JSON support — same mechanism/rationale as {@code
 * DetectionResultEntity#detections}/{@code GeofenceZoneEntity#polygon}: {@link Membership} (with
 * its nested {@code GroupId}/{@code Role}) is a plain immutable record Jackson serializes natively,
 * and a user's memberships are only ever read back whole with the aggregate, so a normalized join
 * table would add schema without adding any query the ports need. No FK to {@code groups} for the
 * same reason every other table here omits cross-entity FKs (see MODULE.md's Conventions) — the
 * in-memory reference repositories perform no referential checks either.
 *
 * <p>{@code username} is stored already-lower-cased (the domain {@code User} normalizes it) and
 * carries a {@code UNIQUE} constraint, so {@code findByUsername} is an exact match on the stored
 * value.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memberships", columnDefinition = "jsonb", nullable = false)
    private List<Membership> memberships = new ArrayList<>();

    /** JPA only. */
    protected UserEntity() {
    }

    public UserEntity(UUID id, String username, String displayName, String email, String passwordHash,
                      boolean enabled, List<Membership> memberships) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.memberships = new ArrayList<>(memberships);
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Membership> memberships() {
        return memberships;
    }
}
