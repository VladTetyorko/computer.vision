import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { buildGroupTree } from '../org-logic';
import { OrgApiActions, OrgPageActions } from './org.actions';
import { initialOrgState } from './org.model';

export const orgFeature = createFeature({
  name: 'org',
  reducer: createReducer(
    initialOrgState,
    on(OrgPageActions.refreshRequested, (state) => ({ ...state, loading: true })),
    on(OrgApiActions.refreshSucceeded, (state, { users, groups }) => ({
      ...state,
      users,
      groups,
      loading: false,
      loaded: true,
    })),
    on(OrgApiActions.refreshFailed, (state) => ({ ...state, loading: false })),
    // Every mutation re-reads the whole list rather than patching one entry locally (the same
    // "single source of truth" shape `OrgStore` used) — `loading`/`loaded` are refresh's own
    // concern, so a mutation's success only ever refreshes `users`/`groups`.
    on(OrgApiActions.createUserSucceeded, (state, { users, groups }) => ({ ...state, users, groups, loaded: true })),
    on(OrgApiActions.setUserEnabledSucceeded, (state, { users, groups }) => ({
      ...state,
      users,
      groups,
      loaded: true,
    })),
    on(OrgApiActions.createGroupSucceeded, (state, { users, groups }) => ({ ...state, users, groups, loaded: true })),
    on(OrgApiActions.setMembershipsSucceeded, (state, { users, groups }) => ({
      ...state,
      users,
      groups,
      loaded: true,
    })),
    // adminSetPassword touches no local list — `OrgStore` itself had no `mustChangePassword` copy to
    // flip; the manage-user panel re-reads the true value the next time `users` refreshes.
  ),
  extraSelectors: ({ selectGroups }) => ({
    /** The group hierarchy derived from the flat list (`org-logic.ts#buildGroupTree`) — cycle-safe,
     *  sorted, recomputed only when `groups` changes. */
    selectGroupTree: createSelector(selectGroups, buildGroupTree),
  }),
});
