package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.api.dto.CvTrackersResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the tracker-engine roster (docs/TRACKING-PLAN.md §4.F's frozen wire
 * contract) — the engine picker in the Fly cockpit's Tracking section, filtered client-side to the
 * engines whose {@code modes} include the currently-selected tracking mode.
 *
 * <p>Constructor-injected with the roster itself, a plain {@code List<CvTrackerResponse>} bean
 * rather than a use-case port — deliberately the exact shape {@link CvModelsController} already
 * has, because this is the same kind of thing: a roster that changes at <b>deploy</b> time, not
 * runtime (docs/CV-CONTROL-PLAN.md §D's frozen decision, mirrored for trackers by
 * docs/TRACKING-PLAN.md §4.F). {@code vision-app}'s {@code TrackingWiring#cvTrackerRoster} supplies
 * the content.
 *
 * <p><b>Deliberately not an RPC to cv-service.</b> docs/TRACKING-ORCHESTRATION.md's risk R11 asked
 * exactly this question — a static list can drift from what cv-service can actually construct — and
 * answered it: do not grow the wire contract to solve a display problem. cv-service probes engine
 * constructibility at startup and logs the roster it really got, and every {@code DetectionResponse}
 * reports {@code tracker_engine_id}, the engine that <b>actually served</b> that frame; the UI shows
 * that, not the requested one. This endpoint only has to name what a deployment ships.
 *
 * <p>This endpoint never errors — the roster bean always carries at least the built-ins.
 *
 * <p>A separate controller rather than a second method on {@link CvModelsController} (which
 * docs/TRACKING-PLAN.md §4.F allowed): the two rosters share no collaborator, and folding them would
 * have made one class own two unrelated config-backed lists to save a file.
 */
@RestController
public class CvTrackersController {

    private final List<CvTrackerResponse> cvTrackerRoster;

    public CvTrackersController(List<CvTrackerResponse> cvTrackerRoster) {
        this.cvTrackerRoster =
                List.copyOf(Objects.requireNonNull(cvTrackerRoster, "cvTrackerRoster must not be null"));
    }

    /**
     * The tracker-engine roster, in display order.
     *
     * @return the roster wrapped per the frozen wire contract
     */
    @GetMapping("/api/cv/trackers")
    public CvTrackersResponse trackers() {
        return new CvTrackersResponse(cvTrackerRoster);
    }
}
