package com.drones.vision.api.controller;

import com.drones.vision.api.dto.GeofenceZoneRequest;
import com.drones.vision.api.dto.GeofenceZoneResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.flight.application.geofence.GeofenceService;
import com.drones.vision.flight.domain.model.ZoneId;
import com.drones.vision.platform.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for geofence zone CRUD (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract).
 *
 * <p>Constructor-injected with {@link GeofenceService} and {@link CurrentUser}.
 *
 * <h2>Authority (docs/plans/done/LIVE-SCOPE-PLAN.md §2.2, W5) — geofences are safety-relevant</h2>
 * A geofence zone is a no-fly boundary; an unauthorized edit here is not an information leak the way
 * an unscoped read elsewhere might be, it is a flight-safety event — a keep-out zone silently
 * widened or a keep-in boundary silently relaxed changes what is safe to fly, for every asset, not
 * just the editor's own. The plan's §2.2 table gates a write on {@code canManage()} for a
 * zone scoped to one asset/group, falling back to the deployment-global {@code canAdminister()}
 * "if the model has such a thing" for a zone bound to no single asset. It does not: {@link
 * com.drones.vision.flight.domain.model.GeofenceZone} carries no asset/group field at all — {@link
 * GeofenceService}'s own javadoc states plainly that zones are "global reference data — no
 * ownership, no per-user scoping, no audit trail." Every zone in this codebase is therefore the
 * plan's "global zone" case, with no narrower one to fall back from, so {@link #create}/{@link
 * #update}/{@link #delete} all require {@link
 * com.drones.vision.platform.Authority#mayAdminister() authority().mayAdminister()}
 * (docs/plans/active/AUTH-ROLES-PLAN.md wave B6, superseding the bare {@code
 * VisibilityScope#canAdminister()} check this gate used before) uniformly — a MANAGER's {@code
 * mayManageOrg()} authority over their own group's assets does not extend to a boundary every
 * group's aircraft must obey.
 *
 * <p>{@link #list} carries no scope check and is marked {@link OpenByDesign} rather than filtered:
 * since no zone has an owning asset/group to filter by, "the zones the caller may see" is every
 * zone, for every scope — the same "deployment-wide reference data" reasoning {@code
 * CategoryController#list} already uses, sharpened here by a safety argument rather than weakened by
 * one: a PILOT who cannot see a keep-out zone because it "isn't theirs" is a PILOT who can fly into
 * it without warning, which is the opposite of what this endpoint exists to prevent.
 *
 * <p>Error mapping is entirely {@link GeofenceService}'s/{@link
 * com.drones.vision.flight.application.geofence.GeofenceZoneSpec}'s own exceptions surfacing through {@link
 * ApiExceptionHandler}, no controller-side translation needed: {@link
 * java.util.NoSuchElementException} (unknown zone id on update/delete) → 404; {@link
 * IllegalArgumentException} (a malformed zone-id UUID, an unrecognized {@code kind}, or a
 * polygon with fewer than 3 vertices) → 400 — the same idiom every other controller in this
 * module already follows.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class GeofenceController {

    private final GeofenceService geofenceService;
    private final CurrentUser currentUser;

    public GeofenceController(GeofenceService geofenceService, CurrentUser currentUser) {
        this.geofenceService = Objects.requireNonNull(geofenceService, "geofenceService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every known zone.
     *
     * @return every zone, sorted by name (see {@link GeofenceService#zones()})
     */
    @OpenByDesign(reason = "Deployment-wide reference data with no per-asset/per-group field to filter "
            + "by (GeofenceZone has no ownership at all) -- and, unlike most global reference data, "
            + "hiding a no-fly zone from any role would itself be the safety hole, not prevent one.")
    @GetMapping("/api/geofences")
    public List<GeofenceZoneResponse> list() {
        return geofenceService.zones().stream().map(GeofenceZoneResponse::from).toList();
    }

    /**
     * Creates a new zone.
     *
     * @param request the zone to create
     * @return the created zone
     */
    @PostMapping("/api/geofences")
    @ResponseStatus(HttpStatus.CREATED)
    public GeofenceZoneResponse create(@RequestBody GeofenceZoneRequest request) {
        requireAdminister();
        return GeofenceZoneResponse.from(geofenceService.create(request.toSpec()));
    }

    /**
     * Replaces an existing zone's fields wholesale (the wire contract's update request carries the
     * same shape as create).
     *
     * @param id      the zone id, as a canonical UUID string
     * @param request the replacement fields
     * @return the updated zone
     */
    @PutMapping("/api/geofences/{id}")
    public GeofenceZoneResponse update(@PathVariable String id, @RequestBody GeofenceZoneRequest request) {
        ZoneId zoneId = ZoneId.of(id);
        // Parse/validate the body (a malformed kind/polygon is a 400) before the authority 403, so a
        // bad request never depends on the caller's scope -- same ordering AssetController#update uses.
        var spec = request.toSpec();
        requireAdminister();
        return GeofenceZoneResponse.from(geofenceService.update(zoneId, spec));
    }

    /**
     * Deletes a zone.
     *
     * @param id the zone id, as a canonical UUID string
     */
    @DeleteMapping("/api/geofences/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        ZoneId zoneId = ZoneId.of(id);
        requireAdminister();
        geofenceService.delete(zoneId);
    }

    /**
     * Guards every write: a no-fly zone is deployment-global safety data (see class javadoc), so
     * authoring one requires {@link com.drones.vision.platform.Authority#mayAdminister()} rather
     * than the group-scoped {@code mayManageOrg()}/{@code mayManageFleet()} this module's other
     * controllers use for group-owned writes (docs/plans/active/AUTH-ROLES-PLAN.md wave B6,
     * superseding the bare {@code VisibilityScope#canAdminister()} check this gate used before).
     */
    private void requireAdminister() {
        if (!currentUser.authority().mayAdminister()) {
            throw new AccessDeniedException("Not permitted to manage geofence zones");
        }
    }
}
