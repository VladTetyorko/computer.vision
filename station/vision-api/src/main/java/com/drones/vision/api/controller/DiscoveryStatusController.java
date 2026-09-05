package com.drones.vision.api.controller;

import com.drones.vision.api.dto.DiscoverySourceResponse;
import com.drones.vision.api.dto.DiscoveryStatusResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.DiscoveryStatusFacts;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Driving REST adapter for {@code GET /api/discovery/status} (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C2) — "the most important endpoint in the plan": one
 * screen answering whether zero-config onboarding is actually working, without an operator having
 * to correlate the discovery inbox, {@code /api/system/network}, and the MAVLink lobby's own logs
 * by hand.
 *
 * <p>Constructor-injected with {@link DiscoveryService} (for {@link #sources}, the same {@link
 * DiscoveryService#health()} read {@link DiscoveryInboxController#list} already uses) and a plain
 * {@link Supplier}{@code <}{@link DiscoveryStatusFacts}{@code >} — see that record's own javadoc
 * for why the sweep timing/telemetry-intake/video-intake facts cross the module boundary this way
 * rather than as a constructor dependency on {@code adapter-mavlink} or {@code vision-app}
 * directly (both forbidden by the dependency rule).
 *
 * <h2>Authorization</h2>
 * Gated on {@link com.drones.vision.platform.Authority#mayManageOrg() authority().mayManageOrg()}
 * directly in this controller, the same org-level read gate {@link DiscoveryInboxController#list}
 * already applies — this endpoint reports process-wide intake facts with no per-instance {@code
 * Ownership} to authorise against.
 */
@RestController
public class DiscoveryStatusController {

    private final DiscoveryService discoveryService;
    private final Supplier<DiscoveryStatusFacts> statusFacts;
    private final CurrentUser currentUser;

    public DiscoveryStatusController(DiscoveryService discoveryService, Supplier<DiscoveryStatusFacts> statusFacts,
                                      CurrentUser currentUser) {
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService must not be null");
        this.statusFacts = Objects.requireNonNull(statusFacts, "statusFacts must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * @return sweep timing, telemetry/video intake facts, and every registered discovery
     *         mechanism's own reachability
     * @throws AccessDeniedException if the caller's scope may not manage the organization (403)
     */
    @GetMapping("/api/discovery/status")
    public DiscoveryStatusResponse status() {
        if (!currentUser.authority().mayManageOrg()) {
            throw new AccessDeniedException("Not permitted to view discovery status");
        }
        DiscoveryStatusFacts facts = statusFacts.get();
        var sources = discoveryService.health().stream().map(DiscoverySourceResponse::from).toList();
        return new DiscoveryStatusResponse(facts.sweepSeconds(), facts.lastSweepAt(), facts.telemetryIntake(),
                facts.videoIntake(), sources);
    }
}
