package com.drones.vision.api;

import com.drones.vision.api.dto.AuditEntryResponse;
import com.drones.vision.application.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for a user's own activity feed (docs/U-SCOPE-PLAN.md, U-e slice 2, feature
 * 7): {@code GET /api/me/activity} — the audit entries the acting user themselves made, newest
 * first.
 *
 * <p>Constructor-injected with {@link ActivityService} and {@link CurrentUser} — the "who" is the
 * caller's own id, never a path parameter, so a user can only ever read their own activity. Reuses
 * {@link AuditEntryResponse}, the same DTO the asset-history audit surface already returns.
 */
@RestController
public class ActivityController {

    /** Default page size when {@code limit} is absent. */
    static final int DEFAULT_LIMIT = 50;

    /** Hard cap so a caller cannot ask for an unbounded page. */
    static final int MAX_LIMIT = 500;

    private final ActivityService activityService;
    private final CurrentUser currentUser;

    public ActivityController(ActivityService activityService, CurrentUser currentUser) {
        this.activityService = Objects.requireNonNull(activityService, "activityService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * The acting user's own most-recent audit entries, newest first.
     *
     * @param limit maximum entries to return; defaults to {@value #DEFAULT_LIMIT}, capped at
     *              {@value #MAX_LIMIT}, floored at 1 (a non-positive request is clamped up rather
     *              than rejected — an activity feed has no meaningful empty-page error)
     * @return the caller's activity
     */
    @GetMapping("/api/me/activity")
    public List<AuditEntryResponse> myActivity(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int effective = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return activityService.myActivity(currentUser.userId(), effective).stream()
                .map(AuditEntryResponse::from)
                .toList();
    }
}
