import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { provideMockActions } from '@ngrx/effects/testing';
import { provideState, provideStore } from '@ngrx/store';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { firstValueFrom, ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { MeResponse } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveStore } from '../../live/live-store';
import { AuthApiActions, AuthPageActions } from './auth.actions';
import {
  bootMe$,
  bootstrap$,
  bootstrapRequired$,
  changePassword$,
  logout$,
  logoutSideEffects$,
  login$,
  reconnectLiveOnSession$,
} from './auth.effects';
import { authFeature } from './auth.reducer';

function me(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    userId: 'u-1',
    username: 'pilot',
    displayName: 'Pat Pilot',
    email: 'pilot@example.com',
    memberships: [],
    topRole: 'PILOT',
    authEnabled: true,
    capabilities: ['OPERATE_PAYLOAD'],
    scopeKind: 'ASSIGNED_ASSETS',
    mustChangePassword: false,
    ...overrides,
  };
}

function setup(overrides: { api?: Partial<VisionApi>; router?: Partial<Router>; liveStore?: Partial<LiveStore> } = {}) {
  const actions = new ReplaySubject<Action>(1);
  const api = {
    authMe: vi.fn().mockResolvedValue(null),
    authLogin: vi.fn(),
    authLogout: vi.fn().mockResolvedValue(undefined),
    changePassword: vi.fn().mockResolvedValue(undefined),
    bootstrapStatus: vi.fn().mockResolvedValue({ required: false }),
    bootstrap: vi.fn(),
    ...overrides.api,
  };
  const router = { navigateByUrl: vi.fn().mockResolvedValue(true), ...overrides.router };
  const liveStore = { reconnect: vi.fn(), stop: vi.fn(), ...overrides.liveStore };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(authFeature),
      { provide: VisionApi, useValue: api },
      { provide: Router, useValue: router },
      { provide: LiveStore, useValue: liveStore },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, router, liveStore, store };
}

describe('auth effects', () => {
  describe('bootMe$', () => {
    it('folds a clean 401 (null) into Me Loaded', async () => {
      const { actions } = setup({ api: { authMe: vi.fn().mockResolvedValue(null) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootMe$()));
      actions.next(AuthPageActions.bootRequested());
      expect(await result).toEqual(AuthApiActions.meLoaded({ me: null }));
    });

    it('folds an existing session into Me Loaded', async () => {
      const user = me();
      const { actions } = setup({ api: { authMe: vi.fn().mockResolvedValue(user) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootMe$()));
      actions.next(AuthPageActions.bootRequested());
      expect(await result).toEqual(AuthApiActions.meLoaded({ me: user }));
    });

    it('degrades a network failure to Me Load Failed, with a console warning', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const { actions } = setup({ api: { authMe: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 })) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootMe$()));
      actions.next(AuthPageActions.bootRequested());
      expect(await result).toEqual(AuthApiActions.meLoadFailed());
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });
  });

  describe('login$', () => {
    it('succeeds with the resolved session', async () => {
      const user = me();
      const { actions } = setup({ api: { authLogin: vi.fn().mockResolvedValue(user) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => login$()));
      actions.next(AuthPageActions.loginRequested({ username: 'pilot', password: 'pilot' }));
      expect(await result).toEqual(AuthApiActions.loginSucceeded({ me: user }));
    });

    it('a 401 becomes the deliberately-vague bad-credentials message', async () => {
      const { actions } = setup({ api: { authLogin: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 401 })) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => login$()));
      actions.next(AuthPageActions.loginRequested({ username: 'pilot', password: 'wrong' }));
      expect(await result).toEqual(AuthApiActions.loginFailed({ message: 'Incorrect username or password.' }));
    });

    it('a non-401 failure surfaces via describeHttpError', async () => {
      const { actions } = setup({ api: { authLogin: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 })) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => login$()));
      actions.next(AuthPageActions.loginRequested({ username: 'pilot', password: 'pilot' }));
      const action = (await result) as ReturnType<typeof AuthApiActions.loginFailed>;
      expect(action.message).toContain('Cannot reach the Vision backend');
    });
  });

  describe('bootstrap$', () => {
    it('succeeds with the new admin session', async () => {
      const admin = me({ topRole: 'ADMIN', username: 'root-admin' });
      const { actions } = setup({ api: { bootstrap: vi.fn().mockResolvedValue(admin) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootstrap$()));
      actions.next(
        AuthPageActions.bootstrapRequested({
          request: { username: 'root-admin', displayName: 'Root Admin', email: 'root@example.com', password: 'a-strong-password' },
        }),
      );
      expect(await result).toEqual(AuthApiActions.bootstrapSucceeded({ me: admin }));
    });

    it('a failure surfaces the server message and never throws', async () => {
      const { actions } = setup({
        api: {
          bootstrap: vi
            .fn()
            .mockRejectedValue(
              new HttpErrorResponse({ status: 409, error: { error: 'ALREADY_INITIALIZED', message: 'This station already has an administrator.' } }),
            ),
        },
      });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootstrap$()));
      actions.next(
        AuthPageActions.bootstrapRequested({
          request: { username: 'root-admin', displayName: 'Root Admin', email: 'root@example.com', password: 'a-strong-password' },
        }),
      );
      expect(await result).toEqual(AuthApiActions.bootstrapFailed({ message: 'This station already has an administrator.' }));
    });
  });

  describe('reconnectLiveOnSession$', () => {
    it('reconnects on Login Succeeded', () => {
      const { actions, liveStore } = setup();
      TestBed.runInInjectionContext(() => reconnectLiveOnSession$()).subscribe();
      actions.next(AuthApiActions.loginSucceeded({ me: me() }));
      expect(liveStore.reconnect).toHaveBeenCalled();
    });

    it('reconnects on Bootstrap Succeeded', () => {
      const { actions, liveStore } = setup();
      TestBed.runInInjectionContext(() => reconnectLiveOnSession$()).subscribe();
      actions.next(AuthApiActions.bootstrapSucceeded({ me: me() }));
      expect(liveStore.reconnect).toHaveBeenCalled();
    });

    it('does not reconnect on a failed login', () => {
      const { actions, liveStore } = setup();
      TestBed.runInInjectionContext(() => reconnectLiveOnSession$()).subscribe();
      actions.next(AuthApiActions.loginFailed({ message: 'nope' }));
      expect(liveStore.reconnect).not.toHaveBeenCalled();
    });
  });

  describe('logout$ (stage 1 — reads wasAuthEnabled before the reducer clears it)', () => {
    it('reads authEnabled=true off state and forwards it once the best-effort request settles', async () => {
      const { actions, api, store } = setup();
      store.dispatch(AuthApiActions.meLoaded({ me: me({ authEnabled: true }) }));
      const result = firstValueFrom(TestBed.runInInjectionContext(() => logout$()));
      const action = AuthPageActions.logoutRequested();
      store.dispatch(action);
      actions.next(action);
      expect(await result).toEqual(AuthApiActions.logoutCompleted({ wasAuthEnabled: true }));
      expect(api.authLogout).toHaveBeenCalled();
    });

    it('reads authEnabled=false off state (dev parity)', async () => {
      const { actions, store } = setup();
      store.dispatch(AuthApiActions.meLoaded({ me: me({ authEnabled: false }) }));
      const result = firstValueFrom(TestBed.runInInjectionContext(() => logout$()));
      const action = AuthPageActions.logoutRequested();
      store.dispatch(action);
      actions.next(action);
      expect(await result).toEqual(AuthApiActions.logoutCompleted({ wasAuthEnabled: false }));
    });

    it('still completes (best-effort) when the request itself fails, with a console warning', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const { actions, store } = setup({ api: { authLogout: vi.fn().mockRejectedValue(new Error('offline')) } });
      store.dispatch(AuthApiActions.meLoaded({ me: me({ authEnabled: true }) }));
      const result = firstValueFrom(TestBed.runInInjectionContext(() => logout$()));
      const action = AuthPageActions.logoutRequested();
      store.dispatch(action);
      actions.next(action);
      expect(await result).toEqual(AuthApiActions.logoutCompleted({ wasAuthEnabled: true }));
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });
  });

  describe('logoutSideEffects$ (stage 2)', () => {
    it('stops the live store and routes to /login when auth was enabled', async () => {
      const { actions, router, liveStore } = setup();
      const result = firstValueFrom(TestBed.runInInjectionContext(() => logoutSideEffects$()));
      actions.next(AuthApiActions.logoutCompleted({ wasAuthEnabled: true }));
      expect(await result).toEqual(AuthApiActions.logoutFinished());
      expect(liveStore.stop).toHaveBeenCalled();
      expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    });

    it('leaves the live store alone and routes to /fly in dev parity', async () => {
      const { actions, router, liveStore } = setup();
      const result = firstValueFrom(TestBed.runInInjectionContext(() => logoutSideEffects$()));
      actions.next(AuthApiActions.logoutCompleted({ wasAuthEnabled: false }));
      expect(await result).toEqual(AuthApiActions.logoutFinished());
      expect(liveStore.stop).not.toHaveBeenCalled();
      expect(router.navigateByUrl).toHaveBeenCalledWith('/fly');
    });
  });

  describe('changePassword$', () => {
    it('success re-fetches /me and emits Me Loaded then Change Password Succeeded, in that order', async () => {
      const refreshed = me({ mustChangePassword: false });
      const { actions, api } = setup({ api: { authMe: vi.fn().mockResolvedValue(refreshed) } });
      const emitted: Action[] = [];
      TestBed.runInInjectionContext(() => changePassword$()).subscribe((action) => emitted.push(action));
      actions.next(AuthPageActions.changePasswordRequested({ currentPassword: 'old-pw', newPassword: 'new-pw' }));
      await vi.waitFor(() => expect(emitted).toHaveLength(2));
      expect(emitted).toEqual([AuthApiActions.meLoaded({ me: refreshed }), AuthApiActions.changePasswordSucceeded()]);
      expect(api.changePassword).toHaveBeenCalledWith({ currentPassword: 'old-pw', newPassword: 'new-pw' });
    });

    it('a 401 (wrong current password) emits only Change Password Failed, with a specific message', async () => {
      const { actions } = setup({
        api: { changePassword: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 401 })) },
      });
      const emitted: Action[] = [];
      TestBed.runInInjectionContext(() => changePassword$()).subscribe((action) => emitted.push(action));
      actions.next(AuthPageActions.changePasswordRequested({ currentPassword: 'wrong-pw', newPassword: 'new-pw' }));
      await vi.waitFor(() => expect(emitted).toHaveLength(1));
      expect(emitted).toEqual([AuthApiActions.changePasswordFailed({ message: 'Incorrect current password.' })]);
    });

    it('any other failure degrades via describeHttpError', async () => {
      const { actions } = setup({
        api: {
          changePassword: vi
            .fn()
            .mockRejectedValue(new HttpErrorResponse({ status: 400, error: { error: 'WEAK_PASSWORD', message: 'Too short.' } })),
        },
      });
      const emitted: Action[] = [];
      TestBed.runInInjectionContext(() => changePassword$()).subscribe((action) => emitted.push(action));
      actions.next(AuthPageActions.changePasswordRequested({ currentPassword: 'old-pw', newPassword: '123' }));
      await vi.waitFor(() => expect(emitted).toHaveLength(1));
      expect(emitted).toEqual([AuthApiActions.changePasswordFailed({ message: 'Too short.' })]);
    });
  });

  describe('bootstrapRequired$', () => {
    it('reflects the latch as the server reports it', async () => {
      const { actions } = setup({ api: { bootstrapStatus: vi.fn().mockResolvedValue({ required: true }) } });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootstrapRequired$()));
      actions.next(AuthPageActions.bootstrapRequiredRequested());
      expect(await result).toEqual(AuthApiActions.bootstrapRequiredResolved({ required: true }));
    });

    it('fails safe to false (never a rejected stream) when the check itself is unreachable', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const { actions } = setup({
        api: { bootstrapStatus: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 })) },
      });
      const result = firstValueFrom(TestBed.runInInjectionContext(() => bootstrapRequired$()));
      actions.next(AuthPageActions.bootstrapRequiredRequested());
      expect(await result).toEqual(AuthApiActions.bootstrapRequiredResolved({ required: false }));
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });
  });
});
