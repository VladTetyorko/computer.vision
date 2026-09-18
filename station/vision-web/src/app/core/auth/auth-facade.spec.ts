import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import type { AuthCapability, MeResponse, Role, ScopeKind } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveFacade } from '../live/live-facade';
import { provideAppState } from '../state/app-state';
import { AuthFacade } from './auth-facade';

/** Mirrors the real `RoleAuthority`/`DefaultScopeResolver` policy table closely enough for a
 *  fixture — see `auth-logic.spec.ts`'s identical helper for the full reasoning. */
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

function stubApi(
  overrides: Partial<
    Record<'authMe' | 'authLogin' | 'authLogout' | 'changePassword' | 'bootstrapStatus' | 'bootstrap', ReturnType<typeof vi.fn>>
  > = {},
) {
  return {
    authMe: vi.fn().mockResolvedValue(null),
    authLogin: vi.fn(),
    authLogout: vi.fn().mockResolvedValue(undefined),
    changePassword: vi.fn().mockResolvedValue(undefined),
    bootstrapStatus: vi.fn().mockResolvedValue({ required: false }),
    bootstrap: vi.fn(),
    ...overrides,
  };
}

function stubRouter() {
  return { navigateByUrl: vi.fn().mockResolvedValue(true) };
}

/** `reconnect`/`stop` spied so `login`/`logout`'s own hooks into it are directly assertable. */
function stubLiveFacade() {
  return { reconnect: vi.fn(), stop: vi.fn() };
}

function create(
  api: ReturnType<typeof stubApi>,
  router: ReturnType<typeof stubRouter> = stubRouter(),
  liveStore: ReturnType<typeof stubLiveFacade> = stubLiveFacade(),
): AuthFacade {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      { provide: VisionApi, useValue: api },
      { provide: Router, useValue: router },
      { provide: LiveFacade, useValue: liveStore },
    ],
  });
  return TestBed.inject(AuthFacade);
}

/**
 * The auth slice end to end — facade → action → effect (real HTTP call through a stub `VisionApi`)
 * → reducer → facade signals, replacing `AuthStore`'s own equally end-to-end spec
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N2). Every case below is ported from
 * `auth-store.spec.ts` 1:1 — this facade's public surface is unchanged, only its DI identity is new.
 */
describe('AuthFacade', () => {
  it('starts loading', () => {
    const facade = create(stubApi());
    expect(facade.status()).toBe('loading');
  });

  it('a 401 (no session) resolves to anon, with authEnabled inferred true', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const facade = create(api);

    await facade.ready;

    expect(facade.status()).toBe('anon');
    expect(facade.user()).toBeNull();
    expect(facade.authEnabled()).toBe(true);
  });

  it('authEnabled=false resolves straight to authed with the dev admin — never a login screen', async () => {
    const devAdmin = meResponse({ authEnabled: false, username: 'admin', displayName: 'Dev Admin' });
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(devAdmin) });
    const facade = create(api);

    await facade.ready;

    expect(facade.status()).toBe('authed');
    expect(facade.user()).toEqual(devAdmin);
    expect(facade.authEnabled()).toBe(false);
  });

  it('an existing real session resolves straight to authed', async () => {
    const user = meResponse();
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(user) });
    const facade = create(api);

    await facade.ready;

    expect(facade.status()).toBe('authed');
    expect(facade.user()).toEqual(user);
    expect(facade.authEnabled()).toBe(true);
  });

  it('degrades to anon (authEnabled left at its default) when /me fails outright, e.g. the backend is unreachable', async () => {
    const api = stubApi({ authMe: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 })) });
    const facade = create(api);

    await facade.ready;

    expect(facade.status()).toBe('anon');
    expect(facade.user()).toBeNull();
    expect(facade.authEnabled()).toBe(false);
  });

  it('login: success authenticates and clears any prior error', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const facade = create(api);
    await facade.ready;

    const user = meResponse();
    api.authLogin.mockResolvedValue(user);

    const ok = await facade.login('pilot', 'pilot');

    expect(ok).toBe(true);
    expect(facade.status()).toBe('authed');
    expect(facade.user()).toEqual(user);
    expect(facade.loginError()).toBeNull();
    expect(facade.loginBusy()).toBe(false);
  });

  it('login: a 401 surfaces an inline error and leaves the facade anon', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const facade = create(api);
    await facade.ready;

    api.authLogin.mockRejectedValue(new HttpErrorResponse({ status: 401 }));

    const ok = await facade.login('pilot', 'wrong-password');

    expect(ok).toBe(false);
    expect(facade.status()).toBe('anon');
    expect(facade.user()).toBeNull();
    expect(facade.loginError()).toBe('Incorrect username or password.');
  });

  it('login: a non-401 failure still surfaces an error, via describeHttpError', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const facade = create(api);
    await facade.ready;

    api.authLogin.mockRejectedValue(new HttpErrorResponse({ status: 0 }));

    const ok = await facade.login('pilot', 'pilot');

    expect(ok).toBe(false);
    expect(facade.loginError()).toContain('Cannot reach the Vision backend');
  });

  it('login: busy is true only while the request is in flight', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const facade = create(api);
    await facade.ready;

    let resolveLogin: (value: MeResponse) => void = () => undefined;
    api.authLogin.mockReturnValue(new Promise<MeResponse>((resolve) => (resolveLogin = resolve)));

    const pending = facade.login('pilot', 'pilot');
    expect(facade.loginBusy()).toBe(true);

    resolveLogin(meResponse());
    await pending;

    expect(facade.loginBusy()).toBe(false);
  });

  it('logout: clears the session and routes to /login when auth is enabled', async () => {
    const user = meResponse({ authEnabled: true });
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(user) });
    const router = stubRouter();
    const facade = create(api, router);
    await facade.ready;

    await facade.logout();

    expect(api.authLogout).toHaveBeenCalled();
    expect(facade.user()).toBeNull();
    expect(facade.status()).toBe('anon');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('logout: routes to /fly when auth is disabled (dev parity)', async () => {
    const devAdmin = meResponse({ authEnabled: false });
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(devAdmin) });
    const router = stubRouter();
    const facade = create(api, router);
    await facade.ready;

    await facade.logout();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/fly');
  });

  it('logout: clears local state and still navigates even when the request itself fails', async () => {
    const user = meResponse();
    const api = stubApi({
      authMe: vi.fn().mockResolvedValue(user),
      authLogout: vi.fn().mockRejectedValue(new Error('offline')),
    });
    const router = stubRouter();
    const facade = create(api, router);
    await facade.ready;

    await facade.logout();

    expect(facade.user()).toBeNull();
    expect(facade.status()).toBe('anon');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('login: reconnects the live store on success (a fresh session supersedes whatever it was open under)', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null), authLogin: vi.fn().mockResolvedValue(meResponse()) });
    const liveStore = stubLiveFacade();
    const facade = create(api, stubRouter(), liveStore);
    await facade.ready;

    await facade.login('pilot', 'pilot');

    expect(liveStore.reconnect).toHaveBeenCalled();
    expect(liveStore.stop).not.toHaveBeenCalled();
  });

  it('login: does not touch the live store on a failed attempt', async () => {
    const api = stubApi({
      authMe: vi.fn().mockResolvedValue(null),
      authLogin: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 401 })),
    });
    const liveStore = stubLiveFacade();
    const facade = create(api, stubRouter(), liveStore);
    await facade.ready;

    await facade.login('pilot', 'wrong');

    expect(liveStore.reconnect).not.toHaveBeenCalled();
  });

  it('logout: stops the live store when auth was enabled', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(meResponse({ authEnabled: true })) });
    const liveStore = stubLiveFacade();
    const facade = create(api, stubRouter(), liveStore);
    await facade.ready;

    await facade.logout();

    expect(liveStore.stop).toHaveBeenCalled();
  });

  it('logout: leaves the live store alone in dev parity (authEnabled=false) — there was no real session change', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(meResponse({ authEnabled: false })) });
    const liveStore = stubLiveFacade();
    const facade = create(api, stubRouter(), liveStore);
    await facade.ready;

    await facade.logout();

    expect(liveStore.stop).not.toHaveBeenCalled();
  });

  describe('capabilities/scopeKind/mustChangePassword accessors', () => {
    it('read off the session once resolved', async () => {
      const user = meResponse({ topRole: 'MANAGER', mustChangePassword: true });
      const facade = create(stubApi({ authMe: vi.fn().mockResolvedValue(user) }));
      await facade.ready;

      expect(facade.capabilities()).toEqual(['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG']);
      expect(facade.scopeKind()).toBe('GROUPS');
      expect(facade.mustChangePassword()).toBe(true);
    });

    it('degrade to empty/undefined/false while there is no session', async () => {
      const facade = create(stubApi({ authMe: vi.fn().mockResolvedValue(null) }));
      await facade.ready;

      expect(facade.capabilities()).toEqual([]);
      expect(facade.scopeKind()).toBeUndefined();
      expect(facade.mustChangePassword()).toBe(false);
    });
  });

  describe('can', () => {
    it('is true iff the resolved session holds the capability', async () => {
      const user = meResponse({ topRole: 'PILOT' });
      const facade = create(stubApi({ authMe: vi.fn().mockResolvedValue(user) }));
      await facade.ready;

      expect(facade.can('OPERATE_PAYLOAD')).toBe(true);
      expect(facade.can('MANAGE_ORG')).toBe(false);
    });
  });

  describe("sessionExpired (docs/plans/active/AUTH-ROLES-PLAN.md §3.7 clause 2 — session-interceptor.ts's only caller)", () => {
    it('onFlyRoute=true sets reauthRequired and touches nothing else — no session clear, no navigation implied', async () => {
      const user = meResponse();
      const router = stubRouter();
      const facade = create(stubApi({ authMe: vi.fn().mockResolvedValue(user) }), router);
      await facade.ready;

      facade.sessionExpired(true);

      expect(facade.reauthRequired()).toBe(true);
      expect(facade.user()).toEqual(user); // untouched — the cockpit behind the overlay stays exactly as it was
      expect(facade.status()).toBe('authed');
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('onFlyRoute=false clears the local session but leaves navigation to the caller', async () => {
      const user = meResponse();
      const router = stubRouter();
      const facade = create(stubApi({ authMe: vi.fn().mockResolvedValue(user) }), router);
      await facade.ready;

      facade.sessionExpired(false);

      expect(facade.reauthRequired()).toBe(false);
      expect(facade.user()).toBeNull();
      expect(facade.status()).toBe('anon');
      expect(router.navigateByUrl).not.toHaveBeenCalled(); // session-interceptor.ts's own job, not this method's
    });

    it('is superseded by the next resolved session (a successful reauth clears it exactly like an ordinary login)', async () => {
      const api = stubApi({ authMe: vi.fn().mockResolvedValue(meResponse()), authLogin: vi.fn().mockResolvedValue(meResponse()) });
      const facade = create(api);
      await facade.ready;

      facade.sessionExpired(true);
      expect(facade.reauthRequired()).toBe(true);

      await facade.login('pilot', 'pilot');

      expect(facade.reauthRequired()).toBe(false);
    });
  });

  describe('changePassword', () => {
    it('success re-fetches /me and returns null', async () => {
      const refreshed = meResponse({ mustChangePassword: false });
      const api = stubApi({ authMe: vi.fn().mockResolvedValue(refreshed), changePassword: vi.fn().mockResolvedValue(undefined) });
      const facade = create(api);
      await facade.ready;

      const result = await facade.changePassword('old-pw', 'new-pw');

      expect(result).toBeNull();
      expect(api.changePassword).toHaveBeenCalledWith({ currentPassword: 'old-pw', newPassword: 'new-pw' });
      expect(api.authMe).toHaveBeenCalledTimes(2); // once at boot, once refreshing post-change
    });

    it('a 401 (wrong current password) returns a specific message, not the generic session-death copy', async () => {
      const api = stubApi({
        authMe: vi.fn().mockResolvedValue(meResponse()),
        changePassword: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 401 })),
      });
      const facade = create(api);
      await facade.ready;

      const result = await facade.changePassword('wrong-pw', 'new-pw');

      expect(result).toBe('Incorrect current password.');
    });

    it('any other failure degrades via describeHttpError', async () => {
      const api = stubApi({
        authMe: vi.fn().mockResolvedValue(meResponse()),
        changePassword: vi
          .fn()
          .mockRejectedValue(new HttpErrorResponse({ status: 400, error: { error: 'WEAK_PASSWORD', message: 'Too short.' } })),
      });
      const facade = create(api);
      await facade.ready;

      const result = await facade.changePassword('old-pw', '123');

      expect(result).toBe('Too short.');
    });
  });

  describe('bootstrapRequired', () => {
    it('reflects the latch as the server reports it', async () => {
      const api = stubApi({ bootstrapStatus: vi.fn().mockResolvedValue({ required: true }) });
      const facade = create(api);
      await facade.ready;

      expect(await facade.bootstrapRequired()).toBe(true);
    });

    it('reflects false once the latch has closed', async () => {
      const api = stubApi({ bootstrapStatus: vi.fn().mockResolvedValue({ required: false }) });
      const facade = create(api);
      await facade.ready;

      expect(await facade.bootstrapRequired()).toBe(false);
    });

    it('fails safe to false (never throws) when the check itself is unreachable', async () => {
      const api = stubApi({ bootstrapStatus: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 })) });
      const facade = create(api);
      await facade.ready;
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

      const result = await facade.bootstrapRequired();

      expect(result).toBe(false);
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });
  });

  describe('bootstrap', () => {
    it('success applies the new admin session and reconnects the live store, returning null', async () => {
      const created = meResponse({ topRole: 'ADMIN', username: 'root-admin' });
      const api = stubApi({ bootstrap: vi.fn().mockResolvedValue(created) });
      const liveStore = stubLiveFacade();
      const facade = create(api, stubRouter(), liveStore);
      await facade.ready;

      const result = await facade.bootstrap({
        username: 'root-admin',
        displayName: 'Root Admin',
        email: 'root@example.com',
        password: 'a-strong-password',
      });

      expect(result).toBeNull();
      expect(facade.status()).toBe('authed');
      expect(facade.user()).toEqual(created);
      expect(liveStore.reconnect).toHaveBeenCalled();
    });

    it('a failure (e.g. the latch already closed) returns the server message and never throws', async () => {
      const api = stubApi({
        bootstrap: vi
          .fn()
          .mockRejectedValue(
            new HttpErrorResponse({ status: 409, error: { error: 'ALREADY_INITIALIZED', message: 'This station already has an administrator.' } }),
          ),
      });
      const facade = create(api);
      await facade.ready;

      const result = await facade.bootstrap({
        username: 'root-admin',
        displayName: 'Root Admin',
        email: 'root@example.com',
        password: 'a-strong-password',
      });

      expect(result).toBe('This station already has an administrator.');
      expect(facade.status()).toBe('anon'); // no session was applied
    });
  });
});
