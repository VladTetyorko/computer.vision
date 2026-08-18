package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ProbeCandidateRequest;
import com.drones.vision.api.dto.RemediationRequest;
import com.drones.vision.api.dto.RemediationResultResponse;
import com.drones.vision.api.dto.VehicleProfileResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.OnboardingProperties;
import com.drones.vision.api.support.RemediationOrchestrator;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for the onboarding pipeline's PROBE and CONFIGURE stages (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §3.1, §8.1's frozen wire contract). {@code GET
 * /api/assets/{assetId}/readiness}/{@code GET /api/fleet/readiness} (the NEGOTIATE stage) are
 * {@link ReadinessController}'s, not this class's.
 *
 * <p>Constructor-injected with {@link VehicleProfileService} (PROBE) and {@link
 * RemediationOrchestrator} (CONFIGURE's vehicle-side half — see that class's own javadoc for why a
 * dedicated orchestrator exists rather than a single application service). Per the hexagonal
 * dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on {@code
 * vision-domain}/{@code vision-application} types — never on an adapter, never on {@code
 * org.springframework.security} — the acting user comes from {@link CurrentUser}.
 *
 * <h2>Status codes (§8.1, frozen)</h2>
 * <ul>
 *   <li>{@code POST /api/onboarding/probe} — {@code 200} | {@code 409} ({@link IllegalStateException}
 *       — probing disabled ({@code vision.onboarding.probe.enabled=false}) via {@code
 *       NoopVehicleConfigPort}, or an unreachable candidate)</li>
 *   <li>{@code GET /api/assets/{assetId}/profile} — {@code 200} | {@code 404} (unknown, out of
 *       scope, or never probed — {@link java.util.NoSuchElementException}, a scoped read hides all
 *       three identically)</li>
 *   <li>{@code POST /api/assets/{assetId}/probe} — {@code 200} | {@code 403} (audited — {@link
 *       com.drones.vision.platform.AccessDeniedException}) | {@code 404} | {@code 409} (no
 *       probeable device, or disabled)</li>
 *   <li>{@code POST /api/assets/{assetId}/remediate} — {@code 200} | {@code 403} (audited) | {@code
 *       409} (armed, arming unknown, or disabled) — see {@link RemediationOrchestrator} for why a
 *       request that dispatches nothing (e.g. the asset was never probed) answers {@code 200} with
 *       every action {@code UNSUPPORTED} rather than {@code 404}</li>
 * </ul>
 * Every status code above is produced by {@link ApiExceptionHandler}'s central mapping from
 * exceptions the underlying services already throw — this controller adds no authority logic of its
 * own.
 */
@RestController
public class OnboardingController {

    private final VehicleProfileService vehicleProfileService;
    private final RemediationOrchestrator remediationOrchestrator;
    private final CurrentUser currentUser;
    private final OnboardingProperties properties;

    public OnboardingController(VehicleProfileService vehicleProfileService,
                                 RemediationOrchestrator remediationOrchestrator, CurrentUser currentUser,
                                 OnboardingProperties properties) {
        this.vehicleProfileService = Objects.requireNonNull(vehicleProfileService, "vehicleProfileService must not be null");
        this.remediationOrchestrator = Objects.requireNonNull(remediationOrchestrator, "remediationOrchestrator must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * The pre-registration probe (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 stage 3): observes
     * a candidate keyed only by {@code (bind address, sysid)}, before any {@code Asset}/{@code
     * Device} exists (D7). Not scoped, not audited — there is nothing yet to scope or audit against.
     *
     * @param request the candidate to probe
     * @return the observed snapshot, never persisted
     */
    @PostMapping("/api/onboarding/probe")
    public VehicleProfileResponse probeCandidate(@RequestBody ProbeCandidateRequest request) {
        request.requireProtocol();
        VehicleProfile profile = vehicleProfileService.probeCandidate(request.toLinkKey(),
                properties.inventoryWindow(), currentUser.userId());
        return VehicleProfileResponse.from(profile);
    }

    /**
     * The most recently observed profile for a registered asset. A read: unknown, out-of-scope, and
     * never-probed all answer {@code 404} identically.
     *
     * @param assetId the asset to describe, as a canonical UUID string
     * @return the latest snapshot
     */
    @GetMapping("/api/assets/{assetId}/profile")
    public VehicleProfileResponse profile(@PathVariable String assetId) {
        VehicleProfile profile = vehicleProfileService.latestProfile(AssetId.of(assetId), currentUser.scope());
        return VehicleProfileResponse.from(profile);
    }

    /**
     * Actively probes a registered asset's device and persists the resulting snapshot. An authority
     * action (D8): probing puts traffic on the aircraft's own link, so it requires {@code canManage}
     * on the asset, audited on denial.
     *
     * @param assetId the asset to probe, as a canonical UUID string
     * @return the observed snapshot
     */
    @PostMapping("/api/assets/{assetId}/probe")
    public VehicleProfileResponse probe(@PathVariable String assetId) {
        VehicleProfile profile = vehicleProfileService.probe(AssetId.of(assetId), properties.inventoryWindow(),
                currentUser.userId(), currentUser.scope());
        return VehicleProfileResponse.from(profile);
    }

    /**
     * Attempts to remediate one or more features against a registered asset, then re-probes if
     * anything was actually dispatched (see {@link RemediationOrchestrator}). Every dispatched
     * action requires {@code canManage} on the asset (D8) and a disarmed, known arming state (D10),
     * both enforced inside {@link com.drones.vision.flight.application.RemediationService}, audited
     * on denial or refusal.
     *
     * @param assetId the asset to remediate, as a canonical UUID string
     * @param request the features and remedy kinds to attempt
     * @return the remediation outcome, plus a re-probed readiness report if anything changed
     */
    @PostMapping("/api/assets/{assetId}/remediate")
    public RemediationResultResponse remediate(@PathVariable String assetId, @RequestBody RemediationRequest request) {
        return remediationOrchestrator.remediate(AssetId.of(assetId), request.featuresOrEmpty(),
                request.actionsOrEmpty(), currentUser.userId(), currentUser.scope());
    }
}
