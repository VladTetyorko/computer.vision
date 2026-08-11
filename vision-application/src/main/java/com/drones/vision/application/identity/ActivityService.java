package com.drones.vision.application.identity;

import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.UserId;

import java.util.List;
import com.drones.vision.application.scope.VisibilityScope;

/**
 * A user's own activity feed (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 7) — the read side behind
 * {@code GET /api/me/activity} (vision-api, a later wave). One interface, one implementation
 * ({@link DefaultActivityService}).
 *
 * <p>The only scoping here is "your own actor id": a user sees the audit entries they themselves
 * made. A manager-sees-their-team's-activity view is deferred (it would filter by the actors within
 * the manager's {@link VisibilityScope}); this read is intentionally minimal.
 */
public interface ActivityService {

    /**
     * Lists the acting user's own most-recent audit entries, newest first.
     *
     * @param actor the user whose activity to read
     * @param limit maximum number of entries to return; must be positive
     * @return an immutable snapshot, newest first
     */
    List<AuditEntry> myActivity(UserId actor, int limit);
}
