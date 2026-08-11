package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CreateDrawingRequest;
import com.drones.vision.api.dto.DrawingResponse;
import com.drones.vision.api.dto.PatchDrawingRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.map.DrawingService;
import com.drones.vision.domain.model.DrawingId;
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
 * Lines, polygons, arrows and text annotations on the map (docs/plans/done/MAP-REWORK-PLAN.md §4.1's frozen
 * wire contract) — the substrate a later "plans" slice builds on.
 *
 * <p>Same shape as {@link MapMarksController}: {@link CurrentUser#viewer()} resolves who is acting,
 * {@link DrawingService} owns every visibility and authorization decision, and errors surface
 * through {@link ApiExceptionHandler} unchanged — {@link IllegalArgumentException} (malformed id,
 * unrecognized {@code kind}, wrong point count for the kind, a non-kebab-case {@code colorToken}) →
 * 400; {@link java.util.NoSuchElementException} (unknown drawing or unknown layer) → 404; {@link
 * com.drones.vision.application.scope.AccessDeniedException} → 403.
 */
@RestController
@RequestMapping("/api/map/drawings")
public class MapDrawingsController {

    private final DrawingService drawings;
    private final CurrentUser currentUser;

    public MapDrawingsController(DrawingService drawings, CurrentUser currentUser) {
        this.drawings = Objects.requireNonNull(drawings, "drawings must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every drawing on a layer the caller may view, newest first.
     *
     * @return the visible drawings
     */
    @GetMapping
    public List<DrawingResponse> list() {
        return drawings.list(currentUser.viewer()).stream().map(DrawingResponse::from).toList();
    }

    /**
     * Creates a drawing.
     *
     * @param request what to draw; an absent {@code layerId} resolves to the caller's default layer
     * @return the created drawing
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DrawingResponse create(@RequestBody CreateDrawingRequest request) {
        return DrawingResponse.from(drawings.create(currentUser.viewer(), request.toSpec()));
    }

    /**
     * Applies a partial edit — replacement geometry and/or descriptive fields.
     *
     * @param id      the drawing to edit, as a canonical UUID string
     * @param request the fields to change; every field optional
     * @return the updated drawing
     */
    @PatchMapping("/{id}")
    public DrawingResponse patch(@PathVariable String id, @RequestBody PatchDrawingRequest request) {
        return DrawingResponse.from(drawings.patch(currentUser.viewer(), DrawingId.of(id), request.toPatch()));
    }

    /**
     * Removes a drawing.
     *
     * @param id the drawing to delete, as a canonical UUID string
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        drawings.delete(currentUser.viewer(), DrawingId.of(id));
    }
}
