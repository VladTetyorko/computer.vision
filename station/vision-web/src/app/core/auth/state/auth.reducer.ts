import { createFeature, createReducer, on } from '@ngrx/store';
import type { MeResponse } from '../../api/models';
import { AuthApiActions, AuthPageActions } from './auth.actions';
import type { AuthState } from './auth.model';
import { initialAuthState } from './auth.model';

/**
 * Shared by `meLoaded`/`loginSucceeded`/`bootstrapSucceeded` — all three resolve to the same
 * `MeResponse | null` shape and apply it identically (mirrors the old `AuthStore#applySession`
 * private method exactly, including the "a clean `null` means auth must be enabled" inference —
 * see that method's own doc comment, carried into `auth.effects.ts#bootMe$`'s doc instead now that
 * the store class is gone).
 */
function applySession(state: AuthState, me: MeResponse | null): AuthState {
  if (me === null) {
    return { ...state, user: null, authEnabled: true, status: 'anon', reauthRequired: false };
  }
  return { ...state, user: me, authEnabled: me.authEnabled, status: 'authed', reauthRequired: false };
}

export const authFeature = createFeature({
  name: 'auth',
  reducer: createReducer(
    initialAuthState,
    on(AuthApiActions.meLoaded, (state, { me }) => applySession(state, me)),
    // Only a network/5xx failure reaches here (see that action's own doc comment) — degrades to
    // 'anon' without touching `authEnabled`, exactly like `AuthStore#loadMe`'s catch block.
    on(AuthApiActions.meLoadFailed, (state) => ({ ...state, user: null, status: 'anon' })),
    on(AuthPageActions.loginRequested, (state) => ({ ...state, loginBusy: true, loginError: null })),
    on(AuthApiActions.loginSucceeded, (state, { me }) => ({ ...applySession(state, me), loginBusy: false })),
    on(AuthApiActions.loginFailed, (state, { message }) => ({ ...state, loginBusy: false, loginError: message })),
    // `onFlyRoute: true` sets only `reauthRequired` and touches nothing else — no navigation, no
    // local session clear (`shared/ui/reauth-overlay.ts` presents in place). `onFlyRoute: false`
    // clears the session locally but leaves `authEnabled`/`reauthRequired` untouched — mirrors
    // `AuthStore#sessionExpired`'s own two-branch behavior exactly, see that method's frozen doc
    // comment (now carried by `auth.actions.ts`'s `Session Expired` doc).
    on(AuthPageActions.sessionExpired, (state, { onFlyRoute }) =>
      onFlyRoute ? { ...state, reauthRequired: true } : { ...state, user: null, status: 'anon' },
    ),
    // Clears the session unconditionally — `authEnabled`/`reauthRequired` are left untouched, exactly
    // like `AuthStore#logout`'s own state writes (its `wasAuthEnabled` read happens in the effect,
    // *before* this dispatches, purely to decide navigation/LiveStore — never to gate this clear).
    on(AuthApiActions.logoutCompleted, (state) => ({ ...state, user: null, status: 'anon' })),
    on(AuthApiActions.bootstrapSucceeded, (state, { me }) => applySession(state, me)),
  ),
});
