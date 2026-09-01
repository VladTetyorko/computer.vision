package com.drones.vision.api.controller;

import com.drones.vision.api.dto.DiscoveryCandidateResponse;
import com.drones.vision.api.dto.RegisterDiscoveryCandidateRequest;
import com.drones.vision.api.dto.RegisterDiscoveryCandidateResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the discovery inbox (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * &sect;3 P2, &sect;11, Z2c) — the persisted, deduplicated "found devices" list a
 * {@code DiscoveryInboxRunner} sweep ({@code vision-app}'s own wiring) and/or a manual {@link
 * DiscoveryController#scan} feed, and an operator clicks through.
 *
 * <p>Constructor-injected with {@link DiscoveryInboxService} and {@link CurrentUser} only —
 * mirrors {@link DiscoveryController}'s own minimal shape.
 *
 * <h2>Authorization</h2>
 * {@link #list} is gated on {@link com.drones.vision.platform.VisibilityScope#canManageOrg()
 * scope().canManageOrg()} directly in this controller — the same org-level read gate {@link
 * AuditController#list} already applies — per {@link DiscoveryInboxService#candidates()}'s own
 * javadoc: a not-yet-registered candidate has no {@code Ownership} for a per-instance visibility
 * check to authorise against, so the coarser org-wide gate is the only one available. {@link
 * #register} passes {@code scope}/{@code ownership} straight through to {@link
 * DiscoveryInboxService#register}, which performs its own {@code canManageOrg()} +
 * {@code includesGroup} checks (see that method's own javadoc) — the same "service throws, controller
 * does not duplicate the check" shape {@code GroupAdminController#create} already follows. {@link
 * #dismiss} has no scope parameter on the service side (dismissing an inbox row commits to no
 * resource an {@code Ownership} could describe), so this controller gates it explicitly, the same
 * {@code !scope().canManageOrg()} pattern {@link AssetController#create}/{@link
 * CategoryController#create} both use. Every mutation is attributed to {@link
 * CurrentUser#userId()}. With auth off (the default) the dev principal's scope is unbounded, so
 * every gate below passes and behavior is unchanged.
 *
 * <h2>Live updates</h2>
 * No SSE topic yet — {@link #list} is a cheap, indexed, idempotent full-list read a client polls
 * (see this module's own {@code MODULE.md} for why this wave shipped poll-only rather than wiring a
 * new {@code LiveUpdateRegistry} topic with no consumer yet in scope).
 */
@RestController
public class DiscoveryInboxController {

    private final DiscoveryInboxService discoveryInboxService;
    private final CurrentUser currentUser;

    public DiscoveryInboxController(DiscoveryInboxService discoveryInboxService, CurrentUser currentUser) {
        this.discoveryInboxService =
                Objects.requireNonNull(discoveryInboxService, "discoveryInboxService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every candidate in the inbox.
     *
     * @return every candidate, mapped to its wire representation
     * @throws AccessDeniedException if the caller's scope may not manage the organization (403)
     */
    @GetMapping("/api/discovery/inbox")
    public List<DiscoveryCandidateResponse> list() {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to view the discovery inbox");
        }
        return discoveryInboxService.candidates().stream().map(DiscoveryCandidateResponse::from).toList();
    }

    /**
     * Registers a candidate as a new asset, delegating to {@link DiscoveryInboxService#register}
     * (which in turn delegates to the existing {@code AssetService#createFromCandidate}).
     *
     * @param id      the candidate to register, as a canonical UUID string
     * @param request the operator's Identify-step overrides
     * @return a pointer at the newly created asset (see {@link RegisterDiscoveryCandidateResponse}
     *         for why this is not the full asset detail view)
     * @throws java.util.NoSuchElementException if no candidate has that id (404 via {@link ApiExceptionHandler})
     * @throws AccessDeniedException            if the caller's scope may not register assets in the
     *                                           requested group (403)
     * @throws IllegalArgumentException         if the requested category does not exist (400)
     * @throws IllegalStateException            if the candidate's device duplicates an
     *                                           already-registered one (409)
     */
    @PostMapping("/api/discovery/inbox/{id}/register")
    public RegisterDiscoveryCandidateResponse register(@PathVariable String id,
                                                         @RequestBody RegisterDiscoveryCandidateRequest request) {
        Asset created = discoveryInboxService.register(DiscoveryCandidateId.of(id),
                request.toCommand(currentUser.ownership()), currentUser.scope(), currentUser.userId());
        return RegisterDiscoveryCandidateResponse.from(created);
    }

    /**
     * Dismisses a candidate — "not now". A later scan hit for the same identity revives it unless
     * it now matches an already-registered device.
     *
     * @param id the candidate to dismiss, as a canonical UUID string
     * @return the dismissed candidate
     * @throws java.util.NoSuchElementException if no candidate has that id (404 via {@link ApiExceptionHandler})
     * @throws AccessDeniedException            if the caller's scope may not manage the organization (403)
     */
    @PostMapping("/api/discovery/inbox/{id}/dismiss")
    public DiscoveryCandidateResponse dismiss(@PathVariable String id) {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to dismiss a discovery candidate");
        }
        return DiscoveryCandidateResponse.from(
                discoveryInboxService.dismiss(DiscoveryCandidateId.of(id), currentUser.userId()));
    }
}
