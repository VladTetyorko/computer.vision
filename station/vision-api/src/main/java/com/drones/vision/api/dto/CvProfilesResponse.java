package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/cv/profiles} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's
 * frozen wire contract) — mirrors {@link CvModelsResponse}'s wrapped-list shape.
 *
 * @param profiles every profile {@link com.drones.vision.perception.application.profile.CvProfileService#list}
 *                 returns for the caller's scope, built-in profiles always included
 */
public record CvProfilesResponse(List<CvProfileResponse> profiles) {

    public CvProfilesResponse {
        profiles = List.copyOf(profiles);
    }
}
