import { describe, expect, it } from 'vitest';
import {
  anonymousDestination,
  canAdminister,
  hasCapability,
  initialsFor,
  needsLogin,
  roleLabel,
  topRoleLabel,
  type AuthStatus,
} from './auth-logic';
import type { AuthCapability, MeResponse, Role, ScopeKind } from '../api/models';

/** Mirrors the real `RoleAuthority`/`DefaultScopeResolver` policy table (docs/plans/active/AUTH-ROLES-PLAN.md
 *  §3.1/§3.2) closely enough for a fixture — a call site overriding just `topRole` still gets a
 *  self-consistent `capabilities`/`scopeKind` pair, without every existing call in this file having
 *  to spell both out. */
const ROLE_CAPABILITIES: Record<Role, readonly AuthCapability[]> = {
  VIEWER: [],
  PILOT: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'],
  MANAGER: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
  ADMIN: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
};
const ROLE_SCOPE_KIND: Record<Role, ScopeKind> = {
  VIEWER: 'GROUPS',
  PILOT: 'ASSIGNED_ASSETS',
  MANAGER: 'GROUPS',
  ADMIN: 'UNBOUNDED',
};

function meResponse(overrides: Partial<MeResponse> = {}): MeResponse {
  const topRole = overrides.topRole ?? 'PILOT';
  return {
    userId: 'u-1',
    username: 'pilot',
    displayName: 'Pat Pilot',
    email: 'pilot@example.com',
    memberships: [{ groupId: 'g-1', groupName: 'HQ', role: 'PILOT' }],
    topRole,
    authEnabled: true,
    capabilities: ROLE_CAPABILITIES[topRole],
    scopeKind: ROLE_SCOPE_KIND[topRole],
    mustChangePassword: false,
    ...overrides,
  };
}

describe('roleLabel', () => {
  it.each<[Role, string]>([
    ['VIEWER', 'Viewer'],
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

describe('hasCapability', () => {
  it('is true when the capability is present', () => {
    expect(hasCapability(['MANAGE_ORG', 'MANAGE_FLEET'], 'MANAGE_ORG')).toBe(true);
  });

  it('is false when it is absent', () => {
    expect(hasCapability(['OPERATE_PAYLOAD'], 'MANAGE_ORG')).toBe(false);
  });

  it('is false (never throws) for null/undefined — a not-yet-loaded session', () => {
    expect(hasCapability(null, 'MANAGE_ORG')).toBe(false);
    expect(hasCapability(undefined, 'MANAGE_ORG')).toBe(false);
  });

  it('is false for an empty list — a VIEWER session', () => {
    expect(hasCapability([], 'OPERATE_PAYLOAD')).toBe(false);
  });
});

describe('canAdminister', () => {
  it('is true only for UNBOUNDED', () => {
    expect(canAdminister('UNBOUNDED')).toBe(true);
  });

  it('is false for GROUPS and ASSIGNED_ASSETS', () => {
    expect(canAdminister('GROUPS')).toBe(false);
    expect(canAdminister('ASSIGNED_ASSETS')).toBe(false);
  });

  it('is false (never throws) for null/undefined', () => {
    expect(canAdminister(null)).toBe(false);
    expect(canAdminister(undefined)).toBe(false);
  });
});

describe('anonymousDestination', () => {
  it('sends a fresh, nobody-to-sign-in-as station to /setup', () => {
    expect(anonymousDestination(true)).toBe('/setup');
  });

  it('sends every other station to /login', () => {
    expect(anonymousDestination(false)).toBe('/login');
  });
});
