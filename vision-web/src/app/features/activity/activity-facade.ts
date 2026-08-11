import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { formatActivity, type ActivityView } from '../../core/org/org-logic';
import { activityAccentTone, groupActivityByDay, type ActivityAccentTone } from '../../core/activity/activity-logic';
import type { AuditEntry } from '../../core/api/models';

/** How many recent entries to request — the backend caps at 500; 100 is plenty for a "recent activity" read. */
const ACTIVITY_LIMIT = 100;

/** One rendered row — `ActivityView`'s existing display fields plus this task's own two additions. */
export interface ActivityRow extends ActivityView {
  readonly accentTone: ActivityAccentTone;
  /** A left-gutter absolute time (`docs/extracts/design/09-activity.md`: "an audit log is read by 'when'"). */
  readonly absoluteTime: string;
}

/** One day's worth of rows, headed by `groupActivityByDay`'s own label. */
export interface ActivityDayRows {
  readonly label: string;
  readonly rows: readonly ActivityRow[];
}

/**
 * `ActivityPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md). Calls `VisionApi.myActivity` directly
 * rather than through a store — mirrors `features/fly/flight-command-panel.ts`'s own precedent for
 * a one-shot, page-scoped read with no shared/reusable state a store would sensibly model.
 *
 * **`Mine | Everyone` scope toggle — checked and omitted, not silently skipped.**
 * docs/extracts/design/09-activity.md's own refactor list calls for a manager-only `Everyone` scope behind
 * `canManageOrg` (`core/org/org-logic.ts`). The backend's `GET /api/me/activity`
 * (`vision-api/.../ActivityController`) only ever reads `currentUser.userId()` — there is no
 * all-users query anywhere in `ActivityService`/`DefaultActivityService` (that interface's own doc
 * comment states the scoping is "intentionally minimal… a manager-sees-their-team's-activity view is
 * deferred"). Per this task's own explicit instruction ("check the backend actually supports an
 * all-users query before building it; if it does not, omit the toggle and report that, rather than
 * shipping a control that silently shows the same rows"), the toggle is not built — see this task's
 * final report for the flag.
 *
 * **Day-grouping (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4)**: `dayGroups` composes two independently
 * pure/tested layers — `core/org/org-logic.ts#formatActivity` (unchanged, per-entry display fields)
 * and this feature's own new `core/activity/activity-logic.ts#groupActivityByDay`/`activityAccentTone`
 * (calendar bucketing + the verb→accent-color mapping) — rather than growing either existing export.
 */
@Injectable()
export class ActivityFacade {
  private readonly api = inject(VisionApi);

  private readonly entries = signal<readonly AuditEntry[]>([]);
  private readonly nowMs = signal(Date.now());

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  /** The full flat list — only used for the page bar's own `[count]` chip; the template itself reads `dayGroups`. */
  readonly rows = computed<readonly ActivityRow[]>(() => this.entries().map((entry) => this.toRow(entry)));

  readonly dayGroups = computed<readonly ActivityDayRows[]>(() =>
    groupActivityByDay(this.entries(), this.nowMs()).map((group) => ({
      label: group.label,
      rows: group.entries.map((entry) => this.toRow(entry)),
    })),
  );

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const entries = await this.api.myActivity(ACTIVITY_LIMIT);
      this.nowMs.set(Date.now());
      this.entries.set(entries);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  private toRow(entry: AuditEntry): ActivityRow {
    return {
      ...formatActivity(entry, this.nowMs()),
      accentTone: activityAccentTone(entry.action),
      absoluteTime: new Date(entry.occurredAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }),
    };
  }
}
