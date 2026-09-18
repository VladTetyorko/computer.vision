import { createActionGroup, props } from '@ngrx/store';
import type {
  CreateGroupRequest,
  CreateUserRequest,
  GroupSummary,
  UserMembership,
  UserSummary,
} from '../../api/models';

/** Every command `OrgSettingsFacade` (and `layer-manager.ts`'s lazy group lookup) can issue. */
export const OrgPageActions = createActionGroup({
  source: 'Org Page',
  events: {
    'Refresh Requested': props<{ quiet: boolean }>(),
    'Create User Requested': props<{ request: CreateUserRequest }>(),
    'Set User Enabled Requested': props<{ id: string; enabled: boolean }>(),
    'Create Group Requested': props<{ request: CreateGroupRequest }>(),
    'Set Memberships Requested': props<{ userId: string; memberships: readonly UserMembership[] }>(),
    'Admin Set Password Requested': props<{ userId: string; newPassword: string }>(),
  },
});

/**
 * Every outcome. A mutation's own success carries the *whole* refreshed `users`/`groups` lists
 * (the effect re-reads both after mutating, exactly like `OrgStore`'s own
 * `await this.refresh({ quiet: true })`) plus a pre-built toast `message`, so the reducer and the
 * one `notifySuccess$` effect both stay generic across all five mutations.
 */
export const OrgApiActions = createActionGroup({
  source: 'Org API',
  events: {
    'Refresh Succeeded': props<{ users: readonly UserSummary[]; groups: readonly GroupSummary[] }>(),
    'Refresh Failed': props<{ error: string; quiet: boolean }>(),
    'Create User Succeeded': props<{
      users: readonly UserSummary[];
      groups: readonly GroupSummary[];
      user: UserSummary;
      message: string;
    }>(),
    'Create User Failed': props<{ error: string }>(),
    'Set User Enabled Succeeded': props<{
      users: readonly UserSummary[];
      groups: readonly GroupSummary[];
      user: UserSummary;
      message: string;
    }>(),
    'Set User Enabled Failed': props<{ error: string }>(),
    'Create Group Succeeded': props<{
      users: readonly UserSummary[];
      groups: readonly GroupSummary[];
      group: GroupSummary;
      message: string;
    }>(),
    'Create Group Failed': props<{ error: string }>(),
    'Set Memberships Succeeded': props<{
      users: readonly UserSummary[];
      groups: readonly GroupSummary[];
      user: UserSummary;
      message: string;
    }>(),
    'Set Memberships Failed': props<{ error: string }>(),
    'Admin Set Password Succeeded': props<{ message: string }>(),
    'Admin Set Password Failed': props<{ error: string }>(),
  },
});
