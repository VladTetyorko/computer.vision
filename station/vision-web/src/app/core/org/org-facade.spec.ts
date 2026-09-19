import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { GroupSummary, UserSummary } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { provideAppState } from '../state/app-state';
import { provideOrgState } from './state/org.providers';
import { OrgFacade } from './org-facade';

function user(overrides: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'u-1',
    username: 'pat',
    displayName: 'Pat Pilot',
    email: 'pat@example.com',
    enabled: true,
    memberships: [],
    ...overrides,
  };
}

function group(overrides: Partial<GroupSummary> = {}): GroupSummary {
  return { id: 'g-1', name: 'HQ', ...overrides };
}

/**
 * The org slice end to end — facade → action → effect (real HTTP call through a stub `VisionApi`) →
 * reducer → toast. Replaces `OrgStore` (which had no spec of its own before this wave).
 */
describe('OrgFacade', () => {
  let api: {
    listUsers: ReturnType<typeof vi.fn>;
    listGroups: ReturnType<typeof vi.fn>;
    createUser: ReturnType<typeof vi.fn>;
    setUserEnabled: ReturnType<typeof vi.fn>;
    createGroup: ReturnType<typeof vi.fn>;
    setMemberships: ReturnType<typeof vi.fn>;
    adminSetPassword: ReturnType<typeof vi.fn>;
  };
  let toasts: { ok: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      listUsers: vi.fn().mockResolvedValue([user()]),
      listGroups: vi.fn().mockResolvedValue([group()]),
      createUser: vi.fn().mockResolvedValue(user()),
      setUserEnabled: vi.fn().mockResolvedValue(user({ enabled: false })),
      createGroup: vi.fn().mockResolvedValue(group()),
      setMemberships: vi.fn().mockResolvedValue(user()),
      adminSetPassword: vi.fn().mockResolvedValue(undefined),
    };
    toasts = { ok: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [provideAppState(), provideOrgState(), OrgFacade, { provide: VisionApi, useValue: api }, { provide: ToastService, useValue: toasts }],
    });
  });

  it('starts empty and not loaded', () => {
    const facade = TestBed.inject(OrgFacade);
    expect(facade.users()).toEqual([]);
    expect(facade.groups()).toEqual([]);
    expect(facade.loaded()).toBe(false);
    expect(facade.loading()).toBe(false);
  });

  it('refresh() populates users/groups and flips loaded, without a toast', async () => {
    const facade = TestBed.inject(OrgFacade);
    await facade.refresh();
    expect(facade.users()).toEqual([user()]);
    expect(facade.groups()).toEqual([group()]);
    expect(facade.loaded()).toBe(true);
    expect(toasts.ok).not.toHaveBeenCalled();
    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('refresh() toasts on failure unless quiet', async () => {
    api.listUsers.mockRejectedValue(new Error('down'));
    const facade = TestBed.inject(OrgFacade);

    await facade.refresh();
    expect(toasts.error).toHaveBeenCalledTimes(1);

    toasts.error.mockClear();
    await facade.refresh({ quiet: true });
    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('createUser() returns the created user, refreshes the lists, and toasts ok', async () => {
    const facade = TestBed.inject(OrgFacade);
    const created = await facade.createUser({ username: 'pat', displayName: 'Pat Pilot', email: 'p@x.com', password: 'pw' });

    expect(created).toEqual(user());
    expect(facade.users()).toEqual([user()]);
    expect(toasts.ok).toHaveBeenCalledWith('Created Pat Pilot.');
  });

  it('createUser() returns null and toasts an error on failure', async () => {
    api.createUser.mockRejectedValue(new Error('409 Conflict'));
    const facade = TestBed.inject(OrgFacade);

    const created = await facade.createUser({ username: 'pat', displayName: 'Pat', email: 'p@x.com', password: 'pw' });
    expect(created).toBeNull();
    expect(toasts.error).toHaveBeenCalled();
  });

  it('setUserEnabled() returns the updated user and refreshes', async () => {
    const facade = TestBed.inject(OrgFacade);
    const updated = await facade.setUserEnabled('u-1', false);
    expect(updated?.enabled).toBe(false);
    expect(toasts.ok).toHaveBeenCalledWith('Pat Pilot is now disabled.');
  });

  it('createGroup() returns the created group and refreshes', async () => {
    const facade = TestBed.inject(OrgFacade);
    const created = await facade.createGroup({ name: 'HQ' });
    expect(created).toEqual(group());
    expect(toasts.ok).toHaveBeenCalledWith('Created HQ.');
  });

  it('setMemberships() returns the updated user and refreshes', async () => {
    const facade = TestBed.inject(OrgFacade);
    const updated = await facade.setMemberships('u-1', [{ groupId: 'g-1', role: 'PILOT' }]);
    expect(updated).toEqual(user());
    expect(toasts.ok).toHaveBeenCalledWith("Updated Pat Pilot's memberships.");
  });

  it('adminSetPassword() resolves true on success and false on failure, each with its own toast', async () => {
    const facade = TestBed.inject(OrgFacade);
    expect(await facade.adminSetPassword('u-1', 'newpw')).toBe(true);
    expect(toasts.ok).toHaveBeenCalledWith('Password reset — they must change it at next sign-in.');

    api.adminSetPassword.mockRejectedValue(new Error('403'));
    expect(await facade.adminSetPassword('u-1', 'newpw')).toBe(false);
    expect(toasts.error).toHaveBeenCalled();
  });

  it('groupTree derives from groups after a refresh', async () => {
    api.listGroups.mockResolvedValue([group({ id: 'root' }), group({ id: 'child', parentGroupId: 'root' })]);
    const facade = TestBed.inject(OrgFacade);
    await facade.refresh();
    expect(facade.groupTree()).toHaveLength(1);
    expect(facade.groupTree()[0].children).toHaveLength(1);
  });
});
