package com.drones.vision.api;

import com.drones.vision.api.dto.ReturnHomeResponse;
import com.drones.vision.application.FlightCommandService;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CommandResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for the guarded return-to-home command (docs/DRONE-INFRA-PLAN.md I-e, Stage
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
 * {@code 202} with {@link ReturnHomeResponse#result()} {@code "ACCEPTED"}/{@code "NO_ACK"} on a
 * sent command; {@code 404} for an unknown asset ({@link java.util.NoSuchElementException}, the
 * same mapping every other asset-scoped endpoint uses); {@code 409} with the failure message for
 * "not commandable" or "the aircraft refused" ({@link IllegalStateException} — see {@link
 * FlightCommandService} for why both outcomes surface as this one exception type); {@code 403}
 * when the asset exists but is outside the caller's {@link CurrentUser#scope()}
 * ({@link com.drones.vision.application.AccessDeniedException}, mapped by {@link
 * ApiExceptionHandler} — docs/U-SCOPE-PLAN.md, feature 3). With auth off the scope is unbounded, so
 * this endpoint behaves exactly as before scoping.
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
}
