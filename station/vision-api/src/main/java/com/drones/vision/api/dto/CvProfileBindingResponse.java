package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.CvProfileBinding;

import java.time.Instant;

/**
 * Wire representation of a {@link CvProfileBinding} — the {@code 200} body {@code PUT
 * /api/cv/bindings} returns (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's frozen wire contract).
 * There is no {@code GET /api/cv/bindings} to list raw rows — {@code GET /api/cv/coverage} is the
 * read side (see {@link CvCoverageRowResponse}'s own javadoc).
 *
 * @param scopeKind {@code "ORGANIZATION"}/{@code "CATEGORY"}/{@code "ASSET"} — {@link
 *                  com.drones.vision.perception.domain.model.BindingScope#name()} verbatim
 * @param scopeId   the scope's id, as given on the request — a UUID for {@code ORGANIZATION}/
 *                  {@code ASSET}, a kebab-case slug for {@code CATEGORY}
 * @param profileId the bound profile's id, as a canonical UUID string
 * @param createdAt when this binding was made
 */
public record CvProfileBindingResponse(String scopeKind, String scopeId, String profileId, Instant createdAt) {

    /**
     * Maps a persisted binding to the wire, field for field.
     *
     * @param binding the binding to map
     * @return the response body for {@code binding}
     */
    public static CvProfileBindingResponse from(CvProfileBinding binding) {
        return new CvProfileBindingResponse(binding.scopeKind().name(), binding.scopeId(),
                binding.profileId().value().toString(), binding.createdAt());
    }
}
