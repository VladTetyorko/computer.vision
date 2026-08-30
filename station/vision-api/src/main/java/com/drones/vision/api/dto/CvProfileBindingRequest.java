package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfileId;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Body for {@code PUT /api/cv/bindings} (set/replace) and {@code DELETE /api/cv/bindings} (clear)
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's frozen wire contract) — one shared shape for both
 * verbs, per {@code models.ts}'s own comment on {@code CvProfileBindingRequest}. {@code profileId}
 * is required on {@code PUT} ({@link #requireProfileId()} &rarr; 400 if absent) and ignored on
 * {@code DELETE} (clearing a scope doesn't name which profile it was bound to).
 *
 * <p>{@code DELETE} accepting a body is unusual REST, but this is what the frozen wire contract
 * states (there is no single resource id to put in the path — a binding's natural key is the
 * {@code (scopeKind, scopeId)} pair) and Spring MVC supports {@code @RequestBody} on {@code
 * @DeleteMapping} without any special handling.
 *
 * @param scopeKind which kind of scope {@code scopeId} identifies — {@code "ORGANIZATION"}/{@code
 *                  "CATEGORY"}/{@code "ASSET"}, matched case-insensitively
 * @param scopeId   the scope's id: a UUID string for {@code ASSET}/{@code ORGANIZATION}, a
 *                  kebab-case slug for {@code CATEGORY}; format is validated downstream by {@link
 *                  com.drones.vision.perception.application.profile.CvProfileService#bind}/{@link
 *                  com.drones.vision.perception.application.profile.CvProfileService#unbind}
 *                  themselves, not here
 * @param profileId the profile to bind; required on {@code PUT}, ignored on {@code DELETE}
 */
public record CvProfileBindingRequest(String scopeKind, String scopeId, String profileId) {

    /**
     * Case-insensitive {@link BindingScope} lookup, listing the valid values on failure — the same
     * idiom {@link TrackingConfigRequest#toPatch()}'s own {@code parseMode} uses.
     *
     * @return the parsed scope kind
     * @throws IllegalArgumentException if {@code scopeKind} is not a known scope (&rarr; 400)
     */
    public BindingScope toScopeKind() {
        if (scopeKind == null) {
            throw new IllegalArgumentException("scopeKind must not be null");
        }
        return Arrays.stream(BindingScope.values())
                .filter(candidate -> candidate.name().equalsIgnoreCase(scopeKind.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown binding scope: " + scopeKind
                        + ". Valid values: "
                        + Arrays.stream(BindingScope.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }

    /**
     * Parses {@link #profileId()} as a {@link CvProfileId}, required for {@code PUT}.
     *
     * @return the parsed profile id
     * @throws IllegalArgumentException if {@link #profileId()} is blank or not a valid UUID
     *                                  (&rarr; 400)
     */
    public CvProfileId requireProfileId() {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId is required to bind a CV profile");
        }
        return CvProfileId.of(profileId);
    }
}
