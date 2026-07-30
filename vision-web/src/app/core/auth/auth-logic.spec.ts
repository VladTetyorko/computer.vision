import { describe, expect, it } from 'vitest';
import { initialsFor, needsLogin, roleLabel, topRoleLabel, type AuthStatus } from './auth-logic';
import type { MeResponse, Role } from '../api/models';

function meResponse(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    userId: 'u-1',
    username: 'pilot',
    displayName: 'Pat Pilot',
    email: 'pilot@example.com',
    memberships: [{ groupId: 'g-1', groupName: 'HQ', role: 'PILOT' }],
    topRole: 'PILOT',
    authEnabled: true,
    ...overrides,
  };
}

describe('roleLabel', () => {
  it.each<[Role, string]>([
    ['PILOT', 'Pilot'],
    ['MANAGER', 'Manager'],
    ['ADMIN', 'Admin'],
  ])('renders %s as %s', (role, label) => {
    expect(roleLabel(role)).toBe(label);
  });
});

describe('topRoleLabel', () => {
  it('renders the label for topRole, ignoring memberships', () => {
    expect(topRoleLabel(meResponse({ topRole: 'MANAGER' }))).toBe('Manager');
  });

  it('accepts anything carrying just a topRole (DTO-boundary minimal input)', () => {
    expect(topRoleLabel({ topRole: 'ADMIN' })).toBe('Admin');
  });
});

describe('initialsFor', () => {
  it('takes the first letter of the first and last word for a multi-word name', () => {
    expect(initialsFor('Pat Pilot')).toBe('PP');
    expect(initialsFor('Jane Amelia Doe')).toBe('JD');
  });

  it('takes the first two letters for a single-word name', () => {
    expect(initialsFor('admin')).toBe('AD');
    expect(initialsFor('x')).toBe('X');
  });

  it('always upper-cases', () => {
    expect(initialsFor('pat pilot')).toBe('PP');
  });

  it('collapses extra internal whitespace', () => {
    expect(initialsFor('Pat   Pilot')).toBe('PP');
  });

  it('falls back to "?" for an empty or whitespace-only name', () => {
    expect(initialsFor('')).toBe('?');
    expect(initialsFor('   ')).toBe('?');
  });
});

describe('needsLogin', () => {
  const statuses: readonly AuthStatus[] = ['loading', 'anon', 'authed'];
  const users: readonly (MeResponse | null)[] = [null, meResponse()];

  it('is always false when authEnabled is false, across every status/user combination', () => {
    for (const status of statuses) {
      for (const user of users) {
        expect(needsLogin(status, false, user)).toBe(false);
      }
    }
  });

  it('is true once the boot check concludes anon with no user, and auth is enabled', () => {
    expect(needsLogin('anon', true, null)).toBe(true);
  });

  it('is false while still loading, even with auth enabled — no redirect before the first check answers', () => {
    expect(needsLogin('loading', true, null)).toBe(false);
  });

  it('is false once authed, even with auth enabled', () => {
    expect(needsLogin('authed', true, meResponse())).toBe(false);
  });

  it('is false for the inconsistent anon-but-a-user-is-set combination — both signals must agree', () => {
    expect(needsLogin('anon', true, meResponse())).toBe(false);
  });
});
