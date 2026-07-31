import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import { ActivityFacade } from './activity-facade';

/**
 * The "My activity" view (`/activity`, docs/U-SCOPE-PLAN.md, U-e slice 2 feature 7) — the acting
 * user's own recent actions, newest first. Reachable by any signed-in user (only inside
 * `app.routes.ts`'s `authGuard` group, no role gate — a pilot reads their own history too), linked
 * from the identity-chip menu. The backend scopes `GET /api/me/activity` to the session's own actor,
 * so this never needs a user id: a user only ever sees their own.
 *
 * Dumb by convention (docs/UI-ARCHITECTURE-PLAN.md): the one fetch and its formatting lives in
 * `ActivityFacade`, which this component injects exclusively. Honest states — a distinct "loading",
 * "couldn't load" (with the reason), and an empty invitation, never a blank list masquerading as "no
 * activity". Responsive: a single scrolling column of rows that wrap on a narrow viewport (`activity.css`).
 */
@Component({
  selector: 'vision-activity',
  imports: [EmptyState],
  templateUrl: './activity.html',
  styleUrl: './activity.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ActivityFacade],
})
export class ActivityPage {
  protected readonly facade = inject(ActivityFacade);
}
