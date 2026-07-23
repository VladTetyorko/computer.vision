package com.drones.vision.api;

import com.drones.vision.api.dto.FleetSummaryResponse;
import com.drones.vision.application.FleetSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for the manager dashboard's aggregated fleet read (docs/MVP3-PLAN.md C-a).
 *
 * <p>Constructor-injected with {@link FleetSummaryService} only — a single, focused use-case
 * dependency, the same one-collaborator shape {@link EventController}/{@link
 * UsageTimelineController} already establish for a controller with exactly one job.
 */
@RestController
public class FleetController {

    private final FleetSummaryService fleetSummaryService;

    public FleetController(FleetSummaryService fleetSummaryService) {
        this.fleetSummaryService = Objects.requireNonNull(fleetSummaryService, "fleetSummaryService must not be null");
    }

    /**
     * Summarizes the fleet: per-category counts plus a capped, per-asset attention list
     * (docs/MVP3-PLAN.md C-a) — one poll for everything the Command page's attention queue and
     * warehouse-readiness tiles need.
     *
     * @param includeArchived whether to include soft-deleted assets; defaults to {@code false}
     *                        (only non-deleted assets), mirroring every other list endpoint's
     *                        default in this codebase
     * @return the aggregated summary
     */
    @GetMapping("/api/fleet/summary")
    public FleetSummaryResponse summary(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return FleetSummaryResponse.from(fleetSummaryService.summary(includeArchived));
    }
}
