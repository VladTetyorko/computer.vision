package com.drones.vision.api.controller;

import com.drones.vision.api.dto.FleetSummaryResponse;
import com.drones.vision.application.fleet.FleetSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the manager dashboard's aggregated fleet read (docs/plans/done/MVP3-PLAN.md C-a).
 *
 * <p>Constructor-injected with {@link FleetSummaryService} and {@link CurrentUser} — the summary is
 * scoped to what the acting user may see (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1), so the
 * per-category counts and attention list a manager sees cover only their own group subtree. With
 * auth off the scope is unbounded, so this returns the whole fleet exactly as before scoping.
 */
@RestController
public class FleetController {

    private final FleetSummaryService fleetSummaryService;
    private final CurrentUser currentUser;

    public FleetController(FleetSummaryService fleetSummaryService, CurrentUser currentUser) {
        this.fleetSummaryService = Objects.requireNonNull(fleetSummaryService, "fleetSummaryService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Summarizes the fleet: per-category counts plus a capped, per-asset attention list
     * (docs/plans/done/MVP3-PLAN.md C-a) — one poll for everything the Command page's attention queue and
     * warehouse-readiness tiles need.
     *
     * @param includeArchived whether to include soft-deleted assets; defaults to {@code false}
     *                        (only non-deleted assets), mirroring every other list endpoint's
     *                        default in this codebase
     * @return the aggregated summary
     */
    @GetMapping("/api/fleet/summary")
    public FleetSummaryResponse summary(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return FleetSummaryResponse.from(fleetSummaryService.summary(currentUser.scope(), includeArchived));
    }
}
