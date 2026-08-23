package com.drones.vision.api.exception;

/**
 * Thrown by {@code com.drones.vision.api.controller.GeoRegionController} when cv-service cannot be
 * reached for a region-index call (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3) — {@code
 * GrpcReferenceIndexPort#list}/{@code #delete} let the underlying transport failure propagate
 * uncaught (that adapter's own javadoc), so the controller catches it at the edge and re-throws
 * this instead, since {@code vision-api} must not name a {@code io.grpc} type directly. Mapped to
 * {@code 503} by {@link ApiExceptionHandler}.
 */
public class GeoServiceUnavailableException extends RuntimeException {

    public GeoServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
