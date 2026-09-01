package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ParameterWriteRequest;
import com.drones.vision.api.dto.ParameterWriteResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.flight.application.RemediationService;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.ParameterAliases;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.VisibilityScope;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Driving REST adapter for the one explicit, operator-initiated parameter write this platform
 * exposes (docs/plans/active/FLEET-RADIO-PLAN.md R5; docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * O9's write half). {@code RemediationOrchestrator}'s own {@code PARAM_WRITE} handling refuses to
 * synthesise this request on the operator's behalf (D6) — it has no target value and no consent to
 * offer — so this controller is the only caller of {@link RemediationService#writeParameter} in
 * {@code vision-api}, and adds no service or adapter of its own: every gate below (tier, authority,
 * disarmed-only, device resolution) is {@link RemediationService}'s, unchanged.
 *
 * <h2>Why a dangerous endpoint gets its own controller</h2>
 * Writing a parameter to a flight controller is at least as sensitive as commanding it
 * ({@link FlightCommandController}), for two reasons this class exists to keep visible rather than
 * bury inside a general-purpose controller: the authority gate below, and {@link #requireConsent}
 * below.
 *
 * <h2>Authorization</h2>
 * Every call resolves {@link CurrentUser#scope()} and passes it straight into {@link
 * RemediationService#writeParameter}, which enforces {@code canManage}/{@code canAdminister}
 * per-tier (docs/plans/active/DRONE-ONBOARDING-PLAN.md §6.1) and audits a denial as {@link
 * com.drones.vision.platform.AccessDeniedException} (403) — the same scoped-command shape {@link
 * FlightCommandController} already uses for {@code arm}/{@code disarm}/{@code return-home}, so a
 * caller outside their management scope is refused exactly the same honest, audited way.
 *
 * <h2>What {@code consent} gates</h2>
 * {@link RemediationService#writeParameter}'s own {@code explicitConsent} parameter only gates
 * Tier-B parameters — Tier A (the tier {@code SYSID_THISMAV}/{@code MAV_SYSID} belongs to) never
 * checks it. That is correct for {@link com.drones.vision.api.support.RemediationOrchestrator}'s
 * Mechanism-A remediation (D8: a read-adjacent request that needs no confirm), but this endpoint's
 * entire reason to exist is carrying an <em>explicit</em> operator act (D6) — so {@link
 * ParameterWriteRequest#requireConsent()} enforces {@code consent == true} for every write this
 * controller dispatches, Tier A included, before the asset is even resolved. A caller who omits it
 * or sends {@code false} gets a plain 400, never a write attempt — the field is a real interlock,
 * not a decoration the server would have ignored either way.
 *
 * <h2>Spelling resolution (docs/plans/active/FLEET-RADIO-PLAN.md F0)</h2>
 * ArduPilot 4.7 renamed {@code SYSID_THISMAV} to {@code MAV_SYSID} (and {@code SYSID_MYGCS} to
 * {@code MAV_GCS_SYSID}); MAVLink has no "no such parameter" reply, so writing the spelling a
 * vehicle does not recognize is not a fast failure — it is silence, indistinguishable from a lost
 * packet until the request times out. {@link #resolveSpelling} looks at the asset's latest {@link
 * VehicleProfile} (if one exists) for a {@link ParameterReading} under any known {@link
 * ParameterAliases#spellingsOf(String) alias} of the requested name, and writes whichever spelling
 * the vehicle actually answered under the last time it was probed, falling back to the name exactly
 * as requested when there is no profile to consult (never probed, unknown asset, or an unaliased
 * name to begin with — nothing here re-derives {@link RemediationService#writeParameter}'s own
 * asset/authority resolution, so a failure at this step is never surfaced as anything other than
 * "use the name as given"). This is business logic with nowhere better to live: this wave's file
 * scope may not add a fourth application-service method to {@code vision-flight} (a hazard {@code
 * RemediationOrchestrator}'s own javadoc already flags for the same reason), and the lookup needs
 * both {@link RemediationService} and {@link VehicleProfileService} in the same place a plain DTO
 * mapping would not.
 */
@RestController
public class AssetParameterController {

    private final RemediationService remediationService;
    private final VehicleProfileService vehicleProfileService;
    private final CurrentUser currentUser;

    public AssetParameterController(RemediationService remediationService,
                                     VehicleProfileService vehicleProfileService, CurrentUser currentUser) {
        this.remediationService = Objects.requireNonNull(remediationService, "remediationService must not be null");
        this.vehicleProfileService =
                Objects.requireNonNull(vehicleProfileService, "vehicleProfileService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Writes one Tier-A/B vehicle parameter (docs/plans/active/FLEET-RADIO-PLAN.md R5). With {@code
     * vision.onboarding.probe.enabled} at its default ({@code false}), every asset resolves to the
     * no-op {@link com.drones.vision.flight.domain.port.VehicleConfigPort}, which has no device this
     * platform can configure — the same 409 refusal every onboarding endpoint gives, not a special
     * case this controller invents.
     *
     * @param id      the asset to configure, as a canonical UUID string
     * @param request the parameter, value, and explicit consent — see {@link ParameterWriteRequest}
     * @return the write outcome, snapshot-before/read-back-after
     * @throws IllegalArgumentException                          {@code name} blank, {@code value}
     *                                                            absent, {@code consent} not {@code
     *                                                            true} (400, before the asset is
     *                                                            resolved); or the parameter is Tier
     *                                                            C/unclassified, or Tier B without
     *                                                            consent (400, from {@link
     *                                                            RemediationService})
     * @throws java.util.NoSuchElementException                  the asset is unknown (404)
     * @throws com.drones.vision.platform.AccessDeniedException  the caller may not manage this asset
     *                                                            (403, audited)
     * @throws IllegalStateException                             the aircraft is armed, its arming is
     *                                                            unknown, or it has no configurable
     *                                                            device — including probing disabled
     *                                                            (409)
     */
    @PostMapping("/api/assets/{id}/parameters")
    public ParameterWriteResponse writeParameter(@PathVariable String id, @RequestBody ParameterWriteRequest request) {
        boolean consent = request.requireConsent();
        String requestedName = request.requireName();
        double value = request.requireValue();

        AssetId assetId = AssetId.of(id);
        VisibilityScope scope = currentUser.scope();
        String targetName = resolveSpelling(assetId, scope, requestedName);

        ParameterWriteOutcome outcome =
                remediationService.writeParameter(assetId, targetName, value, consent, currentUser.userId(), scope);
        return ParameterWriteResponse.from(outcome);
    }

    /**
     * See this class's own "Spelling resolution" doc section. Returns {@code requestedName}
     * unchanged whenever it names no alias group (the common case — most writable parameters were
     * never renamed) or the asset's latest profile cannot be consulted for any reason.
     */
    private String resolveSpelling(AssetId assetId, VisibilityScope scope, String requestedName) {
        Set<String> spellings = ParameterAliases.spellingsOf(requestedName);
        if (spellings.size() <= 1) {
            return requestedName;
        }
        try {
            VehicleProfile profile = vehicleProfileService.latestProfile(assetId, scope);
            return profile.parameters().stream()
                    .map(ParameterReading::name)
                    .filter(spellings::contains)
                    .findFirst()
                    .orElse(requestedName);
        } catch (NoSuchElementException neverProbedUnknownOrOutOfScope) {
            return requestedName;
        }
    }
}
