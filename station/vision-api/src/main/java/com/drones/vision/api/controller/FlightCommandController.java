package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AuxFunctionRequest;
import com.drones.vision.api.dto.FlightCapabilitiesResponse;
import com.drones.vision.api.dto.ForceCommandRequest;
import com.drones.vision.api.dto.ReturnHomeResponse;
import com.drones.vision.api.dto.SetModeRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.flight.application.FlightCommandService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.flight.domain.model.FlightCapability;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the guarded return-to-home command (docs/plans/active/DRONE-INFRA-PLAN.md I-e, Stage
 * 1 — "bring it home"). The one deliberate place this platform commands an aircraft rather than
 * only observing it; see {@link FlightCommandService}'s own javadoc for the doctrine this breaks
 * and how narrowly.
 *
 * <p>Constructor-injected with {@link FlightCommandService} and {@link CurrentUser}, mirroring
 * {@link AssetController}/{@link SimulationController}. Per the hexagonal dependency rule
 * (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on {@code vision-domain}
 * and {@code vision-application} — never on an adapter.
 *
 * <h2>Status codes (frozen wire contract)</h2>
 * The command endpoints ({@code return-home}, {@code mode}, {@code arm}, {@code disarm}) answer
 * {@code 202} with {@link ReturnHomeResponse#result()} {@code "ACCEPTED"}/{@code "NO_ACK"} on a
 * sent command; {@code 404} for an unknown asset ({@link java.util.NoSuchElementException}); {@code
 * 409} with the failure message for "not commandable" or "the aircraft refused" ({@link
 * IllegalStateException} — see {@link FlightCommandService} for why both outcomes surface as this
 * one exception type); {@code 400} for an unknown/blank flight mode ({@link
 * IllegalArgumentException}, validated against the vehicle's capabilities before dispatch —
 * deliberately distinct from the not-commandable 409, see {@code DefaultFlightCommandService}); and
 * {@code 403} when the asset exists but is outside the caller's {@link CurrentUser#scope()}
 * ({@link com.drones.vision.platform.AccessDeniedException}, mapped by {@link
 * ApiExceptionHandler} — docs/plans/done/U-SCOPE-PLAN.md, feature 3).
 *
 * <p>{@code GET /api/assets/{id}/flight-capabilities} is instead a <em>read</em>: {@code 200} with
 * the capability snapshot, and {@code 404} for an unknown <em>or</em> out-of-scope asset (hiding
 * existence, per the scoped-read convention — not the 403 the commands give). With auth off the
 * scope is unbounded, so every endpoint here behaves exactly as before scoping.
 */
@RestController
public class FlightCommandController {

    private final FlightCommandService flightCommandService;
    private final CurrentUser currentUser;

    public FlightCommandController(FlightCommandService flightCommandService, CurrentUser currentUser) {
        this.flightCommandService =
                Objects.requireNonNull(flightCommandService, "flightCommandService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Commands an asset's aircraft to return to home (return-to-launch). No request body.
     *
     * @param id the asset to command, as a canonical UUID string
     * @return the command outcome — {@code ACCEPTED} once the aircraft's own acknowledgement
     *         confirms it, or {@code NO_ACK} if none arrived within the timeout (the command may
     *         still have landed — UDP is lossy)
     */
    @PostMapping("/api/assets/{id}/return-home")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReturnHomeResponse returnHome(@PathVariable String id) {
        CommandResult result = flightCommandService.returnToHome(AssetId.of(id), currentUser.userId(),
                currentUser.scope());
        return ReturnHomeResponse.from(result);
    }

    /**
     * Commands an asset's aircraft into a named flight mode.
     *
     * @param id      the asset to command, as a canonical UUID string
     * @param request the target mode; {@code mode} is required and non-blank (400 otherwise)
     * @return the command outcome ({@code ACCEPTED}/{@code NO_ACK})
     */
    @PostMapping("/api/assets/{id}/mode")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReturnHomeResponse setMode(@PathVariable String id, @RequestBody SetModeRequest request) {
        CommandResult result = flightCommandService.setMode(AssetId.of(id), request.requireMode(),
                currentUser.userId(), currentUser.scope());
        return ReturnHomeResponse.from(result);
    }

    /**
     * Commands an asset's aircraft to arm (spin up its motors). The highest-danger command here.
     *
     * @param id      the asset to command, as a canonical UUID string
     * @param request the optional {@code force} flag; the whole body may be absent ({@code force}
     *                defaults to {@code false})
     * @return the command outcome ({@code ACCEPTED}/{@code NO_ACK})
     */
    @PostMapping("/api/assets/{id}/arm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReturnHomeResponse arm(@PathVariable String id,
                                  @RequestBody(required = false) ForceCommandRequest request) {
        ForceCommandRequest body = request != null ? request : ForceCommandRequest.EMPTY;
        CommandResult result = flightCommandService.arm(AssetId.of(id), body.forceOrDefault(),
                currentUser.userId(), currentUser.scope());
        return ReturnHomeResponse.from(result);
    }

    /**
     * Commands an asset's aircraft to disarm (stop its motors).
     *
     * @param id      the asset to command, as a canonical UUID string
     * @param request the optional {@code force} flag; the whole body may be absent ({@code force}
     *                defaults to {@code false})
     * @return the command outcome ({@code ACCEPTED}/{@code NO_ACK})
     */
    @PostMapping("/api/assets/{id}/disarm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReturnHomeResponse disarm(@PathVariable String id,
                                     @RequestBody(required = false) ForceCommandRequest request) {
        ForceCommandRequest body = request != null ? request : ForceCommandRequest.EMPTY;
        CommandResult result = flightCommandService.disarm(AssetId.of(id), body.forceOrDefault(),
                currentUser.userId(), currentUser.scope());
        return ReturnHomeResponse.from(result);
    }

    /**
     * Force-disarms the asset's aircraft regardless of the autopilot's own checks — QGroundControl's
     * {@code Emergency Stop}, and the same {@code 21196} magic parameter underneath.
     *
     * <p><b>This stops the motors wherever the vehicle is.</b> A multirotor in the air falls. It is
     * a separate endpoint from {@link #disarm} rather than a flag on it precisely so that neither
     * an audit entry nor a UI can confuse the two: {@code disarm} asks the autopilot, this one does
     * not (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.4).
     *
     * @param id the asset to command, as a canonical UUID string
     * @return the command outcome ({@code ACCEPTED}/{@code NO_ACK})
     */
    @PostMapping("/api/assets/{id}/emergency-stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReturnHomeResponse emergencyStop(@PathVariable String id) {
        CommandResult result = flightCommandService.emergencyStop(AssetId.of(id), currentUser.userId(),
                currentUser.scope());
        return ReturnHomeResponse.from(result);
    }

    /**
     * Fires one ArduPilot auxiliary function at one switch level — the generic escape hatch that
     * lets a bound switch reach a feature this platform has no dedicated command for (motor
     * emergency stop, gripper, parachute, camera trigger, RC-override enable), without this module
     * pretending to model each of them (CONTROLLER-SETUP-CONTEXT.md §2.3, decision C5).
     *
     * @param id      the asset to command, as a canonical UUID string
     * @param request the function number and switch level ({@code 0} LOW / {@code 1} MIDDLE /
     *                {@code 2} HIGH); both range-checked before anything is resolved or sent, so a
     *                malformed binding is a 400 that never reaches an aircraft
     * @return the command outcome ({@code ACCEPTED}/{@code NO_ACK})
     */
    @PostMapping("/api/assets/{id}/aux-function")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReturnHomeResponse auxFunction(@PathVariable String id, @RequestBody AuxFunctionRequest request) {
        CommandResult result = flightCommandService.auxFunction(AssetId.of(id), request.requireFunction(),
                request.requireLevel(), currentUser.userId(), currentUser.scope());
        return ReturnHomeResponse.from(result);
    }

    /**
     * A best-effort snapshot of what commands the asset's aircraft currently accepts, for a UI to
     * decide which controls to show. A <em>read</em>: an unknown or out-of-scope asset 404s (hiding
     * existence); an in-scope asset with no commandable device reports everything {@code false}.
     *
     * @param id the asset to describe, as a canonical UUID string
     * @return the capability snapshot
     */
    @GetMapping("/api/assets/{id}/flight-capabilities")
    public FlightCapabilitiesResponse flightCapabilities(@PathVariable String id) {
        FlightCapability capability =
                flightCommandService.capabilities(AssetId.of(id), currentUser.scope());
        return FlightCapabilitiesResponse.from(capability);
    }
}
