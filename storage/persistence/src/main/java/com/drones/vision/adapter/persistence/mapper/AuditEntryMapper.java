package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AuditEntryEntity;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditId;

/**
 * {@link AuditEntry} &harr; {@link AuditEntryEntity} mapping, following the extraction convention
 * every other aggregate's mapper in this package already uses (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * §3/§7 row C).
 */
public final class AuditEntryMapper {

    private AuditEntryMapper() {
    }

    public static AuditEntryEntity toEntity(AuditEntry entry) {
        return new AuditEntryEntity(entry.id().value(), entry.occurredAt(), entry.actor().value(), entry.action(),
                entry.targetType(), entry.targetId(), entry.summary(), entry.details());
    }

    public static AuditEntry toDomain(AuditEntryEntity entity) {
        return new AuditEntry(new AuditId(entity.id()), entity.occurredAt(), new UserId(entity.actorId()),
                entity.action(), entity.targetType(), entity.targetId(), entity.summary(), entity.details());
    }
}
