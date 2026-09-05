import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { buildGroupTree } from './org-logic';
import type {
  CreateGroupRequest,
  CreateUserRequest,
  GroupSummary,
  UserMembership,
  UserSummary,
} from '../api/models';

/** Stable per-file console tag, mirroring `[auth]`/`[fleet]` — no shared logging service in this app. */
const LOG_PREFIX = '[org]';

/**
 * The org-settings surface's source of truth for users and groups (docs/plans/done/U-SCOPE-PLAN.md, U-e slice
 * 2) — `providedIn: 'root'`, one instance app-wide, mirroring `AuthStore`'s posture rather than a
 * page-scoped provider (the pilots-assignment card also reads its user list, so this outlives any
 * one page). **Lazy, not self-initializing**: unlike `FleetStore`/`AuthStore`, it does *not* fetch
 * from its own constructor — an admin-only listing shouldn't hit `/api/users`/`/api/groups` on
 * every app boot for every user (a pilot would just 403), so `features/org-settings/**` calls
 * `refresh()` once it's actually reached (past the role guard).
 *
 * Every mutation funnels through `run()` so a failure produces exactly one explained toast, the
 * same seam `FleetStore` established; a `403` (a grant above the inviter's own scope) or `409`
 * (username taken) surfaces via `describeHttpError`'s own specific sentences.
 */
@Injectable({ providedIn: 'root' })
export class OrgStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  private readonly usersSignal = signal<readonly UserSummary[]>([]);
  private readonly groupsSignal = signal<readonly GroupSummary[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);

  readonly users = this.usersSignal.asReadonly();
  readonly groups = this.groupsSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** `false` until the first successful `refresh()` — lets the page tell "still loading" from "genuinely no users/groups". */
  readonly loaded = this.loadedSignal.asReadonly();

  /** The group hierarchy derived from the flat list (`org-logic.ts#buildGroupTree`) — cycle-safe, sorted, recomputed only when `groups` changes. */
  readonly groupTree = computed(() => buildGroupTree(this.groupsSignal()));

  /** Re-reads users and groups together. Toasts once on failure unless `quiet`. */
  async refresh(options: { quiet?: boolean } = {}): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const [users, groups] = await Promise.all([this.api.listUsers(), this.api.listGroups()]);
      this.usersSignal.set(users);
      this.groupsSignal.set(groups);
      this.loadedSignal.set(true);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to load users/groups`, { error });
      if (!options.quiet) {
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loadingSignal.set(false);
    }
  }

  async createUser(request: CreateUserRequest): Promise<UserSummary | null> {
    return this.run(async () => {
      const user = await this.api.createUser(request);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Created ${user.displayName}.`);
      return user;
    });
  }

  async setUserEnabled(id: string, enabled: boolean): Promise<UserSummary | null> {
    return this.run(async () => {
      const user = await this.api.setUserEnabled(id, enabled);
      await this.refresh({ quiet: true });
      this.toasts.ok(`${user.displayName} is now ${enabled ? 'enabled' : 'disabled'}.`);
      return user;
    });
  }

  async createGroup(request: CreateGroupRequest): Promise<GroupSummary | null> {
    return this.run(async () => {
      const group = await this.api.createGroup(request);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Created ${group.name}.`);
      return group;
    });
  }

  /**
   * Wholesale-replaces `userId`'s group memberships (docs/plans/active/AUTH-ROLES-PLAN.md wave W3,
   * `PUT /api/users/{id}/memberships`) — not a delta, so the caller (`org-settings-facade.ts`'s
   * manage-user panel) always sends the *complete* intended set. A `403` (a grant above the
   * caller's own scope) surfaces via `describeHttpError`'s own specific sentence, same as every
   * other mutation here.
   */
  async setMemberships(userId: string, memberships: readonly UserMembership[]): Promise<UserSummary | null> {
    return this.run(async () => {
      const user = await this.api.setMemberships(userId, { memberships });
      await this.refresh({ quiet: true });
      this.toasts.ok(`Updated ${user.displayName}'s memberships.`);
      return user;
    });
  }

  /**
   * Sets `userId`'s password on their behalf (docs/plans/active/AUTH-ROLES-PLAN.md wave W3,
   * `POST /api/users/{id}/password`) — always forces the target's own `mustChangePassword` on the
   * next request they make, per the endpoint's own frozen contract; this store has no local
   * `mustChangePassword` copy to flip, `refresh()` re-reads the true value from `/api/users`.
   * Returns `true` on success, `false` on any failure (already toasted by `run()`) — the manage-user
   * panel uses this to decide whether to also clear its own password-reset field.
   */
  async adminSetPassword(userId: string, newPassword: string): Promise<boolean> {
    const result = await this.run(async () => {
      await this.api.adminSetPassword(userId, { newPassword });
      this.toasts.ok('Password reset — they must change it at next sign-in.');
      return true;
    });
    return result ?? false;
  }

  /** Runs an action, turning any failure into one explained toast (mirrors `FleetStore.run`). */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      console.warn(`${LOG_PREFIX} action failed`, { error });
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
