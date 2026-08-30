package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;

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
 * on a profile (&sect;5.1's own field list) — {@link #toTrackingConfig()} defaults them.
 *
 * @param mode              {@code "OFF"}/{@code "ASSOCIATE"}/{@code "FOLLOW"}, matched
 *                          case-insensitively on the way in; anything else is a 400
 * @param engineId          tracker engine id from {@code GET /api/cv/trackers}; {@code ""} means
 *                          "the server's default for the mode"
 * @param capabilityLevel   the capability-ladder ceiling this profile requests, [0,5]; {@code 0} =
 *                          auto-probe
 * @param verifyEveryMillis {@code FOLLOW} detector re-verify cadence, milliseconds
 * @param followFps         the Java-side sampler's target rate while {@code FOLLOW} is active
 */
public record CvProfileTrackingResponse(String mode, String engineId, int capabilityLevel, int verifyEveryMillis,
                                         int followFps) {

    /**
     * Maps a persisted profile's tracking configuration to the wire, dropping the five session-only
     * fields this shape does not carry.
     *
     * @param tracking the profile's tracking configuration
     * @return this shape's view of it
     */
    public static CvProfileTrackingResponse from(TrackingConfig tracking) {
        return new CvProfileTrackingResponse(tracking.mode().name(), tracking.engineId(), tracking.capabilityLevel(),
                tracking.verifyEveryMillis(), tracking.followFps());
    }

    /**
     * Expands this wire shape back into a full {@link TrackingConfig}, defaulting the five
     * session-only fields this shape does not carry: {@code redetectIouPercent}/{@code
     * maxAgeFrames}/{@code minHits} to {@link TrackingConfig}'s own published defaults, {@code
     * reupdateMaxGapMillis} to {@code 0} (server default), {@code lock} to {@code null} (a profile
     * never starts holding a target).
     *
     * @return the full tracking configuration a profile created/edited from this request persists
     * @throws IllegalArgumentException if {@code mode} is not a known mode (&rarr; 400), or any
     *                                  field is out of {@link TrackingConfig}'s own valid range
     */
    public TrackingConfig toTrackingConfig() {
        return new TrackingConfig(parseMode(mode), engineId, verifyEveryMillis, followFps,
                TrackingConfig.DEFAULT_REDETECT_IOU_PERCENT, TrackingConfig.DEFAULT_MAX_AGE_FRAMES,
                TrackingConfig.DEFAULT_MIN_HITS, capabilityLevel, 0, null);
    }

    /**
     * Case-insensitive {@link TrackingMode} lookup, listing the valid values on failure — the same
     * idiom {@link TrackingConfigRequest} already uses.
     */
    private static TrackingMode parseMode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("tracking.mode must not be null");
        }
        return Arrays.stream(TrackingMode.values())
                .filter(candidate -> candidate.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tracking mode: " + value + ". Valid values: "
                        + Arrays.stream(TrackingMode.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }
}
