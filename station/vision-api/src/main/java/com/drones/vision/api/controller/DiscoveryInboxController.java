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
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.application.pairing.PairingService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import com.drones.vision.warehouse.domain.model.Pairing;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * Driving REST adapter for the discovery inbox (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * &sect;3 P2, &sect;11, Z2c) — the persisted, deduplicated "found devices" list a
 * {@code DiscoveryInboxRunner} sweep ({@code vision-app}'s own wiring) and/or a manual {@link
 * DiscoveryController#scan} feed, and an operator clicks through.
 *
 * <p>Constructor-injected with {@link DiscoveryInboxService}, {@link DiscoveryService} (read-only —
 * {@link #list} reads its {@link DiscoveryService#health()} only, never calls {@link
 * DiscoveryService#scan}, which stays {@link DiscoveryController}'s own verb), {@link PairingService}
 * and {@link AssetService} (both only for "adopt is one motion" below), and {@link CurrentUser} — five
 * collaborators, at the java-clean-code ceiling.
 *
 * <h2>Adopt is one motion (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling 3)</h2>
 * {@link #register} and {@link #attach}, when the candidate they just turned into a device is a
 * {@code mavlink} TELEMETRY device, call {@link PairingService#pair} in the same call — composed
 * here rather than inside {@code DefaultDiscoveryInboxService} because that is exactly where the
 * plan freezes it, and because there is no real cross-repository database transaction to join in
 * this codebase's plain-Hibernate adapters (each JPA write commits on its own) — "same transaction"
 * means "the same synchronous request," which composing two sibling application services at this
 * layer already gives. {@link #register} reads the candidate's {@link DiscoveredDevice} before
 * calling {@link DiscoveryInboxService#register} (needed to know the heard sysid and protocol) and
 * takes the resulting asset's one device (see {@code DefaultDiscoveryInboxService#toAssetSpec} —
 * always exactly one for this flow); {@link #attach} resolves the just-created {@link DeviceId}
 * afterward via {@link AssetService#findDuplicateDevice}, since {@link DiscoveryInboxService#attach}
 * returns the candidate, not the device it created. Neither path pairs a non-{@code mavlink}
 * candidate at all — {@link RegisterDiscoveryCandidateResponse#sysidPushRequired()}/{@link
 * DiscoveryCandidateResponse#sysidPushRequired()} are simply absent then.
 *
 * <h2>Authorization</h2>
 * {@link #list} is gated on {@link com.drones.vision.platform.Authority#mayManageOrg()
 * authority().mayManageOrg()} directly in this controller (docs/plans/active/AUTH-ROLES-PLAN.md wave
 * B6, superseding the bare {@code Authority#mayManageOrg()} check this gate used before) — the
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

    /**
     * The one protocol in this codebase that is telemetry and nothing else. Declared locally, as
     * {@code CapabilityParsing}/{@code DefaultDiscoveryInboxService} each declare their own copy —
     * no module here owns a shared constant for it.
     */
    private static final String PROTOCOL_MAVLINK = "mavlink";

    private final DiscoveryInboxService discoveryInboxService;
    private final DiscoveryService discoveryService;
    private final PairingService pairingService;
    private final AssetService assetService;
    private final CurrentUser currentUser;

    public DiscoveryInboxController(DiscoveryInboxService discoveryInboxService, DiscoveryService discoveryService,
                                     PairingService pairingService, AssetService assetService,
                                     CurrentUser currentUser) {
        this.discoveryInboxService =
                Objects.requireNonNull(discoveryInboxService, "discoveryInboxService must not be null");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService must not be null");
        this.pairingService = Objects.requireNonNull(pairingService, "pairingService must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
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
        DiscoveryCandidateId candidateId = DiscoveryCandidateId.of(id);
        DiscoveredDevice discovered = findCandidate(candidateId)
                .map(DiscoveryCandidate::discovered)
                .orElse(null);
        Asset created = discoveryInboxService.register(candidateId,
                request.toCommand(currentUser.ownership()), currentUser.authority(), currentUser.userId());
        AdoptOutcome outcome = discovered == null ? null
                : created.devices().stream().findFirst()
                        .map(deviceId -> adopt(discovered, deviceId))
                        .orElse(null);
        return RegisterDiscoveryCandidateResponse.from(created, sysidPushRequired(outcome), assignedSysid(outcome));
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
     * {@code mayManageOrg()} check and its own scoped 404 (never 403) for an unknown-or-out-of-scope
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
        DiscoveryCandidateId candidateId = DiscoveryCandidateId.of(id);
        DiscoveredDevice discovered = findCandidate(candidateId)
                .map(DiscoveryCandidate::discovered)
                .orElse(null);
        DiscoveryCandidate attached = discoveryInboxService.attach(candidateId,
                AssetId.of(request.assetId()), currentUser.authority(), currentUser.userId());
        // attach() returns the candidate, not the device id it just created -- resolve it back via
        // the same identity match findDuplicateDevice already performs (it now matches the device
        // attach() just registered, since a real duplicate now exists).
        AdoptOutcome outcome = discovered == null || discovered.suggestedStream() == null ? null
                : assetService.findDuplicateDevice(discovered.suggestedStream())
                        .map(match -> adopt(discovered, match.deviceId()))
                        .orElse(null);
        return DiscoveryCandidateResponse.from(attached, sysidPushRequired(outcome), assignedSysid(outcome));
    }

    /**
     * Finds one candidate by id — a linear scan of {@link DiscoveryInboxService#candidates()}, the
     * only lookup the interface exposes; acceptable since the inbox is a small, operator-paced list
     * (dozens of rows), not a hot path.
     */
    private Optional<DiscoveryCandidate> findCandidate(DiscoveryCandidateId id) {
        return discoveryInboxService.candidates().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * "Adopt is one motion": pairs {@code deviceId} when {@code discovered} is a {@code mavlink}
     * TELEMETRY device, and reports whether the confirm screen must show the {@code MAV_SYSID} push
     * step, and which sysid was actually assigned.
     *
     * @return the pairing outcome, or {@code null} when {@code discovered} is not a {@code mavlink}
     *         device (pairing was not attempted at all)
     */
    private AdoptOutcome adopt(DiscoveredDevice discovered, DeviceId deviceId) {
        StreamDescriptor stream = discovered.suggestedStream();
        if (stream == null || !PROTOCOL_MAVLINK.equalsIgnoreCase(stream.protocol().trim())) {
            return null;
        }
        int heardSysid = heardSysidFor(discovered);
        Pairing pairing = pairingService.pair(deviceId, heardSysid, null, currentUser.userId());
        return new AdoptOutcome(pairing.sysid() != heardSysid, pairing.sysid());
    }

    /**
     * {@link #adopt}'s result — the two facts {@link RegisterDiscoveryCandidateResponse}/{@link
     * DiscoveryCandidateResponse} report about a just-completed pairing (their own {@code
     * sysidPushRequired}/{@code assignedSysid} fields, frozen to match {@code
     * station/vision-web}'s {@code core/api/models.ts} L4 mirror). Kept local rather than promoted
     * to a DTO: it never crosses this controller's own boundary.
     */
    private record AdoptOutcome(boolean sysidPushRequired, int assignedSysid) {
    }

    private static Boolean sysidPushRequired(AdoptOutcome outcome) {
        return outcome == null ? null : outcome.sysidPushRequired();
    }

    private static Integer assignedSysid(AdoptOutcome outcome) {
        return outcome != null && outcome.sysidPushRequired() ? outcome.assignedSysid() : null;
    }

    /**
     * The heard sysid a {@code mavlink} candidate carries, read the same lenient way {@link
     * DiscoveryCandidate#identityKeyFor} does — {@link StreamDescriptor#options()}'s {@code "sysid"}
     * key, falling back to {@link DiscoveredDevice#details()}.
     *
     * @return the heard sysid, or {@code 0} if none was observed — {@link PairingService#pair}
     *         treats {@code 0} as out of range and assigns a fresh one instead
     */
    private static int heardSysidFor(DiscoveredDevice discovered) {
        StreamDescriptor stream = discovered.suggestedStream();
        String raw = stream != null ? stream.options().get("sysid") : null;
        if (raw == null) {
            raw = discovered.details().get("sysid");
        }
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        if (!currentUser.authority().mayManageOrg()) {
            throw new AccessDeniedException("Not permitted to restore a discovery candidate");
        }
        return DiscoveryCandidateResponse.from(
                discoveryInboxService.restore(DiscoveryCandidateId.of(id), currentUser.userId()));
    }
}
