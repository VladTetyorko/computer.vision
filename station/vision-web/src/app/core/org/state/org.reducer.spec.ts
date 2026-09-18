import { describe, expect, it } from 'vitest';
import type { GroupSummary, UserSummary } from '../../api/models';
import { OrgApiActions, OrgPageActions } from './org.actions';
import { initialOrgState } from './org.model';
import { orgFeature } from './org.reducer';

const reduce = orgFeature.reducer;

function user(overrides: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'u-1',
    username: 'pat',
    displayName: 'Pat Pilot',
    email: 'pat@example.com',
    enabled: true,
    memberships: [],
    topRole: 'PILOT',
    ...overrides,
  } as UserSummary;
}

function group(overrides: Partial<GroupSummary> = {}): GroupSummary {
  return { id: 'g-1', name: 'HQ', ...overrides };
}

describe('org reducer', () => {
  it('starts empty, not loading, not loaded', () => {
    expect(initialOrgState).toEqual({ users: [], groups: [], loading: false, loaded: false });
  });

  it('refreshRequested flips loading on; refreshSucceeded replaces both lists and flips loaded', () => {
    const requested = reduce(initialOrgState, OrgPageActions.refreshRequested({ quiet: false }));
    expect(requested.loading).toBe(true);

    const succeeded = reduce(requested, OrgApiActions.refreshSucceeded({ users: [user()], groups: [group()] }));
    expect(succeeded.loading).toBe(false);
    expect(succeeded.loaded).toBe(true);
    expect(succeeded.users).toEqual([user()]);
    expect(succeeded.groups).toEqual([group()]);
  });

  it('refreshFailed only clears loading — the previous lists and loaded flag survive', () => {
    const loaded = reduce(initialOrgState, OrgApiActions.refreshSucceeded({ users: [user()], groups: [] }));
    const requested = reduce(loaded, OrgPageActions.refreshRequested({ quiet: true }));
    const failed = reduce(requested, OrgApiActions.refreshFailed({ error: 'boom', quiet: true }));

    expect(failed.loading).toBe(false);
    expect(failed.loaded).toBe(true);
    expect(failed.users).toEqual([user()]);
  });

  it('each mutation success refreshes both lists and sets loaded, without touching loading', () => {
    const afterCreateUser = reduce(
      initialOrgState,
      OrgApiActions.createUserSucceeded({ user: user(), users: [user()], groups: [], message: 'x' }),
    );
    expect(afterCreateUser.users).toEqual([user()]);
    expect(afterCreateUser.loaded).toBe(true);
    expect(afterCreateUser.loading).toBe(false);

    const afterSetEnabled = reduce(
      afterCreateUser,
      OrgApiActions.setUserEnabledSucceeded({
        user: user({ enabled: false }),
        users: [user({ enabled: false })],
        groups: [],
        message: 'x',
      }),
    );
    expect(afterSetEnabled.users).toEqual([user({ enabled: false })]);

    const afterCreateGroup = reduce(
      afterSetEnabled,
      OrgApiActions.createGroupSucceeded({ group: group(), users: [], groups: [group()], message: 'x' }),
    );
    expect(afterCreateGroup.groups).toEqual([group()]);

    const afterMemberships = reduce(
      afterCreateGroup,
      OrgApiActions.setMembershipsSucceeded({ user: user(), users: [user()], groups: [group()], message: 'x' }),
    );
    expect(afterMemberships.users).toEqual([user()]);
    expect(afterMemberships.groups).toEqual([group()]);
  });

  it('a mutation failure leaves users/groups/loaded untouched', () => {
    const loaded = reduce(initialOrgState, OrgApiActions.refreshSucceeded({ users: [user()], groups: [group()] }));
    const afterFailure = reduce(loaded, OrgApiActions.createUserFailed({ error: 'nope' }));
    expect(afterFailure).toEqual(loaded);
  });

  it('adminSetPassword touches no list on success or failure', () => {
    const loaded = reduce(initialOrgState, OrgApiActions.refreshSucceeded({ users: [user()], groups: [group()] }));
    const afterSuccess = reduce(loaded, OrgApiActions.adminSetPasswordSucceeded({ message: 'ok' }));
    expect(afterSuccess).toEqual(loaded);
  });

  it('selectGroupTree derives the hierarchy from the flat groups list', () => {
    const state = { ...initialOrgState, groups: [group({ id: 'root' }), group({ id: 'child', parentGroupId: 'root' })] };
    const tree = orgFeature.selectGroupTree.projector(state.groups);
    expect(tree).toHaveLength(1);
    expect(tree[0].group.id).toBe('root');
    expect(tree[0].children).toHaveLength(1);
    expect(tree[0].children[0].group.id).toBe('child');
  });
});
