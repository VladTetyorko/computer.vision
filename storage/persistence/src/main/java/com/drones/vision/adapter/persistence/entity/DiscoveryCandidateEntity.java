package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.warehouse.domain.model.CandidateStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JPA row for {@code discovery_candidates} — mirrors {@link
 * com.drones.vision.warehouse.domain.model.DiscoveryCandidate} field-for-field, with its nested
 * {@link com.drones.vision.warehouse.domain.model.DiscoveredDevice} flattened onto this same row
 * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11, {@code V31__discovery_inbox.sql}). {@link
 * com.drones.vision.adapter.persistence.mapper.DiscoveryCandidateMapper} owns the mapping both ways.
 *
 * <p>Mutable, hand-registered in {@link com.drones.vision.adapter.persistence.config.PersistenceUnit}
 * — no package scanning, same convention every entity in this module follows.
 *
 * <p>{@code suggestedStreamProtocol}/{@code suggestedStreamUri}/{@code suggestedStreamOptions}
 * mirror {@code devices.stream_protocol}/{@code stream_uri}/{@code stream_options}
 * ({@code V1__baseline.sql}) field-for-field, except all three are nullable together — a candidate
 * whose discovery mechanism could not produce a ready-to-use stream (e.g. ONVIF before {@code
 * GetStreamUri}) has {@code DiscoveredDevice#suggestedStream() == null}, and this entity's compact
 * three-column group round-trips that {@code null} exactly (see the migration's own header). {@code
 * details} is always a real, possibly-empty jsonb object — {@code DiscoveredDevice#details()}'s own
 * compact constructor never lets it be {@code null}.
 */
@Entity
@Table(name = "discovery_candidates")
public class DiscoveryCandidateEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "identity_key", length = 1024, nullable = false)
    private String identityKey;

    @Column(name = "method", length = 64, nullable = false)
    private String method;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address", length = 2048, nullable = false)
    private String address;

    @Column(name = "suggested_category", length = 64)
    private String suggestedCategory;

    @Column(name = "suggested_stream_protocol", length = 64)
    private String suggestedStreamProtocol;

    @Column(name = "suggested_stream_uri", length = 2048)
    private String suggestedStreamUri;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_stream_options", columnDefinition = "jsonb")
    private Map<String, String> suggestedStreamOptions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> details = new LinkedHashMap<>();

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CandidateStatus status;

    @Column(name = "registered_asset_id")
    private UUID registeredAssetId;

    /** JPA only. */
    protected DiscoveryCandidateEntity() {
    }

    public DiscoveryCandidateEntity(UUID id, String identityKey, String method, String name, String address,
                                     String suggestedCategory, String suggestedStreamProtocol,
                                     String suggestedStreamUri, Map<String, String> suggestedStreamOptions,
                                     Map<String, String> details, Instant firstSeen, Instant lastSeen,
                                     CandidateStatus status, UUID registeredAssetId) {
        this.id = id;
        this.identityKey = identityKey;
        this.method = method;
        this.name = name;
        this.address = address;
        this.suggestedCategory = suggestedCategory;
        this.suggestedStreamProtocol = suggestedStreamProtocol;
        this.suggestedStreamUri = suggestedStreamUri;
        this.suggestedStreamOptions = suggestedStreamOptions == null ? null : new LinkedHashMap<>(suggestedStreamOptions);
        this.details = new LinkedHashMap<>(details);
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.status = status;
        this.registeredAssetId = registeredAssetId;
    }

    public UUID id() {
        return id;
    }

    public String identityKey() {
        return identityKey;
    }

    public String method() {
        return method;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public String suggestedCategory() {
        return suggestedCategory;
    }

    public String suggestedStreamProtocol() {
        return suggestedStreamProtocol;
    }

    public String suggestedStreamUri() {
        return suggestedStreamUri;
    }

    public Map<String, String> suggestedStreamOptions() {
        return suggestedStreamOptions;
    }

    public Map<String, String> details() {
        return details;
    }

    public Instant firstSeen() {
        return firstSeen;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public CandidateStatus status() {
        return status;
    }

    public UUID registeredAssetId() {
        return registeredAssetId;
    }
}
