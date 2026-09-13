package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingKnobPatch;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The {@code tracking} object nested in a {@link CvProfileResponse} and accepted by {@link
 * CvProfileRequest} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1's frozen wire contract) — one
 * shared shape for both directions, matching how {@code models.ts} reuses its {@code
 * CvProfileTracking} interface for both {@code CvProfile.tracking} and {@code
 * CvProfileRequest.tracking}.
 *
 * <p>Deliberately narrower than {@link TrackingConfigRequest}/{@link
 * StreamConfigResponse.StreamTrackingConfigResponse}: a profile owns exactly these five knobs. The
 * other five {@link TrackingConfig} fields ({@code redetectIouPercent}, {@code maxAgeFrames},
 * {@code minHits}, {@code reupdateMaxGapMillis}, {@code lock}) are session-only and never persisted
 * on a profile (&sect;5.1's own field list).
 *
 * <h2>Wave W7.3 — a per-knob patch (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, decision
 * E22)</h2>
 * Every field is now individually nullable, mirroring {@link TrackingKnobPatch} field-for-field:
 * {@code null} means "leave this knob unset (inherit)", exactly like {@link CvProfileResponse}'s own
 * knobs. {@code @JsonInclude(NON_NULL)} on this record too, so an unset knob is a missing wire key,
 * never a JSON {@code null}. Two source domain types can produce this same wire shape: a raw,
 * possibly-partial {@link TrackingKnobPatch} (a persisted profile's own tracking group, {@link
 * #from(TrackingKnobPatch)}) or a fully-resolved {@link TrackingConfig} (an EFFECTIVE fold result,
 * never partial, {@link #fromResolved(TrackingConfig)}) — two named factories rather than one
 * overloaded {@code from}, so a reader never has to check which domain type a call site passes.
 *
 * @param mode              replacement tracking mode, or {@code null} to inherit; {@code "OFF"}/
 *                          {@code "ASSOCIATE"}/{@code "FOLLOW"} on the way in, matched
 *                          case-insensitively; anything else is a 400
 * @param engineId          tracker engine id from {@code GET /api/cv/trackers}, or {@code null} to
 *                          inherit; {@code ""} is a real, explicit value meaning "the server's
 *                          default for the mode" — distinct from {@code null}
 * @param capabilityLevel   the capability-ladder ceiling this profile requests, [0,5], or {@code
 *                          null} to inherit; {@code 0} = auto-probe
 * @param verifyEveryMillis {@code FOLLOW} detector re-verify cadence, milliseconds, or {@code null}
 *                          to inherit
 * @param followFps         the Java-side sampler's target rate while {@code FOLLOW} is active, or
 *                          {@code null} to inherit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvProfileTrackingResponse(String mode, String engineId, Integer capabilityLevel,
                                         Integer verifyEveryMillis, Integer followFps) {

    /**
     * Maps a persisted profile's own (possibly partial) tracking patch to the wire.
     *
     * @param patch the profile's tracking patch, or {@code null} when the whole group is unset
     * @return this shape's view of it, or {@code null} when {@code patch} is {@code null} — the
     *         whole {@code tracking} key is then omitted from the enclosing {@link
     *         CvProfileResponse} by its own {@code @JsonInclude(NON_NULL)}
     */
    public static CvProfileTrackingResponse from(TrackingKnobPatch patch) {
        if (patch == null) {
            return null;
        }
        return new CvProfileTrackingResponse(patch.mode() == null ? null : patch.mode().name(), patch.engineId(),
                patch.capabilityLevel(), patch.verifyEveryMillis(), patch.followFps());
    }

    /**
     * Maps a fully-resolved (EFFECTIVE) tracking configuration to the wire — every field always
     * present, since a fold result never leaves a knob unset.
     *
     * @param config the resolved tracking configuration
     * @return this shape's view of it
     */
    public static CvProfileTrackingResponse fromResolved(TrackingConfig config) {
        return new CvProfileTrackingResponse(config.mode().name(), config.engineId(), config.capabilityLevel(),
                config.verifyEveryMillis(), config.followFps());
    }

    /**
     * Expands this wire shape back into a {@link TrackingKnobPatch} — unlike the pre-W7.3 {@code
     * toTrackingConfig()} this replaces, nothing here is defaulted: an absent field stays {@code
     * null} (inherit), matching {@code CvProfile#foldOnto}'s own per-knob semantics.
     *
     * @return the patch a profile created/edited from this request carries
     * @throws IllegalArgumentException if {@code mode} is set but not a known mode (&rarr; 400)
     */
    public TrackingKnobPatch toTrackingKnobPatch() {
        return new TrackingKnobPatch(mode == null ? null : parseMode(mode), engineId, capabilityLevel,
                verifyEveryMillis, followFps);
    }

    /**
     * Case-insensitive {@link TrackingMode} lookup, listing the valid values on failure — the same
     * idiom {@link TrackingConfigRequest} already uses.
     */
    private static TrackingMode parseMode(String value) {
        return Arrays.stream(TrackingMode.values())
                .filter(candidate -> candidate.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tracking mode: " + value + ". Valid values: "
                        + Arrays.stream(TrackingMode.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }
}
