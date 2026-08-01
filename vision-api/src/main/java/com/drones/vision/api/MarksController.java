package com.drones.vision.api;

import com.drones.vision.api.dto.CreateMarkRequest;
import com.drones.vision.api.dto.GeolocateMarkRequest;
import com.drones.vision.api.dto.MarkResponse;
import com.drones.vision.api.dto.PatchMarkRequest;
import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.application.MarkService;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the shared tactical-marks operational picture
 * (docs/TACTICAL-MARKS-PLAN.md §4's frozen wire contract) — structurally a point version of {@link
 * GeofenceController}'s CRUD shape, but threaded with {@link CurrentUser} exactly like {@link
 * AssetController}, since a mark (unlike a geofence zone) is owned and audited.
 *
 * <p>Constructor-injected with {@link MarkService} and {@link CurrentUser} only — no other driven
 * port is needed here, mirroring {@link GeofenceController}'s minimal-dependency shape.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser} and is passed to every mutating call, exactly as
 * {@link AssetController} does — {@link MarkService} never sees a token, a header, or Spring
 * Security.
 *
 * <h2>Visibility</h2>
 * {@link #list} is deliberately unscoped — every {@code ACTIVE} mark, deployment-wide, matching
 * {@link MarkService#list()}'s own javadoc ("the shared operational picture means everyone at the
 * command point sees the same marks"). There is no {@code CurrentUser#scope()} argument to thread
 * into it, unlike {@link AssetController#list}.
 *
 * <p>Error mapping is entirely {@link MarkService}'s own exceptions surfacing through {@link
 * ApiExceptionHandler} — no controller-side translation: {@link IllegalArgumentException} (a
 * malformed mark/asset id, an unrecognized {@code kind}/{@code status}, or incomplete telemetry on
 * {@link #geolocate}) → 400; {@link java.util.NoSuchElementException} (unknown mark id on {@link
 * #update}/{@link #delete}) → 404; {@link com.drones.vision.application.AccessDeniedException}
 * ({@link #update}/{@link #delete} attempted by neither the mark's creator nor a manager) → 403.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter, and
 * never on {@code org.springframework.security} (the acting user is reached through {@link
 * CurrentUser}, not the security context directly).
 */
@RestController
public class MarksController {

    private final MarkService markService;
    private final CurrentUser currentUser;

    public MarksController(MarkService markService, CurrentUser currentUser) {
        this.markService = Objects.requireNonNull(markService, "markService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every active mark, deployment-wide, newest first (see {@link MarkService#list()}).
     *
     * @return every {@code ACTIVE} mark
     */
    @GetMapping("/api/marks")
    public List<MarkResponse> list() {
        return markService.list().stream().map(MarkResponse::from).toList();
    }

    /**
     * Drops a manual mark (a map click).
     *
     * @param request the mark to create
     * @return the created mark
     */
    @PostMapping("/api/marks")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkResponse create(@RequestBody CreateMarkRequest request) {
        Mark created = markService.create(request.toSpec(), currentUser.ownership(), currentUser.userId());
        return MarkResponse.from(created);
    }

    /**
     * Drops a mark projected from an asset's freshest telemetry (the cockpit "geolocate" action).
     *
     * @param request which asset to project from, and the mark's descriptive fields
     * @return the created mark
     */
    @PostMapping("/api/marks/geolocate")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkResponse geolocate(@RequestBody GeolocateMarkRequest request) {
        Mark created = markService.geolocate(request.toSpec(), currentUser.ownership(), currentUser.userId());
        return MarkResponse.from(created);
    }

    /**
     * Applies a partial edit — annotation (label/note/kind/position, including drag-to-correct)
     * and/or a lifecycle transition ({@code status}).
     *
     * @param id      the mark to edit, as a canonical UUID string
     * @param request the fields to change; every field optional
     * @return the updated mark
     */
    @PatchMapping("/api/marks/{id}")
    public MarkResponse update(@PathVariable String id, @RequestBody PatchMarkRequest request) {
        Mark updated =
                markService.update(MarkId.of(id), request.toPatch(), currentUser.userId(), currentUser.scope());
        return MarkResponse.from(updated);
    }

    /**
     * Removes a mark.
     *
     * @param id the mark to delete, as a canonical UUID string
     */
    @DeleteMapping("/api/marks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        markService.delete(MarkId.of(id), currentUser.userId(), currentUser.scope());
    }
}
