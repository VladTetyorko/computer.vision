package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.Objects;

/**
 * The one {@link PipelineConfig} one asset resolves to right now, which layer of
 * docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1's asset &rarr; category &rarr; organization &rarr;
 * platform fold supplied each knob, and which {@link Intent} (if any) seeded any of them — both
 * {@link CvProfileResolver}'s return value and the read model behind {@code
 * GET /api/cv/profiles/effective} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2).
 *
 * <p>{@code profileId}/{@code profileName} are {@code null} exactly when {@code source} is {@link
 * ProfileSource#PLATFORM} — no binding matched at any level, so there is no profile to name; every
 * other source names the <em>most specific</em> bound tier, exactly as before wave W7 — the
 * per-knob provenance now lives in {@link #sources()} instead.
 *
 * <p><b>Wave W7.1</b> (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, decision E22): {@code
 * sources}/{@code intent} are new. {@code sources} is a genuine per-knob answer, filled in while
 * {@link CvProfileResolver#resolve} folds each bound tier — a knob reads {@link
 * ProfileSource#PLATFORM} until some tier either sets it explicitly or seeds it via that tier's own
 * {@code intent}. {@code intent} names the <b>most specific</b> bound tier whose own {@code intent}
 * seeded at least one knob (every non-null {@code intent} always seeds all four intent-affected
 * knobs — {@link com.drones.vision.perception.application.profile.IntentPolicyResolver}'s contract
 * never yields an empty policy), or {@code null} if no bound tier set an {@code intent} at all —
 * unlike before this wave, this is the profile's own <em>persisted</em> intent, not one resolved and
 * discarded at request time.
 *
 * @param assetId     the asset this resolution is for
 * @param profileId   the matched profile's id, or {@code null} iff {@code source} is {@link
 *                    ProfileSource#PLATFORM}
 * @param profileName the matched profile's name, or {@code null} iff {@code source} is {@link
 *                    ProfileSource#PLATFORM}
 * @param source      which layer's binding is the most specific match
 * @param config      the resolved {@link PipelineConfig} — every bound tier folded in order over the
 *                    caller's platform default, or that platform default unchanged for {@link
 *                    ProfileSource#PLATFORM}
 * @param sources     per-knob provenance across the whole fold
 * @param intent      the most specific bound tier's own seeding {@link Intent}, or {@code null} if
 *                    no bound tier set one
 */
public record EffectiveProfile(AssetId assetId, CvProfileId profileId, String profileName, ProfileSource source,
                                PipelineConfig config, KnobSources sources, Intent intent) {

    public EffectiveProfile {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        boolean namesAProfile = profileId != null || profileName != null;
        if (source == ProfileSource.PLATFORM && namesAProfile) {
            throw new IllegalArgumentException("EffectiveProfile profileId/profileName must be null for PLATFORM");
        }
        if (source != ProfileSource.PLATFORM && (profileId == null || profileName == null)) {
            throw new IllegalArgumentException(
                    "EffectiveProfile profileId/profileName must not be null when source is not PLATFORM");
        }
    }
}
