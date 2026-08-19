package com.drones.vision.map.application.track;

import com.drones.vision.kernel.BearingDistance;
import com.drones.vision.kernel.FixedCameraPose;
import com.drones.vision.kernel.GeoProjection;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Solves a fixed camera's yaw/pitch/hfov from 2–8 clicked landmark correspondences
 * (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D5, exactly). Deterministic, no third-party
 * dependencies, pure — mirrors kernel's {@link GeoProjection}/{@code FixedCameraGeo} idiom (private
 * constructor, static methods only; java-clean-code SKILL.md §1: one implementation, no substitution
 * point, no interface earned) even though it lives in this context's application layer rather than
 * the kernel (D5: "single consumer today; C11 may lift it to kernel later").
 *
 * <h2>What is solved, and what is assumed</h2>
 * Solved: yaw, pitch, hfov. Assumed, stated on every result: roll = 0, flat ground at the camera's
 * own elevation, square pixels ({@code tan(vfov/2) = tan(hfov/2)*height/width} — the same relation
 * {@code FixedCameraGeo} uses, which is why the horizontal and vertical residual axes share one
 * degrees-per-pixel conversion factor, {@code hfov/width}, at the end of {@link #solve}). Camera
 * <em>position</em> and <em>height</em> are operator-measured inputs, never solved (D5).
 *
 * <h2>Method</h2>
 * For each landmark, {@link GeoProjection#bearingDistance} gives a measured bearing/range to it;
 * range plus the operator-measured AGL gives a measured depression. The landmark's normalized pixel
 * coordinates give a <em>predicted</em> azimuth/depression offset for any candidate {@code hfov}, via
 * exactly {@code FixedCameraGeo}'s own pixel-angle formula (not re-derived independently — see
 * {@link #pixelAngleOffsetDegrees}). A 1-D golden-section search over {@code hfov} &isin;
 * [{@value #MIN_HFOV_DEGREES}, {@value #MAX_HFOV_DEGREES}] degrees, {@value
 * #GOLDEN_SECTION_ITERATIONS} fixed iterations, finds the {@code hfov} minimizing the summed squared
 * angular residual once {@code yaw} (circular mean of azimuth residuals) and {@code pitch} (mean of
 * depression residuals) are re-fit at every candidate.
 *
 * <h2>Honesty over completeness (D5)</h2>
 * Two degeneracy checks run <em>before</em> the search, on the raw landmark geometry alone, refusing
 * with a named reason rather than fitting a confident pose to garbage geometry:
 * <ul>
 *   <li>any landmark nearer than {@value #MIN_LANDMARK_DISTANCE_METERS} m from the camera —
 *       {@link #REASON_LANDMARK_TOO_CLOSE};</li>
 *   <li>every landmark's bearing within {@value #MIN_BEARING_SPREAD_DEGREES}&deg; of every other
 *       (radially collinear as seen from the camera, the classic "picked landmarks all roughly in
 *       the same direction" degenerate case) — {@link #REASON_BEARING_SPREAD_TOO_NARROW}.</li>
 * </ul>
 * After the search, for {@code N >= 3} landmarks only, a residual exceeding the caller-supplied
 * {@code maxRmsErrorPixels} refuses with {@link #REASON_RESIDUAL_EXCEEDS}. For exactly 2 landmarks
 * the fit is reported regardless of its residual — D5 calls it "exact," meaning nothing here can
 * independently verify it, not that the residual is necessarily near zero — with {@link
 * CalibrationQuality#UNDETERMINED} rather than a pass/fail RMS gate, so the UI can ask for a third
 * point instead of silently trusting an unverifiable fit.
 */
public final class CameraCalibrationSolver {

    /** Golden-section search lower bound for {@code hfov}, degrees (D5). */
    static final double MIN_HFOV_DEGREES = 20.0;

    /** Golden-section search upper bound for {@code hfov}, degrees (D5). */
    static final double MAX_HFOV_DEGREES = 120.0;

    /** Fixed iteration count for the golden-section search (D5 says "fixed iterations"; this is the count chosen). */
    static final int GOLDEN_SECTION_ITERATIONS = 100;

    /**
     * Below this many degrees of pairwise bearing spread, the landmarks are treated as radially
     * collinear and the geometry cannot constrain a solve (D5). Not listed among §6's configuration
     * knobs — unlike {@code calibration.max-rms-error-pixels}, D5 states this as part of the
     * algorithm itself, so it is a named constant here rather than a caller-supplied setting; see
     * this class's own javadoc note in contexts/vision-map/MODULE.md for the gap this leaves.
     */
    static final double MIN_BEARING_SPREAD_DEGREES = 10.0;

    /**
     * Below this many meters from the camera, a landmark's measured depression is too sensitive to
     * small errors to trust (D5). Same "named constant, not a §6 setting" status as {@link
     * #MIN_BEARING_SPREAD_DEGREES} — see that field's javadoc.
     */
    static final double MIN_LANDMARK_DISTANCE_METERS = 3.0;

    private static final double INVERSE_GOLDEN_RATIO = (Math.sqrt(5.0) - 1.0) / 2.0;

    /** Frozen wire text (§5) — {@code "landmark {1-based index} is {distance}m from the camera"}. */
    static final String REASON_LANDMARK_TOO_CLOSE_TEMPLATE = "landmark %d is %sm from the camera";

    /** Frozen wire text (§5) — {@code "landmarks span only {spread}° of bearing"}. */
    static final String REASON_BEARING_SPREAD_TOO_NARROW_TEMPLATE = "landmarks span only %s° of bearing";

    /** Frozen wire text (§5) — {@code "residual {rms}px exceeds {threshold}px"}. */
    static final String REASON_RESIDUAL_EXCEEDS_TEMPLATE = "residual %spx exceeds %spx";

    private CameraCalibrationSolver() {
    }

    /**
     * Solves yaw/pitch/hfov from {@code request}'s landmarks.
     *
     * @param request           the camera's measured position/height, the image dimensions, and the
     *                          clicked correspondences
     * @param maxRmsErrorPixels the residual ceiling (D5, {@code vision.geo.fixed-camera.calibration
     *                          .max-rms-error-pixels}) — refuses a {@code N >= 3} fit whose residual
     *                          exceeds this; must be positive
     * @return the result — solved with a pose and quality, or refused with a named reason
     * @throws IllegalArgumentException if {@code request} is {@code null} or {@code maxRmsErrorPixels}
     *                                   is not positive
     */
    public static CalibrationResult solve(CalibrationRequest request, double maxRmsErrorPixels) {
        Objects.requireNonNull(request, "request must not be null");
        if (Double.isNaN(maxRmsErrorPixels) || Double.isInfinite(maxRmsErrorPixels) || maxRmsErrorPixels <= 0.0) {
            throw new IllegalArgumentException("maxRmsErrorPixels must be positive: " + maxRmsErrorPixels);
        }

        List<CalibrationLandmark> landmarks = request.landmarks();
        int n = landmarks.size();
        double[] bearingDegrees = new double[n];
        double[] measuredDepressionDegrees = new double[n];

        for (int i = 0; i < n; i++) {
            CalibrationLandmark landmark = landmarks.get(i);
            BearingDistance bd = GeoProjection.bearingDistance(request.cameraPosition(), landmark.mapPosition());
            if (bd.distanceMeters() < MIN_LANDMARK_DISTANCE_METERS) {
                String reason = String.format(Locale.ROOT, REASON_LANDMARK_TOO_CLOSE_TEMPLATE, i + 1,
                        oneDecimal(bd.distanceMeters()));
                return CalibrationResult.refused(reason);
            }
            bearingDegrees[i] = bd.bearingDegrees();
            measuredDepressionDegrees[i] = Math.toDegrees(Math.atan(request.aglMeters() / bd.distanceMeters()));
        }

        double bearingSpreadDegrees = maxPairwiseCircularSpreadDegrees(bearingDegrees);
        if (bearingSpreadDegrees < MIN_BEARING_SPREAD_DEGREES) {
            String reason = String.format(Locale.ROOT, REASON_BEARING_SPREAD_TOO_NARROW_TEMPLATE,
                    oneDecimalOrWhole(bearingSpreadDegrees));
            return CalibrationResult.refused(reason);
        }

        double bestHfovDegrees = searchHfov(landmarks, bearingDegrees, measuredDepressionDegrees, request.imageWidthPixels(),
                request.imageHeightPixels());
        Fit fit = fitAt(bestHfovDegrees, landmarks, bearingDegrees, measuredDepressionDegrees, request.imageWidthPixels(),
                request.imageHeightPixels());

        double degreesPerPixel = bestHfovDegrees / request.imageWidthPixels();
        double rmsErrorPixels = fit.rmsDegrees / degreesPerPixel;

        if (n == CalibrationRequest.MIN_LANDMARKS) {
            FixedCameraPose pose = new FixedCameraPose(request.cameraPosition(), request.aglMeters(), fit.yawDegrees,
                    fit.pitchDegrees, bestHfovDegrees);
            return CalibrationResult.solved(pose, rmsErrorPixels, CalibrationQuality.UNDETERMINED);
        }

        if (rmsErrorPixels > maxRmsErrorPixels) {
            String reason = String.format(Locale.ROOT, REASON_RESIDUAL_EXCEEDS_TEMPLATE, oneDecimal(rmsErrorPixels),
                    oneDecimal(maxRmsErrorPixels));
            return CalibrationResult.refused(reason, rmsErrorPixels);
        }

        FixedCameraPose pose = new FixedCameraPose(request.cameraPosition(), request.aglMeters(), fit.yawDegrees,
                fit.pitchDegrees, bestHfovDegrees);
        return CalibrationResult.solved(pose, rmsErrorPixels, CalibrationQuality.GOOD);
    }

    /**
     * Golden-section search over {@code hfov} minimizing {@link #fitAt}'s summed squared residual —
     * the search's own objective never needs {@code yaw}/{@code pitch} outside {@link #fitAt}, since
     * both are re-derived from scratch at every candidate {@code hfov}.
     */
    private static double searchHfov(List<CalibrationLandmark> landmarks, double[] bearingDegrees,
                                      double[] measuredDepressionDegrees, int imageWidthPixels, int imageHeightPixels) {
        double lo = MIN_HFOV_DEGREES;
        double hi = MAX_HFOV_DEGREES;
        double c = hi - INVERSE_GOLDEN_RATIO * (hi - lo);
        double d = lo + INVERSE_GOLDEN_RATIO * (hi - lo);
        double objectiveC = fitAt(c, landmarks, bearingDegrees, measuredDepressionDegrees, imageWidthPixels, imageHeightPixels).sumSquaredResidualDegrees;
        double objectiveD = fitAt(d, landmarks, bearingDegrees, measuredDepressionDegrees, imageWidthPixels, imageHeightPixels).sumSquaredResidualDegrees;

        for (int i = 0; i < GOLDEN_SECTION_ITERATIONS; i++) {
            if (objectiveC < objectiveD) {
                hi = d;
                d = c;
                objectiveD = objectiveC;
                c = hi - INVERSE_GOLDEN_RATIO * (hi - lo);
                objectiveC = fitAt(c, landmarks, bearingDegrees, measuredDepressionDegrees, imageWidthPixels, imageHeightPixels).sumSquaredResidualDegrees;
            } else {
                lo = c;
                c = d;
                objectiveC = objectiveD;
                d = lo + INVERSE_GOLDEN_RATIO * (hi - lo);
                objectiveD = fitAt(d, landmarks, bearingDegrees, measuredDepressionDegrees, imageWidthPixels, imageHeightPixels).sumSquaredResidualDegrees;
            }
        }
        return (lo + hi) / 2.0;
    }

    /**
     * For a candidate {@code hfovDegrees}: predicts each landmark's pixel-implied azimuth/depression
     * offset (exactly {@code FixedCameraGeo}'s own formula), fits {@code yaw} as the circular mean and
     * {@code pitch} as the plain mean of the per-landmark residual-to-measurement differences, and
     * reports the summed squared angular residual at that fit.
     */
    private static Fit fitAt(double hfovDegrees, List<CalibrationLandmark> landmarks, double[] bearingDegrees,
                              double[] measuredDepressionDegrees, int imageWidthPixels, int imageHeightPixels) {
        int n = landmarks.size();
        double halfHfovTangent = Math.tan(Math.toRadians(hfovDegrees) / 2.0);
        double aspectRatio = (double) imageHeightPixels / (double) imageWidthPixels;

        double[] azimuthCandidateDegrees = new double[n];
        double[] depressionCandidateDegrees = new double[n];
        for (int i = 0; i < n; i++) {
            CalibrationLandmark landmark = landmarks.get(i);
            double azimuthOffsetDegrees = pixelAngleOffsetDegrees(landmark.u(), halfHfovTangent, 1.0);
            double depressionOffsetDegrees = pixelAngleOffsetDegrees(landmark.v(), halfHfovTangent, aspectRatio);
            azimuthCandidateDegrees[i] = bearingDegrees[i] - azimuthOffsetDegrees;
            depressionCandidateDegrees[i] = measuredDepressionDegrees[i] - depressionOffsetDegrees;
        }

        double yawDegrees = circularMeanDegrees(azimuthCandidateDegrees);
        double pitchDegrees = mean(depressionCandidateDegrees);

        double sumSquared = 0.0;
        for (int i = 0; i < n; i++) {
            double azimuthResidual = circularDifferenceDegrees(azimuthCandidateDegrees[i], yawDegrees);
            double depressionResidual = depressionCandidateDegrees[i] - pitchDegrees;
            sumSquared += azimuthResidual * azimuthResidual + depressionResidual * depressionResidual;
        }
        double rmsDegrees = Math.sqrt(sumSquared / (2.0 * n));
        return new Fit(yawDegrees, pitchDegrees, sumSquared, rmsDegrees);
    }

    /**
     * The pixel-angle-offset half of {@code FixedCameraGeo.project}'s formula, reused verbatim (not
     * re-derived) so the solver and the projector always agree on what a pixel coordinate means:
     * {@code atan((2*normalized-1)*halfHfovTangent*axisScale)}, {@code axisScale=1.0} for the
     * horizontal axis, {@code height/width} for the vertical one.
     */
    private static double pixelAngleOffsetDegrees(double normalizedCoordinate, double halfHfovTangent, double axisScale) {
        return Math.toDegrees(Math.atan((2.0 * normalizedCoordinate - 1.0) * halfHfovTangent * axisScale));
    }

    /** Circular mean of a set of degree angles, wrapped to {@code [0,360)}. */
    private static double circularMeanDegrees(double[] anglesDegrees) {
        double sumSin = 0.0;
        double sumCos = 0.0;
        for (double angle : anglesDegrees) {
            double radians = Math.toRadians(angle);
            sumSin += Math.sin(radians);
            sumCos += Math.cos(radians);
        }
        double meanDegrees = Math.toDegrees(Math.atan2(sumSin, sumCos));
        return meanDegrees < 0.0 ? meanDegrees + 360.0 : meanDegrees;
    }

    /** Signed circular difference {@code a - b}, wrapped to {@code [-180,180]}. */
    private static double circularDifferenceDegrees(double a, double b) {
        return ((a - b + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /** The largest pairwise circular difference among a set of bearings — a wraparound-safe "spread." */
    private static double maxPairwiseCircularSpreadDegrees(double[] bearingDegrees) {
        double max = 0.0;
        for (int i = 0; i < bearingDegrees.length; i++) {
            for (int j = i + 1; j < bearingDegrees.length; j++) {
                max = Math.max(max, Math.abs(circularDifferenceDegrees(bearingDegrees[i], bearingDegrees[j])));
            }
        }
        return max;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Matches the §5 example's whole-number bearing-spread formatting ("6°") without a spurious ".0". */
    private static String oneDecimalOrWhole(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /** Local accumulator for one golden-section candidate's fit; not part of the public API. */
    private record Fit(double yawDegrees, double pitchDegrees, double sumSquaredResidualDegrees, double rmsDegrees) {
    }
}
