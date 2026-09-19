import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { BootstrapRequest, MeResponse } from '../../api/models';

/** Every command a page/guard/interceptor can issue (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N2) —
 *  mirrors the old `AuthStore`'s public methods one-for-one. */
export const AuthPageActions = createActionGroup({
  source: 'Auth Page',
  events: {
    /** Dispatched exactly once, from `AuthFacade`'s own constructor — see that file's class doc for
     *  why this (not a `ROOT_EFFECTS_INIT`-triggered effect) is what makes `ready` race-free. */
    'Boot Requested': emptyProps(),
    'Login Requested': props<{ username: string; password: string }>(),
    'Logout Requested': emptyProps(),
    /** `core/auth/session-interceptor.ts` is the one caller — see `auth.reducer.ts` for the frozen
     *  two-branch behavior this must reproduce exactly. */
    'Session Expired': props<{ onFlyRoute: boolean }>(),
    'Change Password Requested': props<{ currentPassword: string; newPassword: string }>(),
    'Bootstrap Requested': props<{ request: BootstrapRequest }>(),
    'Bootstrap Required Requested': emptyProps(),
  },
});

/** Every outcome. `Me Loaded`/`Bootstrap Succeeded`/`Login Succeeded` all resolve through the same
 *  `applySession` reducer helper — see `auth.reducer.ts`. */
export const AuthApiActions = createActionGroup({
  source: 'Auth API',
  events: {
    'Me Loaded': props<{ me: MeResponse | null }>(),
    /** Only a network/5xx failure reaches here — `VisionApi.authMe()` already folds a clean 401 into
     *  a successful `Me Loaded({ me: null })`. */
    'Me Load Failed': emptyProps(),
    'Login Succeeded': props<{ me: MeResponse }>(),
    'Login Failed': props<{ message: string }>(),
    /** Stage 1 of logout — carries `wasAuthEnabled`, read *before* the reducer clears the session,
     *  because both of its reactors need it after the reducer has already forgotten it: stage 2
     *  (`logoutSideEffects$`) picks the redirect with it, and `live.effects.ts#stopOnLogout$` decides
     *  whether there is a real connection to close. */
    'Logout Completed': props<{ wasAuthEnabled: boolean }>(),
    /** Stage 2 — the bridge signal `AuthFacade#logout()` actually awaits. */
    'Logout Finished': emptyProps(),
    'Change Password Succeeded': emptyProps(),
    'Change Password Failed': props<{ message: string }>(),
    'Bootstrap Succeeded': props<{ me: MeResponse }>(),
    'Bootstrap Failed': props<{ message: string }>(),
    /** Never a "failed" counterpart — mirrors `AuthStore#bootstrapRequired()`'s own "never throws,
     *  fails safe to `false`" contract; the effect swallows its own error internally. */
    'Bootstrap Required Resolved': props<{ required: boolean }>(),
  },
});
