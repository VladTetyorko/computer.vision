package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ControlCatalogResponse;
import com.drones.vision.api.dto.ControlProfileResponse;
import com.drones.vision.api.dto.CreateControlProfileRequest;
import com.drones.vision.api.dto.UpdateControlProfileRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.support.AuxFunctionCatalog;
import com.drones.vision.flight.application.ControlProfileService;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Driving REST adapter for controller layouts — what each stick, button and switch of an operator's
 * transmitter does (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C5, decisions C6/C8).
 *
 * <h2>Ownership is the gate here, not visibility scope</h2>
 * Every endpoint below is annotated {@link OpenByDesign} not because it is unguarded but because the
 * guard is a different one from the {@code VisibilityScope} that {@code EndpointAuthorizationTest}
 * looks for: a control profile describes the hardware in one person's hands, so it is gated by
 * <em>owner</em> — the {@link CurrentUser#userId()} taken from the token and passed to {@link
 * ControlProfileService}, which refuses another operator's profile with {@link
 * com.drones.vision.platform.AccessDeniedException} (→ 403). Scoping it by asset visibility would be
 * wrong in both directions: two pilots may share every aircraft on the field and still have
 * completely different transmitters, and a pilot who can see no assets at all must still be able to
 * set their own sticks up on the bench.
 *
 * <h2>Status codes (frozen wire contract)</h2>
 * {@code 200} on the reads and on {@code PUT}; {@code 201} on create; {@code 204} on activate and
 * delete; {@code 400} for a malformed id, an unknown enum name, a blank name, or a layout that
 * violates its own invariants — one control bound twice, one RC channel driven twice, a built-in id
 * where a saved one was required ({@link IllegalArgumentException}); {@code 403} for another
 * operator's profile; {@code 404} for an id nobody saved ({@link java.util.NoSuchElementException}).
 * All of them arrive through {@link ApiExceptionHandler} without controller-side translation.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on the context modules — never on an adapter.
 */
@RestController
@OpenByDesign(reason = "Gated by profile ownership (CurrentUser.userId(), enforced in "
        + "ControlProfileService) rather than by VisibilityScope: a controller layout describes one "
        + "person's transmitter, not an asset, and an operator with no visible assets must still be "
        + "able to configure their own sticks. See this class's javadoc.")
public class ControlProfileController {

    private final ControlProfileService controlProfileService;
    private final CurrentUser currentUser;
    private final AuxFunctionCatalog auxFunctionCatalog;

    public ControlProfileController(ControlProfileService controlProfileService, CurrentUser currentUser,
                                    AuxFunctionCatalog auxFunctionCatalog) {
        this.controlProfileService =
                Objects.requireNonNull(controlProfileService, "controlProfileService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.auxFunctionCatalog = Objects.requireNonNull(auxFunctionCatalog, "auxFunctionCatalog must not be null");
    }

    /**
     * Every layout available to the caller: their saved profiles newest-edit-first, followed by the
     * platform's built-ins.
     *
     * <p>A built-in is reported {@code active} exactly when the caller has activated no saved
     * profile for its vehicle kind — which is the same question {@link
     * ControlProfileService#activeFor} answers at engage time, so the list shows what would actually
     * fly rather than what is merely stored.
     *
     * @return the caller's layouts and the built-ins behind them
     */
    @GetMapping("/api/control-profiles")
    public List<ControlProfileResponse> list() {
        List<OwnedControlProfile> saved = controlProfileService.saved(currentUser.userId());
        Set<VehicleKind> covered = EnumSet.noneOf(VehicleKind.class);
        for (OwnedControlProfile profile : saved) {
            if (profile.active()) {
                covered.add(profile.kind());
            }
        }
        List<ControlProfileResponse> response = new ArrayList<>(saved.stream()
                .map(ControlProfileResponse::saved).toList());
        for (ControlProfile builtIn : controlProfileService.builtIns()) {
            response.add(ControlProfileResponse.builtIn(builtIn, !covered.contains(builtIn.kind())));
        }
        return response;
    }

    /**
     * Everything a setup page may offer: vehicle kinds, input kinds, switch positions, channel
     * functions, bindable actions and this deployment's aux-function menu.
     *
     * @return the catalogue
     */
    @GetMapping("/api/control-profiles/catalog")
    public ControlCatalogResponse catalog() {
        return ControlCatalogResponse.of(auxFunctionCatalog);
    }

    /**
     * Starts a new layout for the caller, copied from the built-in for the named vehicle kind. Not
     * activated — {@link #activate} is its own deliberate gesture.
     *
     * @param request the vehicle kind to copy and the name to give the copy
     * @return the new profile
     */
    @PostMapping("/api/control-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public ControlProfileResponse create(@RequestBody CreateControlProfileRequest request) {
        return ControlProfileResponse.saved(
                controlProfileService.create(owner(), request.toKind(), request.name()));
    }

    /**
     * Replaces one saved layout's name and both of its binding maps.
     *
     * @param id      the profile to update, as a canonical UUID string
     * @param request the new name and bindings
     * @return the updated profile
     */
    @PutMapping("/api/control-profiles/{id}")
    public ControlProfileResponse update(@PathVariable String id, @RequestBody UpdateControlProfileRequest request) {
        return ControlProfileResponse.saved(controlProfileService.update(owner(), ControlProfileId.of(id),
                request.name(), request.toChannelMap(), request.toActionMap(), request.toView()));
    }

    /**
     * Makes one layout the caller's active one for its vehicle kind, replacing whichever of theirs
     * held that place before.
     *
     * @param id the profile to activate, as a canonical UUID string
     */
    @PostMapping("/api/control-profiles/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable String id) {
        controlProfileService.activate(owner(), ControlProfileId.of(id));
    }

    /**
     * Deletes one saved layout. Deleting the active one simply hands its vehicle kind back to the
     * built-in, which is why this needs no confirmation of its own: nothing becomes unflyable.
     *
     * @param id the profile to delete, as a canonical UUID string
     */
    @DeleteMapping("/api/control-profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        controlProfileService.delete(owner(), ControlProfileId.of(id));
    }

    private UserId owner() {
        return currentUser.userId();
    }
}
