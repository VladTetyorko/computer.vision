package com.drones.vision.api.dto;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.application.track.CalibrationResult;

import java.time.Instant;

/**
 * The 200 body of {@code POST /api/assets/{assetId}/camera-pose/calibration}
 * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5/D5) — mirrors {@link CalibrationResult} field for
 * field. <b>Deliberately not {@code @JsonInclude(NON_NULL)}</b>: unlike {@link
 * ProjectedTrackResponse}, §5's two frozen examples both show every key present with an explicit
 * {@code null} where the shape does not apply ({@code "reason": null} when solved, {@code "pose":
 * null, "quality": null} when not) — omitting those keys would not byte-match the frozen contract.
 *
 * <p>{@code rmsErrorPixels} mirrors {@link CalibrationResult#rmsErrorPixels()} verbatim, including
 * its one legitimate {@code null} case: a refusal that fired on a degenerate-geometry check before
 * any fit was attempted (bearing spread &lt; 10°, or a landmark closer than 3 m) never computed a
 * residual, so there is nothing honest to report. §5's own two examples don't show this case, but
 * fabricating a number here would be exactly the confident-wrong-answer C7 forbids — a caller
 * needing this field must be prepared for {@code null} in that one refusal case, though nothing in
 * this codebase's own consumer ({@code core/camera-geo/camera-geo-logic.ts#calibrationSummary}, wave
 * G5) dereferences it when {@code solved: false} in the first place.
 *
 * @param solved         whether a pose was found within the configured tolerance
 * @param pose           the solved pose, {@code null} unless {@code solved}
 * @param rmsErrorPixels the fit's residual in pixels, or {@code null} if a fit was never attempted
 * @param quality        {@code GOOD}/{@code UNDETERMINED}, {@code null} unless {@code solved}
 * @param reason         why the solve was refused, verbatim; {@code null} when {@code solved}
 */
public record CalibrationResponse(boolean solved, CameraPoseResponse pose, Double rmsErrorPixels, String quality,
                                   String reason) {

    /**
     * Maps a domain {@link CalibrationResult} to its wire representation.
     *
     * @param assetId  the asset the calibration was run for
     * @param result   the solver's own result
     * @param solvedAt when the solve ran — stamped on a solved result's embedded {@code pose},
     *                 which is never persisted and so has no real {@code updatedAt} of its own
     * @return the response body for {@code result}
     */
    public static CalibrationResponse from(AssetId assetId, CalibrationResult result, Instant solvedAt) {
        CameraPoseResponse pose = result.solved()
                ? CameraPoseResponse.preview(assetId, result.pose(), result.rmsErrorPixels(), solvedAt)
                : null;
        String quality = result.quality() == null ? null : result.quality().name();
        return new CalibrationResponse(result.solved(), pose, result.rmsErrorPixels(), quality, result.reason());
    }
}
