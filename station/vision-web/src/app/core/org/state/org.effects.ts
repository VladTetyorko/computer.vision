import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, from, map, of, switchMap, tap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { ToastService } from '../../toast.service';
import { OrgApiActions, OrgPageActions } from './org.actions';

/** Stable per-file console tag, mirroring the old `OrgStore`'s own `[org]` prefix. */
const LOG_PREFIX = '[org]';

/** Re-reads both lists together — the one shared read every mutation below also calls after it
 *  writes, so a caller's `users`/`groups` are never stale after a create/enable/membership change. */
function loadUsersAndGroups(api: VisionApi) {
  return Promise.all([api.listUsers(), api.listGroups()]);
}

export const refresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OrgPageActions.refreshRequested),
      switchMap(({ quiet }) =>
        from(loadUsersAndGroups(api)).pipe(
          map(([users, groups]) => OrgApiActions.refreshSucceeded({ users, groups })),
          catchError((error: unknown) => of(OrgApiActions.refreshFailed({ error: describeHttpError(error), quiet }))),
        ),
      ),
    ),
  { functional: true },
);

export const createUser$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OrgPageActions.createUserRequested),
      switchMap(({ request }) =>
        from(
          (async () => {
            const user = await api.createUser(request);
            const [users, groups] = await loadUsersAndGroups(api);
            return OrgApiActions.createUserSucceeded({ user, users, groups, message: `Created ${user.displayName}.` });
          })(),
        ).pipe(catchError((error: unknown) => of(OrgApiActions.createUserFailed({ error: describeHttpError(error) })))),
      ),
    ),
  { functional: true },
);

export const setUserEnabled$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OrgPageActions.setUserEnabledRequested),
      switchMap(({ id, enabled }) =>
        from(
          (async () => {
            const user = await api.setUserEnabled(id, enabled);
            const [users, groups] = await loadUsersAndGroups(api);
            return OrgApiActions.setUserEnabledSucceeded({
              user,
              users,
              groups,
              message: `${user.displayName} is now ${enabled ? 'enabled' : 'disabled'}.`,
            });
          })(),
        ).pipe(
          catchError((error: unknown) => of(OrgApiActions.setUserEnabledFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const createGroup$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OrgPageActions.createGroupRequested),
      switchMap(({ request }) =>
        from(
          (async () => {
            const group = await api.createGroup(request);
            const [users, groups] = await loadUsersAndGroups(api);
            return OrgApiActions.createGroupSucceeded({ group, users, groups, message: `Created ${group.name}.` });
          })(),
        ).pipe(
          catchError((error: unknown) => of(OrgApiActions.createGroupFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const setMemberships$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OrgPageActions.setMembershipsRequested),
      switchMap(({ userId, memberships }) =>
        from(
          (async () => {
            const user = await api.setMemberships(userId, { memberships });
            const [users, groups] = await loadUsersAndGroups(api);
            return OrgApiActions.setMembershipsSucceeded({
              user,
              users,
              groups,
              message: `Updated ${user.displayName}'s memberships.`,
            });
          })(),
        ).pipe(
          catchError((error: unknown) => of(OrgApiActions.setMembershipsFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const adminSetPassword$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OrgPageActions.adminSetPasswordRequested),
      switchMap(({ userId, newPassword }) =>
        from(api.adminSetPassword(userId, { newPassword })).pipe(
          map(() =>
            OrgApiActions.adminSetPasswordSucceeded({
              message: 'Password reset — they must change it at next sign-in.',
            }),
          ),
          catchError((error: unknown) =>
            of(OrgApiActions.adminSetPasswordFailed({ error: describeHttpError(error) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

export const notifySuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(
        OrgApiActions.createUserSucceeded,
        OrgApiActions.setUserEnabledSucceeded,
        OrgApiActions.createGroupSucceeded,
        OrgApiActions.setMembershipsSucceeded,
        OrgApiActions.adminSetPasswordSucceeded,
      ),
      tap((action) => toasts.ok(action.message)),
    ),
  { functional: true, dispatch: false },
);

/** One explained toast per failure (mirrors `OrgStore#run`'s shared seam) — `refreshFailed` alone
 *  can be quiet, since a mutation's own internal re-read failing after a successful write would
 *  otherwise show a confusing second error on top of that write's own (mirrors the old
 *  `refresh({ quiet: true })` call every mutation made). */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(
        OrgApiActions.refreshFailed,
        OrgApiActions.createUserFailed,
        OrgApiActions.setUserEnabledFailed,
        OrgApiActions.createGroupFailed,
        OrgApiActions.setMembershipsFailed,
        OrgApiActions.adminSetPasswordFailed,
      ),
      tap((action) => {
        console.warn(`${LOG_PREFIX} ${action.type}`, { error: action.error });
        if (action.type === OrgApiActions.refreshFailed.type && action.quiet) {
          return;
        }
        toasts.error(action.error);
      }),
    ),
  { functional: true, dispatch: false },
);

export const orgEffects = {
  refresh$,
  createUser$,
  setUserEnabled$,
  createGroup$,
  setMemberships$,
  adminSetPassword$,
  notifySuccess$,
  notifyFailure$,
};
