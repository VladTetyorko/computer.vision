package com.drones.vision.identity.application;

import com.drones.vision.platform.AuditEntry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;

import java.util.List;
import java.util.Objects;

/**
 * The one implementation of {@link ActivityService} — a thin, read-only pass-through over
 * {@link AuditTrailPort#findByActor(UserId, int)}.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — safe to call concurrently.
 */
public final class DefaultActivityService implements ActivityService {

    private final AuditTrailPort auditTrail;

    public DefaultActivityService(AuditTrailPort auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public List<AuditEntry> myActivity(UserId actor, int limit) {
        Objects.requireNonNull(actor, "actor must not be null");
        return auditTrail.findByActor(actor, limit);
    }
}
