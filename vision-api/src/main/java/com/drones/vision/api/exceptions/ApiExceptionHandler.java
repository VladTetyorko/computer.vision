package com.drones.vision.api.exceptions;

import com.drones.vision.api.AssetImageController;
import com.drones.vision.api.DeviceProbeController;
import com.drones.vision.api.HlsProxyController;
import com.drones.vision.api.dto.ErrorResponse;
import com.drones.vision.application.AccessDeniedException;
import com.drones.vision.application.ProbeFailedException;
import com.drones.vision.application.UnsupportedProtocolException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Maps the exceptions the application layer actually throws to HTTP responses.
 *
 * <p>Determined by reading the real {@code vision-application}/{@code
 * vision-domain} sources rather than assuming a shape:
 * <ul>
 *   <li>{@code DeviceRegistration}, {@code
 *       PipelineConfig}, and {@code DetectionQuery} validate their own inputs
 *       in compact constructors, throwing {@link IllegalArgumentException} —
 *       surfaced from bad registration input (blank name, unknown type,
 *       malformed uri, ...) as well as a non-positive {@code limit} on
 *       {@code GET /api/streams/{streamId}/detections}.</li>
 *   <li>{@code VideoSourceRegistry.sourceFor} throws {@link
 *       UnsupportedProtocolException} when no adapter is registered for a
 *       device's protocol — surfaced from starting a stream, not from
 *       registration (registration itself does not check adapter
 *       availability).</li>
 *   <li>{@code StreamService.start} throws {@link NoSuchElementException}
 *       for an unknown device id.</li>
 *   <li>{@code StreamService.start} throws {@link IllegalStateException}
 *       when the device already has an active stream (Phase 0/1 allow at
 *       most one stream per device).</li>
 *   <li>{@link HlsProxyController} throws {@link HlsUpstreamUnavailableException}
 *       when the upstream mediamtx HLS server can't be reached at all
 *       (connection refused, DNS failure, timeout, broken redirect chain) —
 *       distinct from a normal non-2xx response actually received from
 *       upstream, which is passed through verbatim rather than mapped
 *       here.</li>
 *   <li>{@link DeviceProbeController} throws {@link ProbeFailedException} when a probe's protocol
 *       is recognized but the connection itself fails, times out, or ends without a frame —
 *       mapped to {@code 422} (docs/UX-REWORK-PLAN.md §U-d item 3), distinct from {@link
 *       UnsupportedProtocolException}'s {@code 400} (an unrecognized protocol is a malformed
 *       request, not a probe failure).</li>
 *   <li>{@link AssetImageController} throws {@link PayloadTooLargeException} when an uploaded
 *       image body exceeds the configured maximum — mapped to {@code 413}.</li>
 * </ul>
 * {@code StreamService.stop} never throws — stopping an unknown/already
 * stopped stream is a documented no-op — so there is no 404 mapping for the
 * delete-stream endpoint.
 *
 * <p>Body shape: {@code {"error": "...", "message": "..."}}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, UnsupportedProtocolException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    /**
     * A scoped <em>command/grant</em> against an asset the caller cannot see maps to {@code 403}
     * (docs/U-SCOPE-PLAN.md, U-e slice 2) — deliberately distinct from the {@code 404} a scoped
     * <em>read</em> gives ({@link NoSuchElementException}, which hides existence) and the {@code
     * 409} an ordinary {@link IllegalStateException} gives. For a command the honest answer is "you
     * may not do this," not "it isn't there."
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(HlsUpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleBadGateway(HlsUpstreamUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("BAD_GATEWAY", ex.getMessage()));
    }

    @ExceptionHandler(ProbeFailedException.class)
    public ResponseEntity<ErrorResponse> handleProbeFailed(ProbeFailedException ex) {
        // UNPROCESSABLE_CONTENT, not the deprecated UNPROCESSABLE_ENTITY alias (Spring Framework 7) — same 422.
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("UNPROCESSABLE_ENTITY", ex.getMessage()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex) {
        // CONTENT_TOO_LARGE, not the deprecated PAYLOAD_TOO_LARGE alias (Spring Framework 7) — same 413.
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new ErrorResponse("PAYLOAD_TOO_LARGE", ex.getMessage()));
    }
}
