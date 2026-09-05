package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AttachDiscoveryCandidateRequest;
import com.drones.vision.api.dto.DiscoveryCandidateResponse;
import com.drones.vision.api.dto.DiscoveryInboxResponse;
import com.drones.vision.api.dto.DiscoverySourceResponse;
import com.drones.vision.api.dto.RegisterDiscoveryCandidateRequest;
import com.drones.vision.api.dto.RegisterDiscoveryCandidateResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for the discovery inbox (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * &sect;3 P2, &sect;11, Z2c) — the persisted, deduplicated "found devices" list a
 * {@code DiscoveryInboxRunner} sweep ({@code vision-app}'s own wiring) and/or a manual {@link
 * DiscoveryController#scan} feed, and an operator clicks through.
 *
 * <p>Constructor-injected with {@link DiscoveryInboxService}, {@link DiscoveryService} (read-only —
 * {@link #list} reads its {@link DiscoveryService#health()} only, never calls {@link
 * DiscoveryService#scan}, which stays {@link DiscoveryController}'s own verb) and {@link
 * CurrentUser}.
 *
 * <h2>Authorization</h2>
 * {@link #list} is gated on {@link com.drones.vision.platform.Authority#mayManageOrg()
 * authority().mayManageOrg()} directly in this controller (docs/plans/active/AUTH-ROLES-PLAN.md wave
 * B6, superseding the bare {@code VisibilityScope#canManageOrg()} check this gate used before) — the
 * same org-level read gate {@link AuditController#list} already applies — per {@link
 * DiscoveryInboxService#candidates()}'s own javadoc: a not-yet-registered candidate has no {@code
 * Ownership} for a per-instance visibility check to authorise against, so the coarser org-wide gate
 * is the only one available. {@link #register} passes {@code authority()}/{@code ownership} straight
 * through to {@link DiscoveryInboxService#register}, which performs its own {@code mayManageOrg()} +
 * {@code includesGroup} checks (see that method's own javadoc) — the same "service throws, controller
 * does not duplicate the check" shape {@code GroupAdminController#create} already follows. {@link
 * #dismiss} has no scope parameter on the service side (dismissing an inbox row commits to no
 * resource an {@code Ownership} could describe), so this controller gates it explicitly, the same
 * {@code !authority().mayManageOrg()} pattern {@link AssetController#create}/{@link
 * CategoryController#create} both use. Every mutation is attributed to {@link
 * CurrentUser#userId()}. With auth off (the default) the dev principal's authority is {@link
 * com.drones.vision.platform.Authority#full()}, so every gate below passes and behavior is
 * unchanged.
 *
 * <h2>Live updates</h2>
 * Every mutating verb here (report/dismiss/register/attach/restore) also announces a {@code
 * discovery} SSE delta (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4) — transparently,
 * via {@code vision-app}'s {@code LiveUpdateDiscoveryInboxService} decorator around {@link
 * #discoveryInboxService} when {@code vision.live.enabled}/{@code vision.discovery.live.enabled}
 * are both on (both default {@code true}); this controller itself has no notion of the topic.
 * {@link #list} stays a cheap, indexed, idempotent full-list read a client polls or seeds its state
 * from on first load.
 */
@RestController
public class DiscoveryInboxController {

    private final DiscoveryInboxService discoveryInboxService;
    private final DiscoveryService discoveryService;
    private final CurrentUser currentUser;

    public DiscoveryInboxController(DiscoveryInboxService discoveryInboxService, DiscoveryService discoveryService,
                                     CurrentUser currentUser) {
        this.discoveryInboxService =
                Objects.requireNonNull(discoveryInboxService, "discoveryInboxService must not be null");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists every candidate in the inbox, alongside every discovery mechanism's own reachability
     * (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2, A3) — {@code sources}, so a client can tell
     * "this source is unreachable" apart from "reachable, nothing found" instead of both collapsing
     * into an empty candidate list.
     *
     * @return the candidates, mapped to their wire representation, plus one {@link
     *         DiscoverySourceResponse} per registered discovery mechanism
     * @throws AccessDeniedException if the caller's scope may not manage the organization (403)
     */
    @GetMapping("/api/discovery/inbox")
    public DiscoveryInboxResponse list() {
        if (!currentUser.authority().mayManageOrg()) {
            throw new AccessDeniedException("Not permitted to view the discovery inbox");
        }
        var candidates = discoveryInboxService.candidates().stream().map(DiscoveryCandidateResponse::from).toList();
        var sources = discoveryService.health().stream().map(DiscoverySourceResponse::from).toList();
        return new DiscoveryInboxResponse(candidates, sources);
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
                request.toCommand(currentUser.ownership()), currentUser.authority(), currentUser.userId());
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
        if (!currentUser.authority().mayManageOrg()) {
            throw new AccessDeniedException("Not permitted to dismiss a discovery candidate");
        }
        return DiscoveryCandidateResponse.from(
                discoveryInboxService.dismiss(DiscoveryCandidateId.of(id), currentUser.userId()));
    }

    /**
     * Attaches a candidate onto an existing asset — the atomic twin of {@link #register}, for the
     * case an operator already has an asset in mind (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
     * &sect;3.2 C1). No explicit gate here: {@link DiscoveryInboxService#attach} performs its own
     * {@code canManageOrg()} check and its own scoped 404 (never 403) for an unknown-or-out-of-scope
     * {@code assetId} — the same "service throws, controller does not duplicate the check" shape
     * {@link #register} already follows.
     *
     * @param id      the candidate to attach, as a canonical UUID string
     * @param request the existing asset to attach it to
     * @return the attached candidate, now {@code REGISTERED} to {@code request.assetId()}
     * @throws java.util.NoSuchElementException                                              if no
     *         candidate has that id, or {@code assetId} is unknown or outside scope (404)
     * @throws AccessDeniedException                                                         if the
     *         caller's scope may not manage the organization (403)
     * @throws com.drones.vision.warehouse.application.discovery.DiscoveryCandidateAlreadyRegisteredException
     *         if the candidate is already {@code REGISTERED} to a different asset (422 via {@link
     *         ApiExceptionHandler})
     * @throws IllegalStateException                                                         if the
     *         candidate has no suggested stream, or its stream duplicates an already-registered
     *         device (409)
     */
    @PostMapping("/api/discovery/inbox/{id}/attach")
    public DiscoveryCandidateResponse attach(@PathVariable String id,
                                              @RequestBody AttachDiscoveryCandidateRequest request) {
        return DiscoveryCandidateResponse.from(discoveryInboxService.attach(DiscoveryCandidateId.of(id),
                AssetId.of(request.assetId()), currentUser.scope(), currentUser.userId()));
    }

    /**
     * Reopens a candidate — undoes a {@link #dismiss}, or manually recovers a stale {@code
     * REGISTERED} candidate without waiting for the next sweep's auto-reopen (docs/plans/active/
     * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C5). Gated explicitly, mirroring {@link #dismiss}: {@link
     * DiscoveryInboxService#restore} takes no {@code VisibilityScope} (a candidate carries no
     * {@code Ownership} for a per-instance check to authorise against).
     *
     * @param id the candidate to restore, as a canonical UUID string
     * @return the restored candidate — status {@code NEW}, {@code registeredAssetId} cleared
     * @throws java.util.NoSuchElementException if no candidate has that id (404 via {@link ApiExceptionHandler})
     * @throws AccessDeniedException            if the caller's scope may not manage the organization (403)
     */
    @PostMapping("/api/discovery/inbox/{id}/restore")
    public DiscoveryCandidateResponse restore(@PathVariable String id) {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to restore a discovery candidate");
        }
        return DiscoveryCandidateResponse.from(
                discoveryInboxService.restore(DiscoveryCandidateId.of(id), currentUser.userId()));
    }
}
