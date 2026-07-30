package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.FlightCommandPort;

/**
 * Commands an asset's aircraft (docs/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it home"). One
 * interface, one implementation ({@link DefaultFlightCommandService}), mirroring every other
 * service in this package.
 *
 * <p>This is the application-layer half of the one deliberate place the platform commands an
 * aircraft rather than only observing it — see {@link FlightCommandPort}'s own javadoc for the
 * doctrine this breaks and how narrowly. No generic command surface exists here either: exactly
 * one operation, {@link #returnToHome}.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface FlightCommandService {

    /**
     * Commands {@code assetId}'s aircraft to return to home (return-to-launch), gated by the acting
     * user's visibility scope (docs/U-SCOPE-PLAN.md, U-e slice 2, feature 3).
     *
     * <p>The command is refused up front with {@link AccessDeniedException} (mapped to 403 by
     * vision-api) when {@code scope} does not include the asset — an honest "you may not command
     * this," distinct from an unknown-asset 404. An {@link VisibilityScope#unbounded()} scope
     * (ADMIN / auth-off) includes every asset, so behavior is unchanged from before scoping.
     *
     * @param assetId the asset to command
     * @param actor   the user performing the command, for the audit trail
     * @param scope   the acting user's visibility scope
     * @return {@link CommandResult#ACCEPTED} once the aircraft's own acknowledgement confirms it,
     *         or {@link CommandResult#NO_ACK} if none arrived within the port's timeout — UDP is
     *         lossy, the command may still have landed
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown
     * @throws AccessDeniedException            if the asset is outside {@code scope}
     * @throws IllegalStateException            if the asset has no active device the configured
     *                                           {@link FlightCommandPort} can command, or the
     *                                           command attempt itself was refused (either by the
     *                                           port up front — unsupported device/firmware — or
     *                                           by the aircraft's own acknowledgement); see {@link
     *                                           DefaultFlightCommandService} for why both surface
     *                                           as this one exception type
     */
    CommandResult returnToHome(AssetId assetId, UserId actor, VisibilityScope scope);
}
