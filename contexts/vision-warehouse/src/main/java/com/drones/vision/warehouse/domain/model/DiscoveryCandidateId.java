package com.drones.vision.warehouse.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link DiscoveryCandidate}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so
 * candidate identities cannot be mixed up with asset, device, or other typed identifiers at
 * compile time, and can never hold a malformed value — following the same pattern as {@code
 * com.drones.vision.kernel.AssetId} and this module's own {@link MaintenanceId}/{@link NoteId}.
 * Warehouse-local: a discovery candidate is upstream of any other context's concerns (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 Z2a), nothing outside this module needs to name one.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record DiscoveryCandidateId(UUID value) {

    public DiscoveryCandidateId {
        if (value == null) {
            throw new IllegalArgumentException("DiscoveryCandidateId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code DiscoveryCandidateId} using a random UUID.
     *
     * @return a fresh {@code DiscoveryCandidateId}
     */
    public static DiscoveryCandidateId random() {
        return new DiscoveryCandidateId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code DiscoveryCandidateId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code DiscoveryCandidateId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static DiscoveryCandidateId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DiscoveryCandidateId value must not be null");
        }
        try {
            return new DiscoveryCandidateId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DiscoveryCandidateId value must be a valid UUID: " + value, e);
        }
    }
}
