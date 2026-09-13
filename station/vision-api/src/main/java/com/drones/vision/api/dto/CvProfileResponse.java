package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.ProfileSource;
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
 * <h2>Wave W7.3 — a profile is a patch, sources on every read (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.7/&sect;8, decision E22)</h2>
 * {@code model}/{@code confidenceThreshold}/{@code inferenceFps}/{@code labelFilter}/{@code
 * labelDenyFilter}/{@code detectionEnabled}/{@code tracking}/{@code eventRule} are now all
 * individually nullable, mirroring {@link CvProfile}'s own now-nullable knobs one for one — {@code
 * @JsonInclude(NON_NULL)} omits an unset knob from the wire entirely rather than sending a JSON
 * {@code null}. {@code intent} is new (the persisted pick, or absent for none). {@link #sources()}
 * used to carry only the two knobs a request's own save-time intent resolution could seed, computed
 * once by the request that produced this exact response; it is now widened to four knobs and
 * computed straight from {@code profile} itself by {@link #from(CvProfile)} on every read (GET,
 * list, create, update alike) — see {@link Sources}'s own javadoc.
 *
 * @param id                  profile identity, as a canonical UUID string
 * @param name                human-readable name
 * @param description         human-readable description
 * @param builtIn             {@code true} for one of the four seeded, non-editable templates
 * @param groupId             the owning group, as a canonical UUID string; omitted iff {@code builtIn}
 * @param model               the CV model id this profile runs, or absent if unset (inherit) —
 *                            plain string, mirroring {@code StartStreamRequest}/{@code
 *                            PromoteModelRequest}'s own model fields
 * @param confidenceThreshold minimum confidence to keep a detection, [0,1], or absent if unset
 * @param inferenceFps        target inference sample rate, or absent if unset
 * @param labelFilter         labels to keep; empty means all labels; absent if unset
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them; absent
 *                            if unset
 * @param detectionEnabled    whether a stream started from this profile runs detection at all, or
 *                            absent if unset
 * @param tracking            tracking overrides a stream started from this profile uses, or absent
 *                            if the whole group is unset
 * @param eventRule           debounce rule a stream started from this profile uses, or absent if
 *                            unset; start-time only
 * @param intent              the operator's "what am I looking for" pick, or absent for none
 * @param createdAt           when this profile was created
 * @param updatedAt           when this profile was last edited
 * @param sources             per-knob provenance for this profile's own fields, computed fresh on
 *                            every read (wave W7.3, docs/plans/active/CV-ORCHESTRATION-PLAN.md
 *                            &sect;4.7) — see {@link FieldSources}'s own javadoc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvProfileResponse(String id, String name, String description, boolean builtIn, String groupId,
                                 String model, Double confidenceThreshold, Integer inferenceFps,
                                 List<String> labelFilter, List<String> labelDenyFilter, Boolean detectionEnabled,
                                 CvProfileTrackingResponse tracking, CvProfileEventRuleResponse eventRule,
                                 String intent, Instant createdAt, Instant updatedAt, FieldSources sources) {

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
     * Maps a persisted profile to the wire, together with {@link Sources#of(CvProfile)} — the one
     * canonical factory for every plain read/write (`GET`, `list`, `create`, `update`).
     *
     * @param profile the profile to map
     * @return the response body for {@code profile}, carrying its own computed {@link Sources}
     */
    public static CvProfileResponse from(CvProfile profile) {
        return new CvProfileResponse(profile.id().value().toString(), profile.name(), profile.description(),
                profile.builtIn(), profile.groupId() == null ? null : profile.groupId().value().toString(),
                profile.model() == null ? null : profile.model().id(), profile.confidenceThreshold(),
                profile.inferenceFps(), profile.labelFilter(), profile.labelDenyFilter(),
                profile.detectionEnabled(), CvProfileTrackingResponse.from(profile.tracking()),
                CvProfileEventRuleResponse.from(profile.eventRule()),
                profile.intent() == null ? null : profile.intent().name(), profile.createdAt(), profile.updatedAt(),
                FieldSources.of(profile));
    }

    /**
     * Synthesizes a profile-shaped response for {@link com.drones.vision.perception.application.profile.ProfileSource#PLATFORM}
     * — "no binding matched at any level, the deployment's own default applies unchanged"
     * (docs/plans/active/CV-SETTINGS-CONTEXT.md's W2 &rarr; W5 handoff, deviation 2: the platform default
     * is assembled server-side, never invented by {@code vision-perception} itself, and never a real
     * persisted {@link CvProfile} — there is nothing to fetch by id for this case). {@link #id()}/
     * {@link #name()} are the fixed {@link #PLATFORM_DEFAULT_ID}/{@link #PLATFORM_DEFAULT_NAME}
     * sentinels; {@link #createdAt()}/{@link #updatedAt()} are {@link Instant#EPOCH} (this "profile"
     * was never actually created). Every knob is present (a {@link PipelineConfig} is always fully
     * resolved) and {@link #sources()} is {@link Sources#none()} — there is no real profile to report
     * provenance against.
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
                CvProfileTrackingResponse.fromResolved(config.tracking()),
                CvProfileEventRuleResponse.from(config.eventRule()), null, Instant.EPOCH, Instant.EPOCH,
                FieldSources.none());
    }

    /**
     * Synthesizes a profile-shaped response for a matched, non-platform tier — {@code
     * GET /api/cv/profiles/effective}'s own nested {@code profile} (wave W7.3, docs/plans/active/
     * CV-ORCHESTRATION-PLAN.md &sect;4.7). Identity/bookkeeping fields ({@code id}, {@code name},
     * {@code description}, {@code builtIn}, {@code groupId}, {@code intent}, the timestamps) come
     * from {@code matchedProfile} itself, but every KNOB comes from {@code config} — the fold's own
     * result — rather than {@code matchedProfile}'s own, possibly-partial fields: under wave W7's
     * per-knob inheritance, the matched tier's own profile may leave several knobs unset (inherited
     * from a lower tier), so showing its raw fields here would misreport what actually runs. {@link
     * #sources()} is {@link Sources#none()} on this nested response — the real per-knob provenance
     * for an effective read lives one level up, on {@link EffectiveCvProfileResponse#sources()}
     * (all eight knobs, tier-by-tier), not this four-knob, single-profile view.
     *
     * @param matchedProfile the real, persisted profile the fold matched at its most specific tier
     * @param config         the fold's own resolved {@link PipelineConfig}
     * @return a response describing the EFFECTIVE configuration, carrying {@code matchedProfile}'s
     *         own identity
     */
    public static CvProfileResponse fromEffective(CvProfile matchedProfile, PipelineConfig config) {
        return new CvProfileResponse(matchedProfile.id().value().toString(), matchedProfile.name(),
                matchedProfile.description(), matchedProfile.builtIn(),
                matchedProfile.groupId() == null ? null : matchedProfile.groupId().value().toString(),
                config.model().id(), config.confidenceThreshold(), config.inferenceFps(),
                List.copyOf(config.labelFilter()), List.copyOf(config.labelDenyFilter()), config.detectionEnabled(),
                CvProfileTrackingResponse.fromResolved(config.tracking()),
                CvProfileEventRuleResponse.from(config.eventRule()),
                matchedProfile.intent() == null ? null : matchedProfile.intent().name(), matchedProfile.createdAt(),
                matchedProfile.updatedAt(), FieldSources.none());
    }

    /**
     * Per-knob provenance for {@code model}/{@code confidenceThreshold}/{@code inferenceFps}/{@code
     * labelFilter} — the four knobs {@link com.drones.vision.perception.application.profile.IntentPolicyResolver}
     * can seed — computed directly from one persisted {@link CvProfile}'s own fields (wave W7.3,
     * docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, decision E22), never from a save-time
     * request. {@code @JsonInclude(NON_NULL)} — a field with no provenance to report is a missing
     * key on the wire, never a {@code null}.
     *
     * <p><b>Replaces the pre-W7.3 {@link Sources}</b> as {@link #sources()}'s own type, widening from
     * two knobs to four. Now that {@link CvProfile#intent()} is itself persisted (wave W7.0) rather
     * than resolved-and-discarded at save time, "would this knob be seeded from intent" is answerable
     * from the saved profile alone, on every read — a knob reports {@link ProfileSource#INTENT}
     * exactly when it is unset ({@code null}) AND the profile carries a non-{@code null} {@link
     * CvProfile#intent()}, since that is precisely the condition under which {@code
     * CvProfileResolver}'s fold would seed it from the intent policy rather than leave it inherited
     * from a lower tier.
     *
     * <p><b>Disclosed deviation</b> — this is a NEW, separate type rather than a widened {@link
     * Sources} itself: {@code UpdateStreamConfigRequest} (the stream-config hot path, out of wave
     * W7's scope) directly reuses {@link Sources}'s own original two-field constructor for its own
     * live PATCH-path provenance reporting. Widening {@link Sources} in place would have broken that
     * unrelated, untouched file's compile. {@link Sources} is therefore left byte-identical to its
     * pre-W7.3 shape, and this record carries the four-knob behavior instead.
     *
     * @param model               {@link ProfileSource#INTENT} iff {@code profile.model()} is
     *                            {@code null} and {@code profile.intent()} is not
     * @param confidenceThreshold {@link ProfileSource#INTENT} iff {@code
     *                            profile.confidenceThreshold()} is {@code null} and {@code
     *                            profile.intent()} is not
     * @param inferenceFps        {@link ProfileSource#INTENT} iff {@code profile.inferenceFps()} is
     *                            {@code null} and {@code profile.intent()} is not
     * @param labelFilter         {@link ProfileSource#INTENT} iff {@code profile.labelFilter()} is
     *                            {@code null} and {@code profile.intent()} is not
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldSources(ProfileSource model, ProfileSource confidenceThreshold, ProfileSource inferenceFps,
                                ProfileSource labelFilter) {

        /**
         * Computes this profile's own per-knob provenance — see this record's own javadoc for the
         * exact rule.
         *
         * @param profile the persisted profile to inspect
         * @return the four-knob provenance {@code profile} itself implies
         */
        public static FieldSources of(CvProfile profile) {
            ProfileSource fromIntent = profile.intent() == null ? null : ProfileSource.INTENT;
            return new FieldSources(profile.model() == null ? fromIntent : null,
                    profile.confidenceThreshold() == null ? fromIntent : null,
                    profile.inferenceFps() == null ? fromIntent : null,
                    profile.labelFilter() == null ? fromIntent : null);
        }

        /**
         * The "no provenance to report" value — every field {@code null}, so {@code
         * @JsonInclude(NON_NULL)} still omits the whole {@code sources} group from the wire. Used by
         * {@link #platformDefault} and {@link #fromEffective} — both synthesize a profile-shaped
         * response with no real, single {@link CvProfile} to compute {@link #of(CvProfile)} against.
         *
         * @return a {@link FieldSources} with every field {@code null}
         */
        public static FieldSources none() {
            return new FieldSources(null, null, null, null);
        }
    }

    /**
     * The original, pre-W7.3 two-knob provenance shape — {@code model}/{@code labelFilter} only,
     * the two fields the old wire contract gave an unambiguous "caller left this to the platform"
     * sentinel for (a blank string, an empty list). {@link #sources()} no longer uses this type (see
     * {@link FieldSources}); it is kept here, byte-identical to its pre-W7.3 shape, solely because
     * {@code UpdateStreamConfigRequest} (the stream-config hot path, out of wave W7's scope, not
     * touched by this wave) directly reuses its two-argument constructor for its own live PATCH-path
     * {@code fieldSources()} provenance reporting — deleting or widening it in place would have broken
     * that unrelated, untouched file's compile.
     *
     * @param model       {@link ProfileSource#INTENT} when a PATCH request's own intent seeded
     *                    {@code model}
     * @param labelFilter {@link ProfileSource#INTENT} when a PATCH request's own intent seeded
     *                    {@code labelFilter}
     */
    public record Sources(ProfileSource model, ProfileSource labelFilter) {

        /**
         * The "no provenance to report" value — both fields {@code null}.
         *
         * @return a {@link Sources} with both fields {@code null}
         */
        public static Sources none() {
            return new Sources(null, null);
        }
    }
}
