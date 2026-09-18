import type { MeResponse } from '../../api/models';
import type { AuthStatus } from '../auth-logic';

/**
 * The auth slice's state (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N2) — every field the old
 * `AuthStore` held as its own signal, carried over 1:1: `user`/`authEnabled`/`status` are the
 * session itself; `loginBusy`/`loginError` are the login form's own transient UI state;
 * `reauthRequired` is `core/auth/session-interceptor.ts`'s one-way flag for a 401 caught while on
 * `/fly` (see `auth.reducer.ts`'s `sessionExpired` handling for the exact two-branch behavior this
 * preserves).
 */
export interface AuthState {
  readonly user: MeResponse | null;
  /** `false` until the first boot resolves and says otherwise — see `AuthFacade`'s class doc. */
  readonly authEnabled: boolean;
  readonly status: AuthStatus;
  readonly loginBusy: boolean;
  readonly loginError: string | null;
  readonly reauthRequired: boolean;
}

export const initialAuthState: AuthState = {
  user: null,
  authEnabled: false,
  status: 'loading',
  loginBusy: false,
  loginError: null,
  reauthRequired: false,
};
