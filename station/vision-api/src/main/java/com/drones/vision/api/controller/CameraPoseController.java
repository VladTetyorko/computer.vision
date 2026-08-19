package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CalibrateCameraPoseRequest;
import com.drones.vision.api.dto.CalibrationResponse;
import com.drones.vision.api.dto.CameraPoseResponse;
import com.drones.vision.api.dto.PutCameraPoseRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.application.track.CalibrationRequest;
import com.drones.vision.map.application.track.CalibrationResult;
import com.drones.vision.map.application.track.CameraCalibrationSolver;
import com.drones.vision.map.application.track.CameraPoseInput;
import com.drones.vision.map.application.track.CameraPoseService;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Camera-pose CRUD plus the calibration solve, asset-scoped (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md §5/D10) — {@code GET}/{@code PUT}/{@code DELETE
 * /api/assets/{assetId}/camera-pose} and {@code POST .../camera-pose/calibration}.
 *
 * <h2>Flag gate (D8)</h2>
 * Every method's first line is {@link FixedCameraGeoProperties#requireEnabled()}: with {@code
 * vision.geo.fixed-camera.enabled=false} (the default), every one of these endpoints throws {@link
 * IllegalStateException}, mapped by {@link ApiExceptionHandler} to the frozen §5 {@code 409} —
 * checked before path/body parsing so a caller always gets the same honest refusal regardless of
 * what else might have been wrong with the request.
 *
 * <h2>Authority, not visibility (D10, matching {@link AssetController}'s own gates)</h2>
 * {@link #get} only requires the asset be <em>visible</em> — {@link #requireVisible} — so a
 * caller who may see the asset but not manage it can still read its pose; an out-of-scope or
 * unknown asset 404s, hiding existence. {@link #put}/{@link #delete}/{@link #calibrate} require
 * {@link #requireManageable} — a visible-but-unmanageable asset 403s instead, matching {@link
 * AssetController#requireManageable}'s own "an honest 403 beats a hiding 404" stance; {@link
 * CameraPoseService#put}/{@link CameraPoseService#delete} audit the write themselves (this
 * context's first audit write, per D10), so no separate audit call is needed here.
 *
 * <p>Request-body validation runs before the scope guard on every write, mirroring {@link
 * AssetController#update}'s own ordering discipline ("a bad request never depends on the caller's
 * scope") — a malformed pose or calibration body is a {@code 400} even for an asset the caller
 * cannot manage.
 *
 * <h2>Calibration never persists (D5)</h2>
 * {@link #calibrate} only calls {@link CameraCalibrationSolver#solve}; saving a solved pose is a
 * separate {@link #put} call the operator makes after reviewing the result.
 */
@RestController
public class CameraPoseController {

    private final CameraPoseService cameraPoseService;
    private final AssetService assetService;
    private final CurrentUser currentUser;
    private final FixedCameraGeoProperties properties;

    public CameraPoseController(CameraPoseService cameraPoseService, AssetService assetService,
                                 CurrentUser currentUser, FixedCameraGeoProperties properties) {
        this.cameraPoseService = Objects.requireNonNull(cameraPoseService, "cameraPoseService must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Fetches an asset's stored camera pose.
     *
     * @param assetId the asset id, as a canonical UUID string
     * @return the stored pose
     * @throws java.util.NoSuchElementException if no pose is stored, or the asset is unknown/out of
     *                                           scope (both 404 — hiding which)
     */
    @GetMapping("/api/assets/{assetId}/camera-pose")
    public CameraPoseResponse get(@PathVariable String assetId) {
        properties.requireEnabled();
        AssetId id = AssetId.of(assetId);
        requireVisible(id);
        CameraPose pose = cameraPoseService.find(id)
                .orElseThrow(() -> new NoSuchElementException("No camera pose stored for asset " + id.value()));
        return CameraPoseResponse.from(pose);
    }

    /**
     * Sets (creates or replaces) an asset's camera pose — manual entry, or confirming a calibration
     * solve.
     *
     * @param assetId the asset id, as a canonical UUID string
     * @param request the pose to set
     * @return the persisted pose
     */
    @PutMapping("/api/assets/{assetId}/camera-pose")
    public CameraPoseResponse put(@PathVariable String assetId, @RequestBody PutCameraPoseRequest request) {
        properties.requireEnabled();
        AssetId id = AssetId.of(assetId);
        CameraPoseInput input = request.toInput(); // a malformed body is a 400, before the scope guard
        requireManageable(id);
        return CameraPoseResponse.from(cameraPoseService.put(id, input, currentUser.userId()));
    }

    /**
     * Removes an asset's stored camera pose, if any. Idempotent.
     *
     * @param assetId the asset id, as a canonical UUID string
     */
    @DeleteMapping("/api/assets/{assetId}/camera-pose")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String assetId) {
        properties.requireEnabled();
        AssetId id = AssetId.of(assetId);
        requireManageable(id);
        cameraPoseService.delete(id, currentUser.userId());
    }

    /**
     * Solves yaw/pitch/hfov from 2–8 clicked landmark correspondences (D5) — never persists; the
     * caller reviews the result and calls {@link #put} separately to save it.
     *
     * @param assetId the asset id, as a canonical UUID string
     * @param request the camera's measured position/height, the calibration image's dimensions,
     *                and the clicked correspondences
     * @return the solve result — {@code solved:true} with a pose/quality, or {@code solved:false}
     *         with a verbatim refusal reason
     */
    @PostMapping("/api/assets/{assetId}/camera-pose/calibration")
    public CalibrationResponse calibrate(@PathVariable String assetId,
                                          @RequestBody CalibrateCameraPoseRequest request) {
        properties.requireEnabled();
        AssetId id = AssetId.of(assetId);
        CalibrationRequest solverRequest = request.toRequest(); // a malformed body is a 400, before the scope guard
        requireManageable(id);
        CalibrationResult result = CameraCalibrationSolver.solve(solverRequest, properties.calibrationMaxRmsErrorPixels());
        return CalibrationResponse.from(id, result, Instant.now());
    }

    /**
     * Guards a read: re-reads {@code id} through the caller's scope, so an out-of-scope or unknown
     * asset 404s before the pose lookup runs — mirrors {@link AssetController#requireVisible}.
     */
    private void requireVisible(AssetId id) {
        assetService.details(currentUser.scope(), id);
    }

    /**
     * Guards a mutation: re-reads {@code id} through the caller's scope (404 as above), then
     * additionally requires management authority over the asset (403) — mirrors {@link
     * AssetController#requireManageable}.
     */
    private void requireManageable(AssetId id) {
        AssetDetails details = assetService.details(currentUser.scope(), id);
        if (!currentUser.scope().canManage(details.summary().asset().ownership())) {
            throw new AccessDeniedException("Asset " + id.value() + " is outside your management authority");
        }
    }
}
