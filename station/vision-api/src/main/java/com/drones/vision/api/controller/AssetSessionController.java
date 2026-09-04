package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AssetUsageResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the operator {@code engage}/{@code disengage} verb: {@code
 * POST}/{@code DELETE /api/assets/{id}/session}.
 *
 * <p>docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md §3 D2, wave R2: before this controller, an
 * {@link AssetUsage} could only ever be opened as a side effect of a video stream starting ({@link
 * AssetStreamController#startStream} → {@code DefaultStreamService} → {@link
 * UsageTracker#deviceStreamStarted}). An operator preparing a telemetry-only aircraft, or one being
 * readied before its camera comes up, had no way to say "this asset is in use" — {@link #engage}/
 * {@link #disengage} are that explicit act, opening or closing a usage directly, with no video
 * stream and no device traffic involved. See {@link UsageTracker#engage}/{@code #disengage}'s own
 * javadoc for the three stream/operator collision rules this endpoint pair is built on.
 *
 * <p>Named {@code .../session}, not {@code .../engage} and {@code .../disengage}, to match this
 * codebase's established sub-resource idiom: {@link AssetStreamController} already opens/closes the
 * video half of an asset's activity as {@code POST}/{@code DELETE .../stream}; this is the same
 * shape for the operator half, one verb pair per HTTP method rather than two path segments encoding
 * the verb by name.
 *
 * <h2>Who the change is attributed to</h2>
 * {@link #engage} (docs/plans/active/ASSET-FLOWS-PLAN.md §2, D1p) passes {@link
 * CurrentUser#userId()} through to {@link UsageTracker#engage}, which stamps it onto {@link
 * AssetUsage#pilotId()} — this endpoint always runs behind authentication, so a pilot is always
 * known here. {@link AssetStreamController#startStream}, by contrast, consults {@link CurrentUser}
 * for scope alone and never threads it into the usage a stream start opens (see that controller's
 * own "Who the change is attributed to" section); a stream-opened usage's {@code pilotId} stays
 * {@code null} until an operator later calls {@link #engage} here, which backfills it via
 * promotion. {@link #disengage} never creates or first-attributes a usage, only closes or demotes
 * one, so {@link AssetUsage} itself carries nothing for it to stamp — but the caller ending
 * someone else's session is exactly D17's defect (docs/plans/active/AUTH-ROLES-PLAN.md), so wave
 * B4 has it record who did it via {@link AuditTrailPort} instead (see {@link
 * #auditDisengage(AssetId, AssetUsage)}). This is attribution only, same as the plan scopes it —
 * no seat check, no arbitration, no refusal; any in-scope caller may still end the session, exactly
 * as before, but now the audit trail says who.
 *
 * <h2>Visibility scoping, not management authority</h2>
 * Both handlers re-read the asset through {@link CurrentUser#scope()} before mutating — the same
 * "read-scope guards the write" posture {@link AssetStreamController#requireInScope} uses, and for
 * the same reason: engaging/disengaging is an operator act on an aircraft the caller may fly, not
 * an administrative one, so it requires only visibility (can the caller see this asset at all), not
 * {@link com.drones.vision.platform.VisibilityScope#canManage canManage}. An out-of-scope or
 * unknown asset 404s — existence is never revealed to a caller who may not see it.
 *
 * <h2>Status codes</h2>
 * {@link #engage} answers {@code 200 OK} rather than {@code 201 Created}: unlike starting a stream,
 * which always creates a new one, engaging is idempotent and may instead <em>promote</em> an
 * already-open stream-origin usage, or no-op on one already operator-engaged — "created" would be
 * false in two of those three cases. {@link #disengage} answers {@code 204 No Content}, mirroring
 * {@link AssetStreamController#stopStream}'s idempotent-DELETE contract, whether it closed a usage,
 * demoted one back to {@code STREAM} origin (a stream is still running), or found nothing to do. An
 * unknown/out-of-scope asset surfaces as {@link java.util.NoSuchElementException} from {@link
 * AssetService#details(com.drones.vision.platform.VisibilityScope, AssetId)} and maps to {@code 404}
 * through {@link ApiExceptionHandler}; engaging a deactivated or deleted asset surfaces as {@link
 * IllegalStateException} from {@link UsageTracker#engage} and maps to {@code 409}; a malformed UUID
 * fails earlier in {@code AssetId.of} and maps to {@code 400}.
 */
@RestController
public class AssetSessionController {

    private final AssetService assetService;
    private final UsageTracker usageTracker;
    private final CurrentUser currentUser;
    private final AuditTrailPort auditTrail;

    public AssetSessionController(AssetService assetService, UsageTracker usageTracker, CurrentUser currentUser,
                                   AuditTrailPort auditTrail) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    /**
     * Marks the asset as in use directly — no video stream, no device traffic. Opens a new
     * {@code OPERATOR}-origin usage if none is open; promotes an already-open {@code STREAM}-origin
     * usage to {@code OPERATOR} if a stream is already running (see {@link UsageTracker#engage}'s
     * javadoc for why promoting, not rejecting, is the right call); no-ops if the asset is already
     * operator-engaged.
     *
     * @param id the asset to engage, as a canonical UUID string
     * @return the usage now open for this asset (newly opened, promoted, or unchanged)
     */
    @PostMapping("/api/assets/{id}/session")
    public AssetUsageResponse engage(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        return AssetUsageResponse.from(usageTracker.engage(assetId, currentUser.userId()));
    }

    /**
     * Ends the operator's direct engagement of the asset. A no-op if the asset is not currently
     * operator-engaged. If a video stream is still running when this is called, the usage is
     * <em>demoted</em> back to {@code STREAM} origin rather than closed — a running stream must
     * always have somewhere to record telemetry against, so disengaging alone never closes a usage
     * a stream still depends on (see {@link UsageTracker#disengage}'s javadoc). Otherwise the usage
     * is closed exactly as a stream stopping would close it. Whichever outcome occurs, the caller
     * is recorded as who did it (docs/plans/active/AUTH-ROLES-PLAN.md D17, wave B4) — see {@link
     * #auditDisengage(AssetId, AssetUsage)}. A no-op disengage (nothing was operator-engaged)
     * records nothing; there is no action to attribute.
     *
     * @param id the asset to disengage, as a canonical UUID string
     */
    @DeleteMapping("/api/assets/{id}/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disengage(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        usageTracker.disengage(assetId).ifPresent(usage -> auditDisengage(assetId, usage));
    }

    /**
     * Guards {@link #engage}/{@link #disengage}: re-reads {@code id} through the caller's scope so
     * an out-of-scope (or unknown) asset 404s before the mutation runs — the same scoped read
     * {@link AssetStreamController#requireInScope} performs.
     */
    private void requireInScope(AssetId id) {
        assetService.details(currentUser.scope(), id);
    }

    /**
     * Records who ended the session (docs/plans/active/AUTH-ROLES-PLAN.md D17, wave B4) —
     * <b>attribution, not authority</b>: this never refuses the call, it only names the caller
     * afterward. Mirrors the {@code AuditAction.UPDATED}/{@code AuditTargetType.ASSET} shape
     * {@code DefaultManualControlService#audit} already uses for engage/release, the closest
     * existing precedent for "an operator act on an aircraft, not a create/edit/delete".
     *
     * @param assetId the asset whose session ended
     * @param usage   the usage as {@link UsageTracker#disengage} left it — closed, or demoted and
     *                still open
     */
    private void auditDisengage(AssetId assetId, AssetUsage usage) {
        String outcome = usage.endedAt() != null ? "closed" : "demoted";
        Map<String, String> attributes = Map.of("usageId", usage.id().value().toString(), "outcome", outcome);
        auditTrail.record(AuditEntry.of(currentUser.userId(), AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Operator session " + outcome + " for asset " + assetId.value(),
                attributes));
    }
}
