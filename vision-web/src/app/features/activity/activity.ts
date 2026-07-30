import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { formatActivity } from '../../core/org/org-logic';
import { EmptyState } from '../../shared/ui/empty-state';
import type { AuditEntry } from '../../core/api/models';

/** How many recent entries to request — the backend caps at 500; 100 is plenty for a "recent activity" read. */
const ACTIVITY_LIMIT = 100;

/**
 * The "My activity" view (`/activity`, docs/U-SCOPE-PLAN.md, U-e slice 2 feature 7) — the acting
 * user's own recent actions, newest first. Reachable by any signed-in user (only inside
 * `app.routes.ts`'s `authGuard` group, no role gate — a pilot reads their own history too), linked
 * from the identity-chip menu. The backend scopes `GET /api/me/activity` to the session's own actor,
 * so this never needs a user id: a user only ever sees their own.
 *
 * Dumb by convention: the one fetch lives here, every row's rendering is `org-logic.ts#formatActivity`
 * (pure, unit-tested). Honest states — a distinct "loading", "couldn't load" (with the reason), and
 * an empty invitation, never a blank list masquerading as "no activity". Responsive: a single
 * scrolling column of rows that wrap on a narrow viewport (`activity.css`).
 */
@Component({
  selector: 'vision-activity',
  imports: [EmptyState],
  templateUrl: './activity.html',
  styleUrl: './activity.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActivityPage {
  private readonly api = inject(VisionApi);

  private readonly entries = signal<readonly AuditEntry[]>([]);
  private readonly nowMs = signal(Date.now());

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** The rows, pre-formatted — recomputed only if the entries or the reference clock change. */
  protected readonly rows = computed(() => this.entries().map((entry) => formatActivity(entry, this.nowMs())));

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
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
}
