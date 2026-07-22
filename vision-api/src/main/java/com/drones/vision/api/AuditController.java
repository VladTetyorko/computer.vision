package com.drones.vision.api;

import com.drones.vision.api.dto.AuditEntryResponse;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.port.out.AuditTrailPort;
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
 */
@RestController
public class AuditController {

    /** Default page size: enough to answer "what happened recently" without unbounded reads. */
    private static final int DEFAULT_LIMIT = 100;

    private final AuditTrailPort auditTrail;

    public AuditController(AuditTrailPort auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    /**
     * Lists audit entries, newest first — either across the whole fleet or for one thing.
     *
     * @param targetType optional filter: {@code ASSET} or {@code DEVICE}; requires {@code targetId}
     * @param targetId   optional filter: the id to scope to; requires {@code targetType}
     * @param limit      maximum entries to return
     * @return the matching entries, newest first
     */
    @GetMapping("/api/audit")
    public List<AuditEntryResponse> list(@RequestParam(required = false) String targetType,
                                          @RequestParam(required = false) String targetId,
                                          @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType and targetId must be supplied together");
        }

        List<com.drones.vision.domain.model.AuditEntry> entries = targetType == null
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
