import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AuthStore } from './auth-store';
import { VisionApi } from '../api/vision-api';
import type { MeResponse } from '../api/models';

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

function stubApi(overrides: Partial<Record<'authMe' | 'authLogin' | 'authLogout', ReturnType<typeof vi.fn>>> = {}) {
  return {
    authMe: vi.fn().mockResolvedValue(null),
    authLogin: vi.fn(),
    authLogout: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

function stubRouter() {
  return { navigateByUrl: vi.fn().mockResolvedValue(true) };
}

function create(api: ReturnType<typeof stubApi>, router: ReturnType<typeof stubRouter> = stubRouter()): AuthStore {
  TestBed.configureTestingModule({
    providers: [AuthStore, { provide: VisionApi, useValue: api }, { provide: Router, useValue: router }],
  });
  return TestBed.inject(AuthStore);
}

describe('AuthStore', () => {
  it('starts loading', () => {
    const store = create(stubApi());
    expect(store.status()).toBe('loading');
  });

  it('a 401 (no session) resolves to anon, with authEnabled inferred true', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const store = create(api);

    await store.ready;

    expect(store.status()).toBe('anon');
    expect(store.user()).toBeNull();
    expect(store.authEnabled()).toBe(true);
  });

  it('authEnabled=false resolves straight to authed with the dev admin — never a login screen', async () => {
    const devAdmin = meResponse({ authEnabled: false, username: 'admin', displayName: 'Dev Admin' });
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(devAdmin) });
    const store = create(api);

    await store.ready;

    expect(store.status()).toBe('authed');
    expect(store.user()).toEqual(devAdmin);
    expect(store.authEnabled()).toBe(false);
  });

  it('an existing real session resolves straight to authed', async () => {
    const user = meResponse();
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(user) });
    const store = create(api);

    await store.ready;

    expect(store.status()).toBe('authed');
    expect(store.user()).toEqual(user);
    expect(store.authEnabled()).toBe(true);
  });

  it('degrades to anon (authEnabled left at its default) when /me fails outright, e.g. the backend is unreachable', async () => {
    const api = stubApi({ authMe: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 })) });
    const store = create(api);

    await store.ready;

    expect(store.status()).toBe('anon');
    expect(store.user()).toBeNull();
    expect(store.authEnabled()).toBe(false);
  });

  it('login: success authenticates and clears any prior error', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const store = create(api);
    await store.ready;

    const user = meResponse();
    api.authLogin.mockResolvedValue(user);

    const ok = await store.login('pilot', 'pilot');

    expect(ok).toBe(true);
    expect(store.status()).toBe('authed');
    expect(store.user()).toEqual(user);
    expect(store.loginError()).toBeNull();
    expect(store.loginBusy()).toBe(false);
  });

  it('login: a 401 surfaces an inline error and leaves the store anon', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const store = create(api);
    await store.ready;

    api.authLogin.mockRejectedValue(new HttpErrorResponse({ status: 401 }));

    const ok = await store.login('pilot', 'wrong-password');

    expect(ok).toBe(false);
    expect(store.status()).toBe('anon');
    expect(store.user()).toBeNull();
    expect(store.loginError()).toBe('Incorrect username or password.');
  });

  it('login: a non-401 failure still surfaces an error, via describeHttpError', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const store = create(api);
    await store.ready;

    api.authLogin.mockRejectedValue(new HttpErrorResponse({ status: 0 }));

    const ok = await store.login('pilot', 'pilot');

    expect(ok).toBe(false);
    expect(store.loginError()).toContain('Cannot reach the Vision backend');
  });

  it('login: busy is true only while the request is in flight, and no double-submit races the flag', async () => {
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(null) });
    const store = create(api);
    await store.ready;

    let resolveLogin: (value: MeResponse) => void = () => undefined;
    api.authLogin.mockReturnValue(new Promise<MeResponse>((resolve) => (resolveLogin = resolve)));

    const pending = store.login('pilot', 'pilot');
    expect(store.loginBusy()).toBe(true);

    resolveLogin(meResponse());
    await pending;

    expect(store.loginBusy()).toBe(false);
  });

  it('logout: clears the session and routes to /login when auth is enabled', async () => {
    const user = meResponse({ authEnabled: true });
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(user) });
    const router = stubRouter();
    const store = create(api, router);
    await store.ready;

    await store.logout();

    expect(api.authLogout).toHaveBeenCalled();
    expect(store.user()).toBeNull();
    expect(store.status()).toBe('anon');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('logout: routes to /fly when auth is disabled (dev parity)', async () => {
    const devAdmin = meResponse({ authEnabled: false });
    const api = stubApi({ authMe: vi.fn().mockResolvedValue(devAdmin) });
    const router = stubRouter();
    const store = create(api, router);
    await store.ready;

    await store.logout();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/fly');
  });

  it('logout: clears local state and still navigates even when the request itself fails', async () => {
    const user = meResponse();
    const api = stubApi({
      authMe: vi.fn().mockResolvedValue(user),
      authLogout: vi.fn().mockRejectedValue(new Error('offline')),
    });
    const router = stubRouter();
    const store = create(api, router);
    await store.ready;

    await store.logout();

    expect(store.user()).toBeNull();
    expect(store.status()).toBe('anon');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });
});
