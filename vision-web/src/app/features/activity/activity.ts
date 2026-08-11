import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { ActivityFacade } from './activity-facade';

/**
 * The "My activity" view (`/activity`, docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 feature 7) — the acting
 * user's own recent actions, newest first, day-grouped. Reachable by any signed-in user (only inside
 * `app.routes.ts`'s `authGuard` group, no role gate — a pilot reads their own history too), linked
 * from the identity-chip menu. The backend scopes `GET /api/me/activity` to the session's own actor,
 * so this never needs a user id: a user only ever sees their own.
 *
 * Dumb by convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md): the one fetch and its formatting/grouping lives
 * in `ActivityFacade`, which this component injects exclusively. Honest states — a distinct
 * "loading", "couldn't load" (with the reason), and an empty invitation, never a blank list
 * masquerading as "no activity". Responsive: a single scrolling column of rows that wrap on a narrow
 * viewport (`activity.css`).
 *
 * **`page-head` → `vision-page-bar`** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/09-activity.md):
 * the subtitle ("The changes you've made recently, newest first.") is deleted outright — "My
 * activity" already says it.
 *
 * **Wave 3 (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4) additions**: rows are now day-grouped under
 * `TODAY`/`YESTERDAY`/date headers with an absolute-time left gutter (the old relative time moves to
 * a `title` tooltip) — the grouping itself is `core/activity/activity-logic.ts#groupActivityByDay`,
 * a pure function with its own unit tests, not view code. The per-row verb chip is gone, replaced by
 * a left accent border (`ActivityFacade.toRow`/`core/activity/activity-logic.ts#activityAccentTone`).
 * **No `Mine | Everyone` scope toggle** — `ActivityFacade`'s own class doc comment records the check
 * that ruled it out: the backend has no all-users activity query to back it.
 */
@Component({
  selector: 'vision-activity',
  imports: [PageBar, EmptyState],
  templateUrl: './activity.html',
  styleUrl: './activity.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ActivityFacade],
})
export class ActivityPage {
  protected readonly facade = inject(ActivityFacade);
}
