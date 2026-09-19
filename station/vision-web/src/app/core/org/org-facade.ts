import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import type {
  CreateGroupRequest,
  CreateUserRequest,
  GroupSummary,
  UserMembership,
  UserSummary,
} from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { OrgApiActions, OrgPageActions } from './state/org.actions';
import { orgFeature } from './state/org.reducer';

/**
 * The org slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md §2). Every
 * mutation keeps `OrgStore`'s exact `Promise<T | null>` (or `Promise<boolean>`) return contract via
 * {@link dispatchAndAwait} — `OrgSettingsFacade` (unmigrated, out of this wave's scope) awaits each
 * one and branches on the result, so the value must still arrive, not just an event.
 *
 * **Page-provided since wave N4, not `providedIn: 'root'`** (NGRX-MIGRATION-PLAN.md §9). Both
 * injectors sit behind a lazy route — `OrgSettingsFacade` (embedded in `CrewPage` at
 * `manage/roster`) and `<vision-layer-manager>` inside the map tool rail — so the `org` slice is
 * registered by those routes instead of the root injector. **`org-guard.ts` injects `AuthFacade`,
 * not this** — that was the one thing that would have forced this slice to stay root, since the
 * guard runs on routes reachable before any of these pages load. **Behaviour change this carries:**
 * the org read-model is re-read per page visit rather than once per session.
 */
@Injectable()
export class OrgFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly users = this.store.selectSignal(orgFeature.selectUsers);
  readonly groups = this.store.selectSignal(orgFeature.selectGroups);
  readonly loading = this.store.selectSignal(orgFeature.selectLoading);
  readonly loaded = this.store.selectSignal(orgFeature.selectLoaded);
  readonly groupTree = this.store.selectSignal(orgFeature.selectGroupTree);

  async refresh(options: { quiet?: boolean } = {}): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OrgPageActions.refreshRequested({ quiet: options.quiet ?? false }),
      OrgApiActions.refreshSucceeded,
      OrgApiActions.refreshFailed,
      () => undefined,
      () => undefined,
    );
  }

  async createUser(request: CreateUserRequest): Promise<UserSummary | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      OrgPageActions.createUserRequested({ request }),
      OrgApiActions.createUserSucceeded,
      OrgApiActions.createUserFailed,
      (action) => action.user,
      () => null,
    );
  }

  async setUserEnabled(id: string, enabled: boolean): Promise<UserSummary | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      OrgPageActions.setUserEnabledRequested({ id, enabled }),
      OrgApiActions.setUserEnabledSucceeded,
      OrgApiActions.setUserEnabledFailed,
      (action) => action.user,
      () => null,
    );
  }

  async createGroup(request: CreateGroupRequest): Promise<GroupSummary | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      OrgPageActions.createGroupRequested({ request }),
      OrgApiActions.createGroupSucceeded,
      OrgApiActions.createGroupFailed,
      (action) => action.group,
      () => null,
    );
  }

  async setMemberships(userId: string, memberships: readonly UserMembership[]): Promise<UserSummary | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      OrgPageActions.setMembershipsRequested({ userId, memberships }),
      OrgApiActions.setMembershipsSucceeded,
      OrgApiActions.setMembershipsFailed,
      (action) => action.user,
      () => null,
    );
  }

  async adminSetPassword(userId: string, newPassword: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      OrgPageActions.adminSetPasswordRequested({ userId, newPassword }),
      OrgApiActions.adminSetPasswordSucceeded,
      OrgApiActions.adminSetPasswordFailed,
      () => true,
      () => false,
    );
  }
}
