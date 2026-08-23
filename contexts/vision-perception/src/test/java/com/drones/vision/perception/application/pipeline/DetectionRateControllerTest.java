package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rate loop's arithmetic (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;2, closing gap 1 of
 * docs/conclusions/CV-RATE-BUDGET.md). Every expectation here is derived from the association-budget
 * inequality rather than from an observed value, so a change in the formula fails these rather than
 * quietly re-baselining them.
 */
class DetectionRateControllerTest {

    private static final double FLOOR_FPS = 10.0;
    private static final double SOURCE_FPS = 60.0;
    private static final int MAX_IN_FLIGHT = 2;
    private static final int IOU_PERCENT = 30;
    private static final double HFOV_DEGREES = 60.0;
    private static final long SECOND = 1_000_000_000L;

    /** Budget for a box of width {@code w} at IoU {@code t}: {@code w (1-t)/(1+t)}. */
    private static double budget(double width) {
        double threshold = IOU_PERCENT / 100.0;
        return width * (1.0 - threshold) / (1.0 + threshold);
    }

    private static Detection tracked(double width, double velocityX, double velocityY) {
        return new Detection("person", 0.9, new BoundingBox(0.4, 0.4, width, width),
                new ModelRef("yolo", "latest"),
                new TrackRef(1L, TrackState.CONFIRMED, DetectionSource.TRACKER, velocityX, velocityY, 10));
    }

    private static Detection untracked() {
        return new Detection("person", 0.9, new BoundingBox(0.4, 0.4, 0.05, 0.05),
                new ModelRef("yolo", "latest"));
    }

    /** Settles the demand EWMA on one steady observation, so an expectation can be exact. */
    private static void settle(DetectionRateController controller, List<Detection> detections) {
        for (int i = 0; i < 200; i++) {
            controller.observeDetections(detections, IOU_PERCENT);
        }
    }

    private static DetectionRateController controller() {
        return new DetectionRateController(AdaptiveRateSettings.defaults(), HFOV_DEGREES);
    }

    @Test
    void leavesTheConfiguredRateAloneWhenNothingIsTracked() {
        DetectionRateController controller = controller();

        settle(controller, List.of(untracked()));

        assertEquals(0.0, controller.demandFps(), "an untracked box has no association to preserve");
        assertEquals(FLOOR_FPS, controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT));
    }

    @Test
    void derivesTheDemandFromTheTargetsOwnMotionAgainstItsAssociationBudget() {
        // A 0.2-wide box moving 0.5 frame widths per second. Budget = 0.2 * 0.7/1.3 = 0.1077, so the
        // rate that keeps one inter-frame step inside the budget is 0.5 / 0.1077 = 4.64 fps --
        // BELOW the operator's 10, which is the point: a slow target does not deserve more frames.
        DetectionRateController controller = controller();

        settle(controller, List.of(tracked(0.2, 0.5, 0.0)));

        assertEquals(0.5 / budget(0.2), controller.demandFps(), 1e-6);
        assertEquals(FLOOR_FPS, controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT),
                "the demand may raise the configured rate, never lower it");
    }

    @Test
    void raisesTheRateForASmallFastTargetThatWouldOtherwiseEscapeItsBudget() {
        // A 0.05-wide box crossing at 2 frame widths per second. Budget = 0.0269, demand = 74 fps,
        // which the source rate then caps at 60 -- exactly the case a fixed 10 fps loses.
        DetectionRateController controller = controller();

        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));

        assertEquals(2.0 / budget(0.05), controller.demandFps(), 1e-6);
        assertTrue(controller.demandFps() > 70.0, "sanity: this target really does demand a high rate");
        assertEquals(AdaptiveRateSettings.DEFAULT_MAX_FPS,
                controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT), 1e-9,
                "raised well above the configured 10, and held at the deployment's ceiling");
    }

    @Test
    void capsTheRateAtTheSourcesOwnFrameRateBecauseADeadlineNoFrameCanServeIsWaste() {
        DetectionRateController controller = controller();

        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));

        assertEquals(20.0, controller.targetFps(FLOOR_FPS, 20.0, MAX_IN_FLIGHT), 1e-9);
    }

    @Test
    void addsEgoMotionToTargetMotionSoAYawingCameraRaisesTheRateOnItsOwn() {
        // 30 deg/s across a 60 deg field of view = 0.5 frame widths per second of scene shift --
        // the term docs/conclusions/CV-RATE-BUDGET.md 2 shows dominates on a moving platform. The
        // target here is stationary, so the whole demand is ego-motion.
        DetectionRateController controller = controller();
        controller.observeAttitude(CameraAttitude.ofYaw(0.0, HFOV_DEGREES, Instant.EPOCH), 0L);
        controller.observeAttitude(CameraAttitude.ofYaw(30.0, HFOV_DEGREES, Instant.EPOCH), SECOND);

        settle(controller, List.of(tracked(0.2, 0.0, 0.0)));

        assertEquals(0.5 / budget(0.2), controller.demandFps(), 1e-6);
    }

    @Test
    void measuresAYawCrossingNorthTheShortWayRound() {
        // 350 -> 10 degrees is a 20 degree turn, not a 340 degree one. Getting this wrong would
        // read a gentle heading change as a violent slew and pin the rate at its ceiling.
        DetectionRateController controller = controller();
        controller.observeAttitude(CameraAttitude.ofYaw(350.0, HFOV_DEGREES, Instant.EPOCH), 0L);
        controller.observeAttitude(CameraAttitude.ofYaw(10.0, HFOV_DEGREES, Instant.EPOCH), SECOND);

        settle(controller, List.of(tracked(0.2, 0.0, 0.0)));

        assertEquals((20.0 / HFOV_DEGREES) / budget(0.2), controller.demandFps(), 1e-6);
    }

    @Test
    void ignoresEgoMotionEntirelyWhenTheDeploymentHasNotDescribedItsOptics() {
        // Same refusal to invent a scale that CameraAttitude#known() makes: with no field of view a
        // yaw cannot be converted to a frame-relative shift, and a plausible-looking guess would
        // produce a confidently wrong rate. The target-motion term still works, so the loop
        // degrades rather than switching off.
        DetectionRateController controller =
                new DetectionRateController(AdaptiveRateSettings.defaults(), 0.0);
        controller.observeAttitude(CameraAttitude.ofYaw(0.0, HFOV_DEGREES, Instant.EPOCH), 0L);
        controller.observeAttitude(CameraAttitude.ofYaw(30.0, HFOV_DEGREES, Instant.EPOCH), SECOND);

        settle(controller, List.of(tracked(0.2, 0.5, 0.0)));

        assertEquals(0.5 / budget(0.2), controller.demandFps(), 1e-6, "target motion only");
    }

    @Test
    void servesTheHardestObjectToHoldRatherThanTheAverageOne() {
        // The narrowest box sets the budget and the fastest sets the displacement, because the
        // average object is precisely the one that was never in danger of being lost.
        DetectionRateController controller = controller();

        settle(controller, List.of(tracked(0.30, 0.1, 0.0), tracked(0.05, 1.0, 0.0)));

        assertEquals(1.0 / budget(0.05), controller.demandFps(), 1e-6);
    }

    @Test
    void capsTheRateAtMeasuredDetectorCapacitySoTheLoopCannotOutrunTheDetector() {
        // 100ms round trip with 2 allowed in flight = 20 fps of real throughput. Asking for more
        // would only feed the in-flight limiter frames to discard, so the ceiling is the
        // measurement -- which also makes the loop self-limiting when cv-service slows down.
        DetectionRateController controller = controller();
        for (int i = 0; i < 200; i++) {
            controller.recordRoundTrip(100 * 1_000_000L);
        }

        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));

        assertEquals(20.0, controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT), 1e-6);
    }

    @Test
    void neverExceedsTheConfiguredCeiling() {
        DetectionRateController controller =
                new DetectionRateController(new AdaptiveRateSettings(true, 12.0, 1.0), HFOV_DEGREES);

        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));

        assertEquals(12.0, controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT), 1e-9);
    }

    @Test
    void pinsTheRateToTheConfiguredOneWhenTheLoopIsDisabled() {
        DetectionRateController controller =
                new DetectionRateController(AdaptiveRateSettings.disabled(), HFOV_DEGREES);

        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));

        assertTrue(controller.demandFps() > 70.0, "the demand is still computed, it just does not apply");
        assertEquals(FLOOR_FPS, controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT));
    }

    @Test
    void discardsAYawDeltaMeasuredAcrossATelemetryGap() {
        // Telemetry that stopped and resumed would otherwise differentiate as one enormous slew and
        // pin the rate at its ceiling for as long as the EWMA took to forget it.
        DetectionRateController controller = controller();
        controller.observeAttitude(CameraAttitude.ofYaw(0.0, HFOV_DEGREES, Instant.EPOCH), 0L);
        controller.observeAttitude(CameraAttitude.ofYaw(170.0, HFOV_DEGREES, Instant.EPOCH), 10 * SECOND);

        settle(controller, List.of(tracked(0.2, 0.0, 0.0)));

        assertEquals(0.0, controller.demandFps(), 1e-9);
    }

    @Test
    void settlesToExactlyZeroWhenTracksStopArrivingRatherThanDecayingForever() {
        // Found by reading the live API rather than by a failing test: the EWMA decays toward zero
        // geometrically and never arrives, so a stream that stopped tracking anything reported
        // `demandFps: 9.29e-38`. Harmless arithmetic, indistinguishable from a bug to whoever reads it.
        DetectionRateController controller = controller();
        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));

        settle(controller, List.of(untracked()));

        assertEquals(0.0, controller.demandFps(), "exactly zero, not a denormal");
    }

    @Test
    void clearForgetsTheDemandAndTheCapacityEstimate() {
        DetectionRateController controller = controller();
        settle(controller, List.of(tracked(0.05, 2.0, 0.0)));
        controller.recordRoundTrip(100 * 1_000_000L);

        controller.clear();

        assertEquals(0.0, controller.demandFps());
        assertEquals(FLOOR_FPS, controller.targetFps(FLOOR_FPS, SOURCE_FPS, MAX_IN_FLIGHT));
    }
}
