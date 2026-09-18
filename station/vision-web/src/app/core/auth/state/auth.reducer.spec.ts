import { describe, expect, it } from 'vitest';
import type { MeResponse } from '../../api/models';
import { AuthApiActions, AuthPageActions } from './auth.actions';
import { initialAuthState } from './auth.model';
import { authFeature } from './auth.reducer';

const { reducer } = authFeature;

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

describe('auth reducer', () => {
  it('starts loading, with no session', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual(initialAuthState);
  });

  it('Me Loaded with null infers authEnabled=true and status=anon (a clean 401 implies a real auth deployment)', () => {
    const state = reducer(initialAuthState, AuthApiActions.meLoaded({ me: null }));
    expect(state).toEqual({ ...initialAuthState, user: null, authEnabled: true, status: 'anon' });
  });

  it('Me Loaded with a session applies it, including its own authEnabled (dev-parity admin reports false)', () => {
    const devAdmin = me({ authEnabled: false });
    const state = reducer(initialAuthState, AuthApiActions.meLoaded({ me: devAdmin }));
    expect(state).toEqual({ ...initialAuthState, user: devAdmin, authEnabled: false, status: 'authed' });
  });

  it('Me Loaded clears a stale reauthRequired', () => {
    const seeded = { ...initialAuthState, reauthRequired: true };
    const state = reducer(seeded, AuthApiActions.meLoaded({ me: me() }));
    expect(state.reauthRequired).toBe(false);
  });

  it('Me Load Failed degrades to anon without touching authEnabled', () => {
    const seeded = { ...initialAuthState, authEnabled: true, user: me(), status: 'authed' as const };
    const state = reducer(seeded, AuthApiActions.meLoadFailed());
    expect(state).toEqual({ ...seeded, user: null, status: 'anon' });
  });

  it('Login Requested sets loginBusy and clears any prior loginError', () => {
    const seeded = { ...initialAuthState, loginError: 'stale' };
    const state = reducer(seeded, AuthPageActions.loginRequested({ username: 'pat', password: 'pw' }));
    expect(state.loginBusy).toBe(true);
    expect(state.loginError).toBeNull();
  });

  it('Login Succeeded applies the session and clears loginBusy', () => {
    const seeded = { ...initialAuthState, loginBusy: true };
    const state = reducer(seeded, AuthApiActions.loginSucceeded({ me: me() }));
    expect(state.loginBusy).toBe(false);
    expect(state.status).toBe('authed');
    expect(state.user).toEqual(me());
  });

  it('Login Failed clears loginBusy and sets loginError, leaving the session untouched', () => {
    const seeded = { ...initialAuthState, loginBusy: true };
    const state = reducer(seeded, AuthApiActions.loginFailed({ message: 'Incorrect username or password.' }));
    expect(state.loginBusy).toBe(false);
    expect(state.loginError).toBe('Incorrect username or password.');
    expect(state.status).toBe('loading'); // unchanged
  });

  describe('Session Expired', () => {
    it('onFlyRoute=true sets only reauthRequired — the session is untouched', () => {
      const seeded = { ...initialAuthState, user: me(), status: 'authed' as const };
      const state = reducer(seeded, AuthPageActions.sessionExpired({ onFlyRoute: true }));
      expect(state).toEqual({ ...seeded, reauthRequired: true });
    });

    it('onFlyRoute=false clears the session but leaves authEnabled/reauthRequired untouched', () => {
      const seeded = { ...initialAuthState, user: me(), status: 'authed' as const, authEnabled: true };
      const state = reducer(seeded, AuthPageActions.sessionExpired({ onFlyRoute: false }));
      expect(state).toEqual({ ...seeded, user: null, status: 'anon' });
    });
  });

  it('Logout Completed clears the session but leaves authEnabled/reauthRequired untouched', () => {
    const seeded = { ...initialAuthState, user: me(), status: 'authed' as const, authEnabled: true };
    const state = reducer(seeded, AuthApiActions.logoutCompleted({ wasAuthEnabled: true }));
    expect(state).toEqual({ ...seeded, user: null, status: 'anon' });
  });

  it('Bootstrap Succeeded applies the new session exactly like Me Loaded/Login Succeeded', () => {
    const admin = me({ topRole: 'ADMIN', username: 'root-admin' });
    const state = reducer(initialAuthState, AuthApiActions.bootstrapSucceeded({ me: admin }));
    expect(state).toEqual({ ...initialAuthState, user: admin, authEnabled: true, status: 'authed' });
  });
});
