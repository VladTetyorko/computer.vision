import type { GroupSummary, UserSummary } from '../../api/models';

/**
 * Users and groups for the org-settings surface (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2;
 * migrated off `OrgStore` per docs/plans/active/NGRX-MIGRATION-PLAN.md wave N2). Registered
 * app-wide in `provideAppState()`, mirroring the old store's `providedIn: 'root'` posture — the
 * pilot-assignment card also reads the user list, so this outlives any one page. **Lazy, not
 * self-initializing**: nothing dispatches `Refresh Requested` on boot; `features/org-settings/**`
 * does it once actually reached, past the role guard, so an admin-only listing never hits
 * `/api/users`/`/api/groups` for a pilot who'd just 403.
 */
export interface OrgState {
  readonly users: readonly UserSummary[];
  readonly groups: readonly GroupSummary[];
  readonly loading: boolean;
  /** `false` until the first successful refresh — lets a page tell "still loading" from
   *  "genuinely no users/groups". */
  readonly loaded: boolean;
}

export const initialOrgState: OrgState = {
  users: [],
  groups: [],
  loading: false,
  loaded: false,
};
