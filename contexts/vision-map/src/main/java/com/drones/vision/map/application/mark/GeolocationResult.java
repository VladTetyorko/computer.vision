package com.drones.vision.map.application.mark;

import com.drones.vision.map.domain.model.Mark;

/**
 * The outcome of {@link MarkService#geolocate}: the created {@code DETECTION} {@link Mark}, plus
 * whether the pose used to project it was actually measured or fell back to an assumed constant
 * (docs/plans/active/GEO-POSE-PLAN.md G5/§4.3, wave V3).
 *
 * <p>A separate wrapper rather than a field on {@link Mark} itself: {@code measured} describes how
 * *this* fix was produced, not a durable property of the mark (an operator may drag-correct the pin
 * afterward, same as any {@code DETECTION} mark — see {@code Mark}'s own javadoc), and persisting it
 * would mean widening the domain record plus every adapter that maps it (out of this wave's scope).
 * It is therefore surfaced only on the direct response to the geolocate call, not on subsequent
 * {@link MarkService#list} reads of the same mark — a known gap, flagged for whichever wave
 * eventually wants the cockpit to show "measured" after a page reload.
 *
 * @param mark     the created mark
 * @param measured {@code true} only when the projection used a real gimbal depression reading
 *                 <em>and</em> a real AGL sample, per {@code GeoProjection.CameraAim#measured()};
 *                 {@code false} whenever any part of the aim was assumed — including whenever the
 *                 caller supplied an explicit {@code depressionDegrees} override, since an override
 *                 replaces the measurement rather than confirming it. Not an accuracy guarantee, and
 *                 not a reason to treat the mark as any less editable than an assumed one
 */
public record GeolocationResult(Mark mark, boolean measured) {

    public GeolocationResult {
        if (mark == null) {
            throw new IllegalArgumentException("GeolocationResult mark must not be null");
        }
    }
}
