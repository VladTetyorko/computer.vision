package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.DbAuditLogEntity;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;

/**
 * Read-only access to {@code db_audit_log} — the database-level change audit ({@code
 * V21__db_audit_log.sql}). Deliberately <strong>not</strong> a {@code Jpa*Repository implements
 * SomePort} like every sibling in this package: every row here is written by that migration's
 * {@code audit_row_change()} trigger, never by a {@code save}/{@code persist} call this class
 * would otherwise expose, and there is no domain port to implement — the change audit is
 * infrastructure a DBA or an operator reads, not a concept any bounded-context module should
 * import. If a future wave genuinely needs a context module to read this data, that decision
 * (and the port it would require) belongs to that wave, not this one; the brief this class was
 * built under is explicit that no port should be added speculatively here.
 *
 * <p>Two read paths, matching {@code db_audit_log}'s own two indexes: {@link #findRecent}
 * (newest overall) and {@link #findRecentForRow} (newest for one {@code (table, row id)}) — the
 * only two questions an operator actually asks of a change log ("what changed recently,
 * anywhere?" and "what happened to this one row?"). Both order newest-first by {@code
 * occurredAt} with {@code id} as a tiebreaker, since two rows written inside the same
 * transaction can share a timestamp down to the column's own precision.
 */
public final class JpaDbAuditLogRepository {

    private final JpaOperations jpa;

    public JpaDbAuditLogRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    /** The {@code limit} newest entries across every audited table. */
    public List<DbAuditLogEntity> findRecent(int limit) {
        return jpa.read(em -> {
            TypedQuery<DbAuditLogEntity> query = em.createQuery(
                    "select a from DbAuditLogEntity a order by a.occurredAt desc, a.id desc",
                    DbAuditLogEntity.class);
            query.setMaxResults(limit);
            return query.getResultList();
        });
    }

    /** The {@code limit} newest entries for one row, identified by {@code tableName}/{@code rowId}. */
    public List<DbAuditLogEntity> findRecentForRow(String tableName, String rowId, int limit) {
        return jpa.read(em -> {
            TypedQuery<DbAuditLogEntity> query = em.createQuery(
                    "select a from DbAuditLogEntity a where a.tableName = :tableName and a.rowId = :rowId "
                            + "order by a.occurredAt desc, a.id desc",
                    DbAuditLogEntity.class);
            query.setParameter("tableName", tableName);
            query.setParameter("rowId", rowId);
            query.setMaxResults(limit);
            return query.getResultList();
        });
    }
}
