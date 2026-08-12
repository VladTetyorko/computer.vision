package com.drones.vision.flight.application;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;

/**
 * Guarded relay-session control: engages a streaming, ack-less RC-channel-override relay
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md, RC-CONTROL Phase 1) for one asset — the stateful,
 * watchdog-supervised opposite of {@link FlightCommandService}'s request-then-ack one-shots. There
 * is no {@code *UseCase} type or inbound-port package here, matching every other service in this
 * module.
 */
public interface ManualControlService {

    /**
     * Explicit "take control" gesture. Resolves the asset's first active device {@code
     * ManualControlPort#supports} claims, opens a relay link, starts the watchdog, audits {@code
     * ENGAGE}, and returns the live session.
     *
     * @param onWatchdog invoked, exactly once, if the returned session's watchdog later
     *                   auto-releases it for input loss — the session is already released and
     *                   audited by the time this fires
     * @throws com.drones.vision.platform.AccessDeniedException  the asset is outside {@code scope} (audited {@code
     *                                 DENIED:out of scope}) — vision-api maps this to 403
     * @throws IllegalStateException  no active device {@code ManualControlPort} supports, or the
     *                                 one found is not currently reachable — vision-api maps this
     *                                 to a {@code denied} frame; also thrown if a session is
     *                                 already active on this service handle (one engage at a time
     *                                 per handle — see {@code DefaultManualControlService}'s own
     *                                 javadoc for what "handle" means when it is wired as a shared
     *                                 singleton)
     */
    ManualControlSession engage(AssetId assetId, UserId actor, VisibilityScope scope, WatchdogListener onWatchdog);
}
