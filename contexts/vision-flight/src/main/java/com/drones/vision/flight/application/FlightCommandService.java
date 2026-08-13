package com.drones.vision.flight.application;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.flight.domain.model.FlightCapability;
import com.drones.vision.kernel.UserId;
import com.drones.vision.flight.domain.port.FlightCommandPort;
import com.drones.vision.platform.VisibilityScope;

/**
 * Commands an asset's aircraft (docs/plans/active/DRONE-INFRA-PLAN.md I-e — "bring it home", then Stage 2's
 * arm/disarm + mode select). One interface, one implementation
 * ({@link DefaultFlightCommandService}), mirroring every other service in this package.
 *
 * <p>This is the application-layer half of the one deliberate place the platform commands an
 * aircraft rather than only observing it — see {@link FlightCommandPort}'s own javadoc for the
 * doctrine this breaks and how narrowly. There is still no generic command surface here: exactly
 * the capability-gated commands the plan has approved so far ({@link #returnToHome}, {@link
 * #setMode}, {@link #arm}, {@link #disarm}) plus a {@link #capabilities} snapshot a driving adapter
 * drives its own controls from.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface FlightCommandService {

    /**
     * Commands {@code assetId}'s aircraft to return to home (return-to-launch), gated by the acting
     * user's visibility scope (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 3).
     *
     * <p>The command is refused up front with {@link com.drones.vision.platform.AccessDeniedException} (mapped to 403 by
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
     * @throws com.drones.vision.platform.AccessDeniedException            if the asset is outside {@code scope}
     * @throws IllegalStateException            if the asset has no active device the configured
     *                                           {@link FlightCommandPort} can command, or the
     *                                           command attempt itself was refused (either by the
     *                                           port up front — unsupported device/firmware — or
     *                                           by the aircraft's own acknowledgement); see {@link
     *                                           DefaultFlightCommandService} for why both surface
     *                                           as this one exception type
     */
    CommandResult returnToHome(AssetId assetId, UserId actor, VisibilityScope scope);

    /**
     * Commands {@code assetId}'s aircraft into the named flight mode (docs/plans/active/DRONE-INFRA-PLAN.md I-e
     * Stage 2), gated by the acting user's visibility scope exactly like {@link #returnToHome}.
     *
     * <p>The requested mode is validated against the vehicle's own {@link
     * FlightCapability#selectableModes()} <em>before</em> anything is sent, so an unknown mode is a
     * client error ({@link IllegalArgumentException} → 400) cleanly distinct from a not-commandable
     * vehicle ({@link IllegalStateException} → 409). See {@link DefaultFlightCommandService} for the
     * exact 400-vs-409 split.
     *
     * @param assetId  the asset to command
     * @param modeName the target mode name, one of the vehicle's {@link
     *                 FlightCapability#selectableModes()}
     * @param actor    the user performing the command, for the audit trail
     * @param scope    the acting user's visibility scope
     * @return {@link CommandResult#ACCEPTED} or {@link CommandResult#NO_ACK}
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown
     * @throws com.drones.vision.platform.AccessDeniedException            if the asset is outside {@code scope}
     * @throws IllegalArgumentException         if {@code modeName} is not one the vehicle offers
     * @throws IllegalStateException            if the asset has no commandable device, or the
     *                                           command attempt itself was refused
     */
    CommandResult setMode(AssetId assetId, String modeName, UserId actor, VisibilityScope scope);

    /**
     * Commands {@code assetId}'s aircraft to arm — spin up its motors (docs/plans/active/DRONE-INFRA-PLAN.md I-e
     * Stage 2). <b>The highest-danger action this service exposes</b>; the driving adapter gates it
     * with a distinct, higher-friction confirmation than any other command. Scope-gated exactly
     * like {@link #returnToHome}.
     *
     * @param assetId the asset to command
     * @param force   when {@code true}, a forced arm that bypasses the autopilot's pre-arm checks
     * @param actor   the user performing the command, for the audit trail
     * @param scope   the acting user's visibility scope
     * @return {@link CommandResult#ACCEPTED} or {@link CommandResult#NO_ACK}
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown
     * @throws com.drones.vision.platform.AccessDeniedException            if the asset is outside {@code scope}
     * @throws IllegalStateException            if the asset has no commandable device, or the
     *                                           command attempt itself was refused
     */
    CommandResult arm(AssetId assetId, boolean force, UserId actor, VisibilityScope scope);

    /**
     * Commands {@code assetId}'s aircraft to disarm — stop its motors (docs/plans/active/DRONE-INFRA-PLAN.md I-e
     * Stage 2). Scope-gated exactly like {@link #returnToHome}.
     *
     * @param assetId the asset to command
     * @param force   when {@code true}, a forced disarm that bypasses the autopilot's own checks
     *                (the caller owns the crash risk of forcing a disarm mid-flight)
     * @param actor   the user performing the command, for the audit trail
     * @param scope   the acting user's visibility scope
     * @return {@link CommandResult#ACCEPTED} or {@link CommandResult#NO_ACK}
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown
     * @throws com.drones.vision.platform.AccessDeniedException            if the asset is outside {@code scope}
     * @throws IllegalStateException            if the asset has no commandable device, or the
     *                                           command attempt itself was refused
     */
    CommandResult disarm(AssetId assetId, boolean force, UserId actor, VisibilityScope scope);

    /**
     * A best-effort snapshot of what commands {@code assetId}'s aircraft currently accepts, for a
     * driving adapter to decide which controls to show (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2).
     *
     * <p>This is a <em>read</em>, not a command: it is scope-checked the way the scoped asset reads
     * are — an out-of-scope (or unknown) asset yields {@link java.util.NoSuchElementException}
     * (→ 404, hiding existence), <b>not</b> the {@link com.drones.vision.platform.AccessDeniedException} the command methods
     * throw. An in-scope asset with no commandable device at all (e.g. a manager viewing a non-drone
     * asset) simply reports {@link FlightCapability#notCommandable()} rather than throwing. Never
     * audited — reading capabilities sends nothing to any aircraft.
     *
     * @param assetId the asset to describe
     * @param scope   the acting user's visibility scope
     * @return the vehicle's capability snapshot, or {@link FlightCapability#notCommandable()} when
     *         the asset has no active commandable device
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown or outside {@code scope}
     */
    FlightCapability capabilities(AssetId assetId, VisibilityScope scope);
}
