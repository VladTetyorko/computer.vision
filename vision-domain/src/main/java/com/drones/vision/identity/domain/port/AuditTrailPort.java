package com.drones.vision.identity.domain.port;

import com.drones.vision.identity.domain.model.AuditEntry;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.kernel.UserId;

import java.util.List;

/**
 * Driven port: an append-only record of who changed the fleet, and how.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #record(AuditEntry)} appends an entry. Entries are immutable historical facts —
 *       never updated, never deleted, not even when their target is soft-deleted. An audit trail
 *       that can be edited is not an audit trail.</li>
 *   <li>{@link #findRecent(int)} returns the newest entries across everything, bounded to
 *       {@code limit}, newest first.</li>
 *   <li>{@link #findByTarget(AuditTargetType, String, int)} returns the newest entries for one
 *       thing, newest first — the history shown on an asset's page.</li>
 *   <li>{@link #findByActor(UserId, int)} returns the newest entries made by one actor, newest
 *       first — the "my activity" feed shown to a user (docs/plans/done/U-SCOPE-PLAN.md, feature 7).</li>
 * </ul>
 *
 * <p>Recording must never break the operation being audited: implementations that can fail
 * (network, database) should degrade rather than propagate, since losing an audit line is
 * strictly better than failing a deletion the user already confirmed.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: entries are appended from any control-plane
 * call while reads happen concurrently.
 */
public interface AuditTrailPort {

    /**
     * Appends an entry to the trail.
     *
     * @param entry the entry to record
     * @return the recorded entry
     */
    AuditEntry record(AuditEntry entry);

    /**
     * Lists the most recent entries across all targets, newest first.
     *
     * @param limit maximum number of entries to return; must be positive
     * @return an immutable snapshot, newest first
     */
    List<AuditEntry> findRecent(int limit);

    /**
     * Lists the most recent entries for one target, newest first.
     *
     * @param targetType the kind of thing
     * @param targetId   the thing's id, as a canonical string
     * @param limit      maximum number of entries to return; must be positive
     * @return an immutable snapshot, newest first
     */
    List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit);

    /**
     * Lists the most recent entries made by one actor, newest first.
     *
     * @param actor the acting user whose history to return
     * @param limit maximum number of entries to return; must be positive
     * @return an immutable snapshot, newest first
     */
    List<AuditEntry> findByActor(UserId actor, int limit);
}
