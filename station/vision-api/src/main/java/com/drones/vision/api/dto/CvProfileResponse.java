package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link CvProfile} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1's frozen
 * JSON) — body of {@code GET}/{@code POST}/{@code PUT /api/cv/profiles}[/{id}], and nested in
 * {@link EffectiveCvProfileResponse}.
 *
 * <p>{@code groupId} is omitted (not {@code null}) when this profile is built-in, matching {@code
 * models.ts}'s optional {@code groupId?: string} — {@link CvProfile}'s own compact constructor
 * guarantees {@code builtIn <=> groupId == null}.
 *
 * @param id                  profile identity, as a canonical UUID string
 * @param name                human-readable name
 * @param description         human-readable description
 * @param builtIn             {@code true} for one of the four seeded, non-editable templates
 * @param groupId             the owning group, as a canonical UUID string; omitted iff {@code builtIn}
 * @param model               the CV model id this profile runs — plain string, mirroring {@code
 *                            StartStreamRequest}/{@code PromoteModelRequest}'s own model fields
 * @param confidenceThreshold minimum confidence to keep a detection, [0,1]
 * @param inferenceFps        target inference sample rate
 * @param labelFilter         labels to keep; empty means all labels
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them
 * @param detectionEnabled    whether a stream started from this profile runs detection at all
 * @param tracking            tracking configuration a stream started from this profile uses
 * @param eventRule           debounce rule a stream started from this profile uses; start-time only
 * @param createdAt           when this profile was created
 * @param updatedAt           when this profile was last edited
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvProfileResponse(String id, String name, String description, boolean builtIn, String groupId,
                                 String model, double confidenceThreshold, int inferenceFps,
                                 List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled,
                                 CvProfileTrackingResponse tracking, CvProfileEventRuleResponse eventRule,
                                 Instant createdAt, Instant updatedAt) {

    /**
     * Sentinel id for the synthesized "no binding matched" row {@link #platformDefault} returns —
     * the nil UUID, never a real {@link CvProfile#id()} (profile ids are {@link
     * com.drones.vision.perception.domain.model.CvProfileId#random()}, which never produces the nil
     * UUID in practice, but this sentinel is chosen for unmistakability, not statistical rarity).
     */
    public static final String PLATFORM_DEFAULT_ID = "00000000-0000-0000-0000-000000000000";

    /** Display name for the synthesized "no binding matched" row {@link #platformDefault} returns. */
    public static final String PLATFORM_DEFAULT_NAME = "Platform default";

    /**
     * Maps a persisted profile to the wire, field for field.
     *
     * @param profile the profile to map
     * @return the response body for {@code profile}
     */
    public static CvProfileResponse from(CvProfile profile) {
        return new CvProfileResponse(profile.id().value().toString(), profile.name(), profile.description(),
                profile.builtIn(), profile.groupId() == null ? null : profile.groupId().value().toString(),
                profile.model().id(), profile.confidenceThreshold(), profile.inferenceFps(),
                List.copyOf(profile.labelFilter()), List.copyOf(profile.labelDenyFilter()),
                profile.detectionEnabled(), CvProfileTrackingResponse.from(profile.tracking()),
                CvProfileEventRuleResponse.from(profile.eventRule()), profile.createdAt(), profile.updatedAt());
    }

    /**
     * Synthesizes a profile-shaped response for {@link com.drones.vision.perception.application.profile.ProfileSource#PLATFORM}
     * — "no binding matched at any level, the deployment's own default applies unchanged"
     * (docs/plans/active/CV-SETTINGS-CONTEXT.md's W2 &rarr; W5 handoff, deviation 2: the platform default
     * is assembled server-side, never invented by {@code vision-perception} itself, and never a real
     * persisted {@link CvProfile} — there is nothing to fetch by id for this case). {@link #id()}/
     * {@link #name()} are the fixed {@link #PLATFORM_DEFAULT_ID}/{@link #PLATFORM_DEFAULT_NAME}
     * sentinels; {@link #createdAt()}/{@link #updatedAt()} are {@link Instant#EPOCH} (this "profile"
     * was never actually created).
     *
     * @param config the platform's default {@link PipelineConfig} — the same value {@code
     *               DefaultStreamService#start} would fall back to
     * @return a synthesized, non-persisted response describing {@code config} as a profile
     */
    public static CvProfileResponse platformDefault(PipelineConfig config) {
        return new CvProfileResponse(PLATFORM_DEFAULT_ID, PLATFORM_DEFAULT_NAME,
                "No binding matched; the deployment's own default configuration applies unchanged.", true, null,
                config.model().id(), config.confidenceThreshold(), config.inferenceFps(),
                List.copyOf(config.labelFilter()), List.copyOf(config.labelDenyFilter()), config.detectionEnabled(),
                CvProfileTrackingResponse.from(config.tracking()), CvProfileEventRuleResponse.from(config.eventRule()),
                Instant.EPOCH, Instant.EPOCH);
    }
}
