import { describe, expect, it } from 'vitest';
import type { AssignedPilot, Membership, Role, UserSummary } from '../api/models';
import {
  creatorOwnershipGroup,
  assignedPilotName,
  custodianCandidateName,
  custodianPickerGroups,
  defaultPilotSelection,
  pilotsAnywhere,
  pilotsInGroup,
} from './pilot-logic';

function membership(groupId: string, role: Role): Membership {
  return { groupId, groupName: groupId, role };
}

function user(partial: Partial<UserSummary> & Pick<UserSummary, 'userId'>): UserSummary {
  return {
    username: partial.userId,
    displayName: partial.userId,
    email: `${partial.userId}@example.test`,
    enabled: true,
    memberships: [],
    ...partial,
  };
}

function pilot(partial: Partial<AssignedPilot> & Pick<AssignedPilot, 'userId'>): AssignedPilot {
  return { role: 'PILOT', ...partial };
}

describe('creatorOwnershipGroup', () => {
  it('has no group for a membership-less account', () => {
    expect(creatorOwnershipGroup([])).toBeUndefined();
  });

  it('takes the single membership when there is only one', () => {
    const only = membership('g-1', 'PILOT');
    expect(creatorOwnershipGroup([only])).toBe(only);
  });

  it('takes the highest-ranked role, whatever the order', () => {
    const p = membership('g-1', 'PILOT');
    const m = membership('g-2', 'MANAGER');
    const a = membership('g-3', 'ADMIN');
    expect(creatorOwnershipGroup([p, m])).toBe(m);
    expect(creatorOwnershipGroup([m, a, p])).toBe(a);
  });

  it('breaks a rank tie by encounter order, mirroring the backend', () => {
    const first = membership('g-1', 'MANAGER');
    const second = membership('g-2', 'MANAGER');
    expect(creatorOwnershipGroup([first, second])).toBe(first);
  });
});

describe('pilotsInGroup', () => {
  const here = user({ userId: 'u-here', memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
  const elsewhere = user({ userId: 'u-there', memberships: [{ groupId: 'g-2', role: 'PILOT' }] });
  const manager = user({ userId: 'u-mgr', memberships: [{ groupId: 'g-1', role: 'MANAGER' }] });
  const disabled = user({ userId: 'u-off', enabled: false, memberships: [{ groupId: 'g-1', role: 'PILOT' }] });

  it('keeps only enabled PILOTs of that one group', () => {
    expect(pilotsInGroup([here, elsewhere, manager, disabled], 'g-1')).toEqual([here]);
  });

  it('offers nobody rather than everybody when the group is unresolved', () => {
    expect(pilotsInGroup([here, elsewhere], undefined)).toEqual([]);
  });
});

describe('pilotsAnywhere', () => {
  it('keeps every enabled PILOT regardless of group, and no one else', () => {
    const a = user({ userId: 'u-a', memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
    const b = user({ userId: 'u-b', memberships: [{ groupId: 'g-9', role: 'PILOT' }] });
    const viewer = user({ userId: 'u-v', memberships: [{ groupId: 'g-1', role: 'VIEWER' }] });
    const off = user({ userId: 'u-off', enabled: false, memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
    expect(pilotsAnywhere([a, b, viewer, off])).toEqual([a, b]);
  });
});

describe('defaultPilotSelection', () => {
  it('preselects the creator only when their own ownership membership is PILOT', () => {
    expect(defaultPilotSelection('u-1', membership('g-1', 'PILOT'))).toEqual(['u-1']);
    expect(defaultPilotSelection('u-1', membership('g-1', 'MANAGER'))).toEqual([]);
    expect(defaultPilotSelection('u-1', undefined)).toEqual([]);
  });
});

describe('custodianPickerGroups', () => {
  const anna = user({ userId: 'u-anna', displayName: 'Anna K', memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
  const bob = user({ userId: 'u-bob', displayName: 'Bob R', memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
  const clara = user({ userId: 'u-clara', displayName: 'Clara T', memberships: [{ groupId: 'g-2', role: 'PILOT' }] });
  const mgr = user({ userId: 'u-mgr', displayName: 'Mo Manager', memberships: [{ groupId: 'g-1', role: 'MANAGER' }] });
  const users = [bob, anna, mgr, clara];

  it('puts this asset PILOT-seat holders first, names them from the wire, and never repeats them below', () => {
    const groups = custodianPickerGroups({
      users,
      assignedPilots: [pilot({ userId: 'u-anna', displayName: 'Anna K' })],
      groupId: 'g-1',
    });
    expect(groups.assigned).toEqual([{ userId: 'u-anna', name: 'Anna K', seat: 'Assigned' }]);
    expect(groups.pilots.map((c) => c.userId)).toEqual(['u-bob']);
    // Clara is a pilot, but of another group — rung 1 found somebody, so rung 2 never runs and she
    // is not promoted into "Other pilots". She stays reachable under "Show everyone", by name.
    expect(groups.everyone.map((c) => c.userId)).toEqual(['u-clara', 'u-mgr']);
  });

  it('leaves a CREW seat-holder out of "Assigned pilots" — issuing would not grant them a pilot seat', () => {
    const groups = custodianPickerGroups({
      users,
      assignedPilots: [pilot({ userId: 'u-anna', role: 'CREW', displayName: 'Anna K' })],
      groupId: 'g-1',
    });
    expect(groups.assigned).toEqual([]);
    expect(groups.pilots.map((c) => c.userId)).toEqual(['u-anna', 'u-bob']);
  });

  it('falls back to every visible pilot when the asset group resolves to nobody (dev-parity gap)', () => {
    const groups = custodianPickerGroups({ users, assignedPilots: [], groupId: 'a-group-nobody-pilots-in' });
    expect(groups.pilots.map((c) => c.userId)).toEqual(['u-anna', 'u-bob', 'u-clara']);
    expect(groups.everyone.map((c) => c.userId)).toEqual(['u-mgr']);
  });

  it('prefers the group rung whenever it finds anybody at all', () => {
    const groups = custodianPickerGroups({ users, assignedPilots: [], groupId: 'g-2' });
    expect(groups.pilots.map((c) => c.userId)).toEqual(['u-clara']);
    expect(groups.everyone.map((c) => c.userId)).toEqual(['u-anna', 'u-bob', 'u-mgr']);
  });

  it('falls back to a truncated id for an assignee no name reached', () => {
    const groups = custodianPickerGroups({
      users: [],
      assignedPilots: [pilot({ userId: '3f2a91c4-0000-0000-0000-000000000000' })],
      groupId: undefined,
    });
    expect(groups.assigned[0].name).toBe('3f2a91c4…');
  });

  it('hides a disabled account from every group', () => {
    const off = user({ userId: 'u-off', displayName: 'Old Account', enabled: false, memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
    const groups = custodianPickerGroups({ users: [anna, off], assignedPilots: [], groupId: 'g-1' });
    expect([...groups.pilots, ...groups.everyone].map((c) => c.userId)).toEqual(['u-anna']);
  });
});

describe('custodianCandidateName', () => {
  it('finds a name in any of the three groups, and answers undefined for a stale pick', () => {
    const groups = custodianPickerGroups({
      users: [user({ userId: 'u-mgr', displayName: 'Mo Manager' })],
      assignedPilots: [pilot({ userId: 'u-anna', displayName: 'Anna K' })],
      groupId: undefined,
    });
    expect(custodianCandidateName(groups, 'u-anna')).toBe('Anna K');
    expect(custodianCandidateName(groups, 'u-mgr')).toBe('Mo Manager');
    expect(custodianCandidateName(groups, 'u-gone')).toBeUndefined();
  });
});

describe('assignedPilotName', () => {
  const names = new Map([['u-anna', 'Anna Petrenko']]);

  it('prefers the name the wire resolved, then the username', () => {
    expect(assignedPilotName(pilot({ userId: 'u-anna', displayName: 'Anna K', username: 'anna' }), names)).toBe('Anna K');
    expect(assignedPilotName(pilot({ userId: 'u-anna', username: 'anna' }), names)).toBe('anna');
  });

  it('falls back to the org user-list join before giving up on a name', () => {
    expect(assignedPilotName(pilot({ userId: 'u-anna' }), names)).toBe('Anna Petrenko');
  });

  it('truncates an id nothing can name rather than printing a bare UUID', () => {
    expect(assignedPilotName(pilot({ userId: '3f2a91c4-1111-2222-3333-444455556666' }), new Map())).toBe('3f2a91c4…');
  });
});
