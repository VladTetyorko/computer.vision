package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AuditEntryResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.directory.AssetDirectoryService;
import com.drones.vision.warehouse.domain.model.Asset;
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
 * the services that make the changes, never by a caller. {@link AssetDirectoryService} is the one
 * extra collaborator this class needs for the subtree filter below (see "Subtree filter"); it stays
 * the narrow read-only seam it is everywhere else in this codebase, never {@code AssetService}
 * itself.
 *
 * <h2>Management gate (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2)</h2>
 * {@link #list} exposes who-changed-what across the <em>whole fleet</em>, a cross-tenant
 * information leak once real users exist — so it is admission-gated on {@link
 * Authority#mayManageOrg() authority().mayManageOrg()} (docs/plans/active/AUTH-ROLES-PLAN.md wave B6,
 * superseding the bare {@code VisibilityScope#canManageOrg()} check this gate used before), true for
 * ADMIN ({@code UNBOUNDED} scope, every capability) and MANAGER ({@code GROUPS} scope, holding
 * {@code MANAGE_ORG}), false for PILOT ({@code ASSIGNED_ASSETS}) or an unaffiliated caller, throwing
 * {@link AccessDeniedException} (403 via {@code ApiExceptionHandler}) otherwise. This mirrors the
 * same ADMIN/MANAGER gate {@link GroupAdminController}/{@link UserAdminController} apply, enforced
 * directly in this controller rather than in an application service since — per the class javadoc
 * above — there deliberately is no service layer here to put it in. With auth off (the default) the
 * dev principal's authority is {@link Authority#full()}, so this gate is a no-op and behavior is
 * unchanged from before scoping.
 *
 * <h2>Subtree filter (docs/plans/active/AUTH-ROLES-PLAN.md D18a, wave B6)</h2>
 * An admitted MANAGER used to see the <em>whole fleet's</em> audit trail regardless of their own
 * group subtree — a named authority leak (D18), self-documented as deferred U-SCOPE cleanup until
 * this wave closed it. Once admitted, {@link #list} filters the raw entries by {@link
 * #isVisible(AuditEntry, VisibilityScope)}: an {@code ASSET}/{@code DEVICE} entry is visible iff
 * {@link VisibilityScope#includes} holds for the asset it names (a {@code DEVICE} entry resolves to
 * its owning asset via {@link AssetDirectoryService#findByDevice}); a {@code GROUP} entry is visible
 * iff {@link VisibilityScope#includesGroup} holds for the group id it names. {@code USER}/{@code
 * ASSIGNMENT}/{@code DATASET}/{@code MODEL} entries stay unfiltered — none of those target kinds
 * carries an {@code Ownership} a group subtree could check against (mirrors {@code
 * DiscoveryInboxService#candidates()}'s "no per-instance visibility check to authorise against"
 * reasoning), so an admitted MANAGER sees every one of those exactly as an ADMIN does; only the two
 * genuinely fleet-scoped target kinds are narrowed. An {@link Authority#scope()} that {@link
 * VisibilityScope#isUnbounded()} (ADMIN, or the dev principal with auth off) skips the filter
 * entirely rather than resolving every entry for nothing.
 */
@RestController
public class AuditController {

    /** Default page size: enough to answer "what happened recently" without unbounded reads. */
    private static final int DEFAULT_LIMIT = 100;

    private final AuditTrailPort auditTrail;
    private final AssetDirectoryService assetDirectoryService;
    private final CurrentUser currentUser;

    public AuditController(AuditTrailPort auditTrail, AssetDirectoryService assetDirectoryService,
                            CurrentUser currentUser) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.assetDirectoryService =
                Objects.requireNonNull(assetDirectoryService, "assetDirectoryService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists audit entries, newest first — either across the whole fleet or for one thing.
     *
     * @param targetType optional filter: {@code ASSET} or {@code DEVICE}; requires {@code targetId}
     * @param targetId   optional filter: the id to scope to; requires {@code targetType}
     * @param limit      maximum entries to return
     * @return the matching entries, newest first, subtree-filtered per the class javadoc's Subtree
     *         filter section
     * @throws AccessDeniedException if the caller's authority may not manage the organization
     *                                (403); see the class javadoc's Management gate section
     */
    @GetMapping("/api/audit")
    public List<AuditEntryResponse> list(@RequestParam(required = false) String targetType,
                                          @RequestParam(required = false) String targetId,
                                          @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        Authority authority = currentUser.authority();
        if (!authority.mayManageOrg()) {
            throw new AccessDeniedException("Not permitted to view the audit trail");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType and targetId must be supplied together");
        }

        List<AuditEntry> entries = targetType == null
                ? auditTrail.findRecent(limit)
                : auditTrail.findByTarget(parseTargetType(targetType), targetId, limit);
        VisibilityScope scope = authority.scope();
        if (!scope.isUnbounded()) {
            entries = entries.stream().filter(entry -> isVisible(entry, scope)).toList();
        }
        return entries.stream().map(AuditEntryResponse::from).toList();
    }

    /**
     * Whether one audit entry falls within {@code scope}'s subtree — see the class javadoc's
     * Subtree filter section for which target kinds this narrows and why the rest stay unfiltered.
     */
    private boolean isVisible(AuditEntry entry, VisibilityScope scope) {
        return switch (entry.targetType()) {
            case ASSET -> assetDirectoryService.find(AssetId.of(entry.targetId()))
                    .map(asset -> scope.includes(asset.id(), asset.ownership()))
                    .orElse(false);
            case DEVICE -> assetDirectoryService.findByDevice(DeviceId.of(entry.targetId()))
                    .map((Asset asset) -> scope.includes(asset.id(), asset.ownership()))
                    .orElse(false);
            case GROUP -> scope.includesGroup(GroupId.of(entry.targetId()));
            case USER, ASSIGNMENT, DATASET, MODEL -> true;
        };
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
