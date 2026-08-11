package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AuditEntryResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.identity.domain.port.AuditTrailPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Driving REST adapter for reading the audit trail.
 *
 * <p>Reads {@link AuditTrailPort} directly rather than through a driving use case: there is no
 * decision to make when listing an append-only log, and inventing a pass-through use case would
 * add a layer that does nothing. This follows the precedent {@link AssetController} already sets
 * for a usage's telemetry trail. Writing to the trail is never exposed — entries are produced by
 * the services that make the changes, never by a caller.
 *
 * <h2>Management gate (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2)</h2>
 * {@link #list} exposes who-changed-what across the <em>whole fleet</em>, a cross-tenant
 * information leak once real users exist — so it is admission-gated on {@link
 * com.drones.vision.application.scope.VisibilityScope#canManageOrg()}, true for ADMIN
 * ({@code UNBOUNDED}) and MANAGER ({@code GROUPS}) scopes, false for PILOT ({@code
 * ASSIGNED_ASSETS}) or an unaffiliated caller, throwing {@link AccessDeniedException} (403 via
 * {@code ApiExceptionHandler}) otherwise. This mirrors the same ADMIN/MANAGER gate {@link
 * GroupAdminController}/{@link UserAdminController} apply, enforced directly in this controller
 * rather than in an application service since — per the class javadoc above — there deliberately
 * is no service layer here to put it in. <strong>This does not further filter a MANAGER's view down
 * to their own group subtree</strong> — an admitted MANAGER sees the whole fleet's audit trail, the
 * same coarser posture {@code VisibilityScope#canManageOrg}'s own javadoc already calls a "deferred
 * slice-2 cleanup" elsewhere in this codebase. With auth off (the default) the dev principal's scope
 * is unbounded, so this gate is a no-op and behavior is unchanged from before scoping.
 */
@RestController
public class AuditController {

    /** Default page size: enough to answer "what happened recently" without unbounded reads. */
    private static final int DEFAULT_LIMIT = 100;

    private final AuditTrailPort auditTrail;
    private final CurrentUser currentUser;

    public AuditController(AuditTrailPort auditTrail, CurrentUser currentUser) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists audit entries, newest first — either across the whole fleet or for one thing.
     *
     * @param targetType optional filter: {@code ASSET} or {@code DEVICE}; requires {@code targetId}
     * @param targetId   optional filter: the id to scope to; requires {@code targetType}
     * @param limit      maximum entries to return
     * @return the matching entries, newest first
     * @throws AccessDeniedException if the caller's scope may not manage the organization (403);
     *                                see the class javadoc's Management gate section
     */
    @GetMapping("/api/audit")
    public List<AuditEntryResponse> list(@RequestParam(required = false) String targetType,
                                          @RequestParam(required = false) String targetId,
                                          @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to view the audit trail");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType and targetId must be supplied together");
        }

        List<com.drones.vision.identity.domain.model.AuditEntry> entries = targetType == null
                ? auditTrail.findRecent(limit)
                : auditTrail.findByTarget(parseTargetType(targetType), targetId, limit);
        return entries.stream().map(AuditEntryResponse::from).toList();
    }

    private static AuditTargetType parseTargetType(String name) {
        try {
            return AuditTargetType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown targetType: " + name + " (valid values: "
                    + Arrays.stream(AuditTargetType.values()).map(Enum::name).collect(Collectors.joining(", "))
                    + ")", e);
        }
    }
}
