package com.drones.vision.adapter.persistence.entity;

/**
 * The three row-level operations {@code db_audit_log} can record — mirrors Postgres's own {@code
 * TG_OP} trigger variable exactly ({@code V21__db_audit_log.sql}'s {@code audit_row_change()}
 * writes the enum name verbatim as the {@code operation} column's value). Not a domain enum —
 * there is no domain concept of "a database row changed"; this exists purely so {@link
 * DbAuditLogEntity#operation()} is typed rather than a bare string, the same "enums over
 * ad hoc strings" preference the rest of this schema follows for genuinely bounded value sets.
 */
public enum DbAuditOperation {
    INSERT,
    UPDATE,
    DELETE
}
