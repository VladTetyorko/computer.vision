package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CreateMarkRequest;
import com.drones.vision.api.dto.GeolocateMarkRequest;
import com.drones.vision.api.dto.MarkResponse;
import com.drones.vision.api.dto.PatchMarkRequest;
import com.drones.vision.api.dto.PromoteMarkRequest;
import com.drones.vision.api.dto.VerifyMarkRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.mark.MarkService;
import com.drones.vision.domain.model.MarkId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * The tactical marks half of the common operational picture (docs/MAP-REWORK-PLAN.md §4.1's frozen
 * wire contract) — replacing the deployment-wide, unscoped {@code /api/marks} surface
 * docs/TACTICAL-MARKS-PLAN.md shipped.
 *
 * <h2>Visibility is server-side and layer-scoped</h2>
 * {@link #list} returns only {@code ACTIVE} marks on layers the caller may view — resolved from
 * {@link CurrentUser#viewer()} through {@code MapAccessPolicy}, never from a client-side filter and
 * never (see {@code MapAccessPolicy}'s own javadoc) from {@code VisibilityScope}. The old surface's
 * "everyone at the command point sees the same marks" note is superseded.
 *
 * <h2>Errors</h2>
 * Entirely {@link MarkService}'s own exceptions through {@link ApiExceptionHandler}: {@link
 * IllegalArgumentException} (malformed id, unrecognized {@code kind}/{@code affiliation}/{@code
 * status}/{@code decision}, incomplete telemetry on {@link #geolocate}, an {@code UNVERIFIED}
 * verify decision) → 400; {@link java.util.NoSuchElementException} (unknown mark or unknown target
 * layer) → 404; {@link com.drones.vision.application.scope.AccessDeniedException} → 403.
 *
 * <p>Note the deliberate 403-not-404 split the service defines: acting on a mark whose layer the
 * caller cannot even view is {@code 403}, not a hiding {@code 404} — the same stance {@code
 * FlightCommandService} already takes for a command (as opposed to a read) on an out-of-scope
 * resource. Reads hide instead: an invisible mark is simply absent from {@link #list}.
 */
@RestController
@RequestMapping("/api/map/marks")
public class MapMarksController {

    private final MarkService marks;
    private final CurrentUser currentUser;

    public MapMarksController(MarkService marks, CurrentUser currentUser) {
        this.marks = Objects.requireNonNull(marks, "marks must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every {@code ACTIVE} mark on a layer the caller may view, newest first.
     *
     * @return the visible active marks
     */
    @GetMapping
    public List<MarkResponse> list() {
        return marks.list(currentUser.viewer()).stream().map(MarkResponse::from).toList();
    }

    /**
     * Drops a manual mark (a map click), {@code UNVERIFIED}.
     *
     * @param request the mark to create; an absent {@code layerId} resolves to the caller's default layer
     * @return the created mark
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MarkResponse create(@RequestBody CreateMarkRequest request) {
        return MarkResponse.from(marks.create(viewer(), request.toSpec()));
    }

    /**
     * Drops a mark projected from an asset's freshest telemetry (the cockpit "mark target" action).
     *
     * @param request which asset to project from, plus the mark's descriptive fields
     * @return the created mark
     */
    @PostMapping("/geolocate")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkResponse geolocate(@RequestBody GeolocateMarkRequest request) {
        return MarkResponse.from(marks.geolocate(viewer(), request.toSpec()));
    }

    /**
     * Applies a partial edit — annotation, drag-to-correct position, and/or a lifecycle transition.
     *
     * @param id      the mark to edit, as a canonical UUID string
     * @param request the fields to change; every field optional
     * @return the updated mark
     */
    @PatchMapping("/{id}")
    public MarkResponse patch(@PathVariable String id, @RequestBody PatchMarkRequest request) {
        return MarkResponse.from(marks.patch(viewer(), MarkId.of(id), request.toPatch()));
    }

    /**
     * Records a manager's review decision on a mark.
     *
     * @param id      the mark to review, as a canonical UUID string
     * @param request {@code CONFIRMED} or {@code REJECTED}
     * @return the updated mark
     */
    @PostMapping("/{id}/verify")
    public MarkResponse verify(@PathVariable String id, @RequestBody VerifyMarkRequest request) {
        return MarkResponse.from(marks.verify(viewer(), MarkId.of(id), request.toDecision()));
    }

    /**
     * Moves a mark to a wider-shared layer, stamping it {@code CONFIRMED} if it is not already.
     *
     * @param id      the mark to promote, as a canonical UUID string
     * @param request the destination layer; the whole body (and its one field) may be absent, which
     *                promotes to the deployment's COP layer
     * @return the updated mark, now on the target layer
     */
    @PostMapping("/{id}/promote")
    public MarkResponse promote(@PathVariable String id,
                                 @RequestBody(required = false) PromoteMarkRequest request) {
        PromoteMarkRequest effective = request == null ? PromoteMarkRequest.EMPTY : request;
        return MarkResponse.from(marks.promote(viewer(), MarkId.of(id), effective.toTarget()));
    }

    /**
     * Removes a mark.
     *
     * @param id the mark to delete, as a canonical UUID string
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        marks.delete(viewer(), MarkId.of(id));
    }

    private Viewer viewer() {
        return currentUser.viewer();
    }
}
