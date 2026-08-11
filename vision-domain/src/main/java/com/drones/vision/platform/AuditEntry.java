package com.drones.vision.platform;

import com.drones.vision.kernel.UserId;
import java.time.Instant;
import java.util.Map;

/**
 * One immutable line in the audit trail: who changed what, when, and how.
 *
 * <p>Written for every deliberate change to an asset or device — created, edited, deactivated,
 * reactivated, soft-deleted, restored. Because deletion is soft, the trail and its targets stay
 * mutually resolvable forever: an entry never points at a row that no longer exists, which is
 * exactly the failure that makes most audit logs useless a year later.
 *
 * <p>{@code details} carries the specifics the summary cannot — for an edit, the fields that
 * changed and their before/after values. It is free-form on purpose; a rigid schema here would
 * need migrating every time an auditable field is added.
 *
 * @param id          typed audit-entry identity
 * @param occurredAt  when the change was applied
 * @param actor       the user who made the change; the constant dev principal until the identity phase
 * @param action      what was done
 * @param targetType  the kind of thing that was changed
 * @param targetId    the changed thing's id, as a canonical string
 * @param summary     a human-readable one-line description; must not be blank
 * @param details     free-form specifics (e.g. changed fields); defensively copied to an immutable map
 */
public record AuditEntry(AuditId id, Instant occurredAt, UserId actor, AuditAction action,
                          AuditTargetType targetType, String targetId, String summary,
                          Map<String, String> details) {

    public AuditEntry {
        if (id == null) {
            throw new IllegalArgumentException("AuditEntry id must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("AuditEntry occurredAt must not be null");
        }
        if (actor == null) {
            throw new IllegalArgumentException("AuditEntry actor must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("AuditEntry action must not be null");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("AuditEntry targetType must not be null");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("AuditEntry targetId must not be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("AuditEntry summary must not be blank");
        }
        if (details == null) {
            throw new IllegalArgumentException("AuditEntry details must not be null");
        }
        details = Map.copyOf(details);
    }

    /**
     * Creates an entry stamped now, with a fresh id and no extra detail.
     *
     * @param actor      the acting user
     * @param action     what was done
     * @param targetType the kind of thing changed
     * @param targetId   the changed thing's id
     * @param summary    a human-readable description
     * @return the new entry
     */
    public static AuditEntry of(UserId actor, AuditAction action, AuditTargetType targetType, String targetId,
                                 String summary) {
        return of(actor, action, targetType, targetId, summary, Map.of());
    }

    /**
     * Creates an entry stamped now, with a fresh id and supporting detail.
     *
     * @param actor      the acting user
     * @param action     what was done
     * @param targetType the kind of thing changed
     * @param targetId   the changed thing's id
     * @param summary    a human-readable description
     * @param details    free-form specifics
     * @return the new entry
     */
    public static AuditEntry of(UserId actor, AuditAction action, AuditTargetType targetType, String targetId,
                                 String summary, Map<String, String> details) {
        return new AuditEntry(AuditId.random(), Instant.now(), actor, action, targetType, targetId, summary, details);
    }
}
