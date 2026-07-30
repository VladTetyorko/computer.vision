package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link AuditTrailPort}: dev/Phase-0 fallback with no durability
 * across restarts.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres), planned for
 * Phase 2. An audit trail that evaporates on restart is obviously not an audit
 * trail — this exists so the surface is wired and exercised end to end while
 * storage catches up, not because it is fit for use.
 */
public final class InMemoryAuditTrail implements AuditTrailPort {

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public AuditEntry record(AuditEntry entry) {
        entries.add(entry);
        return entry;
    }

    @Override
    public List<AuditEntry> findRecent(int limit) {
        return newestFirst().limit(limit).toList();
    }

    @Override
    public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
        return newestFirst()
                .filter(entry -> entry.targetType() == targetType && entry.targetId().equals(targetId))
                .limit(limit)
                .toList();
    }

    @Override
    public List<AuditEntry> findByActor(UserId actor, int limit) {
        return newestFirst()
                .filter(entry -> entry.actor().equals(actor))
                .limit(limit)
                .toList();
    }

    private java.util.stream.Stream<AuditEntry> newestFirst() {
        return entries.stream().sorted(Comparator.comparing(AuditEntry::occurredAt).reversed());
    }
}
