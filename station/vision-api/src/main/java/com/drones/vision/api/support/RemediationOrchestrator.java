package com.drones.vision.api.support;

import com.drones.vision.api.dto.ReadinessReportResponse;
import com.drones.vision.api.dto.RemediationResultResponse;
import com.drones.vision.api.dto.RemediationResultResponse.RemediationActionResponse;
import com.drones.vision.flight.application.ReadinessService;
import com.drones.vision.flight.application.RemediationService;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Composes {@code POST /api/assets/{assetId}/remediate}'s end-to-end behaviour (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §8.1) out of three single-purpose {@code vision-flight} application
 * services, none of which alone knows how to go from a wire-shaped {@code {features, actions}}
 * request to a dispatched remedy plus a re-probe.
 *
 * <h2>Why this class exists, and why it lives here</h2>
 * Nothing in {@code contexts/vision-flight} composes "look up each requested feature's remedy, call
 * {@link RemediationService} for the ones that resolve, then re-probe and re-evaluate readiness" —
 * {@link RemediationService} only ever dispatches one already-identified action at a time ({@code
 * requestMessageInterval}/{@code writeParameter}), and neither {@link VehicleProfileService} nor
 * {@link ReadinessService} knows about the other two. This orchestration is genuine
 * cross-service business logic, which normally belongs in the domain/application layer — but this
 * wave's file scope ({@code station/vision-api}, {@code station/vision-app}, {@code
 * storage/persistence} only) may not add a fourth service to {@code vision-flight} (that context is
 * O3's, a separate, already-closed wave). Flagged in this wave's report as a plan gap: a
 * cleaner home for this class is a {@code RemediationOrchestrationService} (or an extension of
 * {@link RemediationService} itself) inside {@code vision-flight}, for whoever next touches that
 * context.
 *
 * <h2>What this wave dispatches, and what it honestly refuses to</h2>
 * {@code RemediationRequest.actions} may name {@code "MESSAGE_INTERVAL"} or {@code "PARAM_WRITE"},
 * but the frozen request shape carries no target value and no {@code explicitConsent} flag —
 * {@link RemediationService#writeParameter} cannot be called without both. Rather than guess a
 * value (the exact "probably fine, didn't check" lie C7 forbids) or silently drop the request, every
 * {@code PARAM_WRITE}-shaped action is reported {@code UNSUPPORTED} with a detail explaining why.
 * Only {@code MESSAGE_INTERVAL} (Mechanism A — not a write, D8) is ever actually dispatched here.
 *
 * <h2>Authority and interlocks</h2>
 * This class performs <strong>no authority check of its own</strong>: every dispatched call goes
 * through {@link RemediationService#requestMessageInterval}, which already resolves the asset
 * (404), checks {@code canManage} (403, audited), and refuses an armed/arming-unknown aircraft (409,
 * audited) before touching the port (see {@code DefaultRemediationService}'s own gate-ordering
 * javadoc). The first such exception aborts the whole request — this endpoint has no partial-success
 * shape.
 *
 * <h2>Re-probe trigger</h2>
 * A re-probe ({@link VehicleProfileService#probe}) plus re-evaluation ({@link
 * ReadinessService#evaluate}) runs iff at least one action actually reached the vehicle (dispatched
 * without throwing), regardless of whether the aircraft's own acknowledgement was {@code ACCEPTED}
 * or {@code NO_ACK} — "the command may still have landed, UDP is lossy" is this codebase's existing
 * doctrine ({@code FlightCommandController}). {@code verifiedAt}/{@code reprobe} stay {@code null}
 * when nothing was ever dispatched.
 */
public final class RemediationOrchestrator {

    private final FeatureRequirementRepositoryPort featureRequirementRepositoryPort;
    private final RemediationService remediationService;
    private final VehicleProfileService vehicleProfileService;
    private final ReadinessService readinessService;
    private final OnboardingProperties properties;

    public RemediationOrchestrator(FeatureRequirementRepositoryPort featureRequirementRepositoryPort,
                                    RemediationService remediationService, VehicleProfileService vehicleProfileService,
                                    ReadinessService readinessService, OnboardingProperties properties) {
        this.featureRequirementRepositoryPort =
                Objects.requireNonNull(featureRequirementRepositoryPort, "featureRequirementRepositoryPort must not be null");
        this.remediationService = Objects.requireNonNull(remediationService, "remediationService must not be null");
        this.vehicleProfileService = Objects.requireNonNull(vehicleProfileService, "vehicleProfileService must not be null");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Attempts to remediate {@code features} using whichever of {@code actions} applies to each
     * feature's own seeded requirement row, then re-probes if anything was actually dispatched.
     *
     * @param assetId  the asset to remediate
     * @param features requested feature keys (deduplicated, order preserved)
     * @param actions  requested remedy-kind names the caller is willing to have attempted
     * @param actor    who requested the remediation
     * @param scope    the actor's visibility scope
     * @throws com.drones.vision.platform.AccessDeniedException if the actor may not manage the asset
     *                                                           (403, audited) — from the first
     *                                                           dispatched action
     * @throws IllegalStateException                            if the aircraft is armed, its arming
     *                                                           is unknown, or probing is disabled
     *                                                           (409) — from the first dispatched
     *                                                           action
     * @throws java.util.NoSuchElementException                 if {@code assetId} is unknown (404)
     *                                                           — from the first dispatched action;
     *                                                           a request that dispatches nothing
     *                                                           never resolves the asset at all (see
     *                                                           class javadoc)
     */
    public RemediationResultResponse remediate(AssetId assetId, List<String> features, List<String> actions,
                                                 UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        Instant requestedAt = Instant.now();
        Set<String> requestedFeatures = orderedDistinct(features);
        Set<String> requestedActions = orderedDistinct(actions);

        String firmware = latestFirmware(assetId, scope);
        Map<String, FeatureRequirement> requirementsByFeature = firmware == null
                ? Map.of()
                : indexByFeature(featureRequirementRepositoryPort.findByFirmware(firmware));

        List<RemediationActionResponse> outcomes = new ArrayList<>();
        boolean anyDispatched = false;
        for (String featureKey : requestedFeatures) {
            FeatureRequirement requirement = requirementsByFeature.get(featureKey);
            if (requirement != null && requirement.requiredMessageId() != null
                    && requestedActions.contains("MESSAGE_INTERVAL")) {
                outcomes.add(dispatchMessageInterval(assetId, requirement, actor, scope));
                anyDispatched = true;
            } else if (requirement != null && requirement.requiredParameterName() != null
                    && requestedActions.contains("PARAM_WRITE")) {
                outcomes.add(unsupported("PARAM_WRITE", null, null,
                        "PARAM_WRITE needs a target value and explicit consent this request shape does not carry; "
                                + "not automatable from " + featureKey + " alone"));
            } else {
                outcomes.add(unsupported(null, null, null, unsupportedReason(featureKey, firmware, requirement)));
            }
        }

        Instant verifiedAt = null;
        ReadinessReportResponse reprobe = null;
        if (anyDispatched) {
            vehicleProfileService.probe(assetId, properties.inventoryWindow(), actor, scope);
            reprobe = ReadinessReportResponse.from(readinessService.evaluate(assetId, scope));
            verifiedAt = Instant.now();
        }

        return new RemediationResultResponse(requestedAt, verifiedAt, List.copyOf(outcomes), reprobe);
    }

    /**
     * The firmware known for {@code assetId}, or {@code null} if never probed / unknown / out of
     * scope — the three cases {@link VehicleProfileService#latestProfile} collapses into one 404,
     * caught here rather than propagated: without a profile there is nothing to look a remedy up
     * against, but that is not this endpoint's failure mode (§8.1 lists no 404 for {@code
     * /remediate} — only the actual dispatch attempts, via {@link RemediationService}, ever produce
     * a 403/404/409).
     */
    private String latestFirmware(AssetId assetId, VisibilityScope scope) {
        try {
            VehicleProfile profile = vehicleProfileService.latestProfile(assetId, scope);
            return profile.firmware();
        } catch (NoSuchElementException notProbedOrUnknown) {
            return null;
        }
    }

    private RemediationActionResponse dispatchMessageInterval(AssetId assetId, FeatureRequirement requirement,
                                                                UserId actor, VisibilityScope scope) {
        double minimumHz = requirement.minimumHz();
        long intervalMicros = Math.round(1_000_000.0 / minimumHz);
        Duration interval = Duration.of(intervalMicros, ChronoUnit.MICROS);
        MessageIntervalOutcome outcome = remediationService.requestMessageInterval(assetId,
                requirement.requiredMessageId(), interval, actor, scope);
        long actualIntervalMicros = outcome.interval().toNanos() / 1_000L;
        return new RemediationActionResponse("MESSAGE_INTERVAL", outcome.messageId(), actualIntervalMicros,
                outcome.outcome().name(), null, null, outcome.detail());
    }

    private static RemediationActionResponse unsupported(String action, Integer messageId, Long intervalMicros,
                                                           String detail) {
        return new RemediationActionResponse(action, messageId, intervalMicros, "UNSUPPORTED", null, null, detail);
    }

    private static String unsupportedReason(String featureKey, String firmware, FeatureRequirement requirement) {
        if (firmware == null) {
            return "no vehicle profile on record for this asset; probe it first";
        }
        if (requirement == null) {
            return "no " + firmware + " requirement row for feature '" + featureKey + "'";
        }
        return "no requested action applies to feature '" + featureKey + "'";
    }

    private static Map<String, FeatureRequirement> indexByFeature(List<FeatureRequirement> requirements) {
        Map<String, FeatureRequirement> byFeature = new LinkedHashMap<>();
        for (FeatureRequirement requirement : requirements) {
            byFeature.put(requirement.featureKey(), requirement);
        }
        return byFeature;
    }

    private static Set<String> orderedDistinct(List<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }
}
