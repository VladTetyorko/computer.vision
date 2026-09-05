import { describe, expect, it } from 'vitest';
import type { GroupSummary, UserSummary } from '../api/models';
import { buildSetupChecklist, hasOnlySeededUsers, isFreshStation } from './setup-checklist-logic';

function user(partial: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'u-0',
    username: 'user',
    displayName: 'User',
    email: 'user@example.com',
    enabled: true,
    memberships: [],
    ...partial,
  };
}

function group(partial: Partial<GroupSummary> = {}): GroupSummary {
  return { id: 'g-0', name: 'Group', ...partial };
}

const seedUsers: readonly UserSummary[] = [
  user({ userId: 'u-admin', username: 'admin', memberships: [{ groupId: 'root', role: 'ADMIN' }] }),
  user({ userId: 'u-manager', username: 'manager', memberships: [{ groupId: 'root', role: 'MANAGER' }] }),
  user({ userId: 'u-pilot', username: 'pilot', memberships: [{ groupId: 'root', role: 'PILOT' }] }),
];

describe('hasOnlySeededUsers', () => {
  it('true for exactly the three seeded usernames', () => {
    expect(hasOnlySeededUsers(seedUsers)).toBe(true);
  });

  it('true for an empty user list too (nothing loaded yet is not "beyond the seed")', () => {
    expect(hasOnlySeededUsers([])).toBe(true);
  });

  it('false the moment any other username exists, seeded users still present or not', () => {
    expect(hasOnlySeededUsers([...seedUsers, user({ userId: 'u-real', username: 'jane' })])).toBe(false);
  });

  it('false for a single non-seeded user', () => {
    expect(hasOnlySeededUsers([user({ username: 'jane' })])).toBe(false);
  });
});

describe('isFreshStation', () => {
  it('fresh: only seeded users, and assets already exist (e.g. a demo fleet added before onboarding)', () => {
    expect(isFreshStation(seedUsers, 12)).toBe(true);
  });

  it('fresh: real users exist, but zero assets', () => {
    expect(isFreshStation([...seedUsers, user({ username: 'jane' })], 0)).toBe(true);
  });

  it('fresh: both — brand new install, nobody has done anything', () => {
    expect(isFreshStation(seedUsers, 0)).toBe(true);
  });

  it('not fresh once real users exist AND at least one asset exists', () => {
    expect(isFreshStation([...seedUsers, user({ username: 'jane' })], 3)).toBe(false);
  });
});

describe('buildSetupChecklist', () => {
  it('a brand-new station: every row undone, linking to the page that does it', () => {
    const rows = buildSetupChecklist(seedUsers, [group({ id: 'root', name: 'Root' })], 0, false, false);
    expect(rows).toEqual([
      { id: 'create-group', label: 'Create a group', done: false, to: '/org' },
      { id: 'add-pilots', label: 'Add pilots', done: false, to: '/org' },
      { id: 'add-aircraft', label: 'Add your first aircraft', done: false, to: '/add-source' },
      { id: 'assign-pilot', label: 'Assign a pilot', done: false, to: '/manage/roster' },
      { id: 'secure-station', label: 'Secure this station', done: false, to: '/settings' },
    ]);
  });

  it('"create a group" ticks off once a second group exists beyond the seeded Root', () => {
    const groups = [group({ id: 'root', name: 'Root' }), group({ id: 'g-2', name: 'Field team' })];
    const rows = buildSetupChecklist(seedUsers, groups, 0, false, false);
    expect(rows.find((r) => r.id === 'create-group')?.done).toBe(true);
  });

  it('"add pilots" ticks off once a second PILOT-role user exists beyond the seeded pilot', () => {
    const users = [...seedUsers, user({ userId: 'u-2', username: 'jane', memberships: [{ groupId: 'root', role: 'PILOT' }] })];
    const rows = buildSetupChecklist(users, [group()], 0, false, false);
    expect(rows.find((r) => r.id === 'add-pilots')?.done).toBe(true);
  });

  it('"add pilots" stays undone for a second MANAGER — that is not a pilot', () => {
    const users = [...seedUsers, user({ userId: 'u-2', username: 'jane', memberships: [{ groupId: 'root', role: 'MANAGER' }] })];
    const rows = buildSetupChecklist(users, [group()], 0, false, false);
    expect(rows.find((r) => r.id === 'add-pilots')?.done).toBe(false);
  });

  it('"add your first aircraft" ticks off from totalAssets alone', () => {
    const rows = buildSetupChecklist(seedUsers, [group()], 1, false, false);
    expect(rows.find((r) => r.id === 'add-aircraft')?.done).toBe(true);
  });

  it('"assign a pilot" ticks off only when told an assignment exists', () => {
    const notYet = buildSetupChecklist(seedUsers, [group()], 1, false, false);
    const done = buildSetupChecklist(seedUsers, [group()], 1, true, false);
    expect(notYet.find((r) => r.id === 'assign-pilot')?.done).toBe(false);
    expect(done.find((r) => r.id === 'assign-pilot')?.done).toBe(true);
  });

  it('"secure this station" ticks off once auth is enabled — the one row with no in-app action', () => {
    const notYet = buildSetupChecklist(seedUsers, [group()], 1, false, false);
    const done = buildSetupChecklist(seedUsers, [group()], 1, false, true);
    expect(notYet.find((r) => r.id === 'secure-station')).toEqual({
      id: 'secure-station',
      label: 'Secure this station',
      done: false,
      to: '/settings',
    });
    expect(done.find((r) => r.id === 'secure-station')?.done).toBe(true);
  });
});
