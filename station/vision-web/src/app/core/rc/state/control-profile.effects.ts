import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, from, map, of, switchMap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { ControlProfileApiActions, ControlProfilePageActions } from './control-profile.actions';

/** Re-reads just the profile list — the one shared read every mutation below also calls after it
 *  writes (`ControlProfileStore`'s own private `reload()`); the catalogue never changes from these. */
function reloadProfiles(api: VisionApi) {
  return api.controlProfiles();
}

export const load$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(ControlProfilePageActions.loadRequested),
      switchMap(() =>
        from(Promise.all([api.controlProfiles(), api.controlCatalog()])).pipe(
          map(([profiles, catalog]) => ControlProfileApiActions.loadSucceeded({ profiles, catalog })),
          catchError((error: unknown) => of(ControlProfileApiActions.loadFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const create$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(ControlProfilePageActions.createRequested),
      switchMap(({ request }) =>
        from(
          (async () => {
            const profile = await api.createControlProfile(request);
            const profiles = await reloadProfiles(api);
            return ControlProfileApiActions.createSucceeded({ profile, profiles });
          })(),
        ).pipe(
          catchError((error: unknown) => of(ControlProfileApiActions.createFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const update$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(ControlProfilePageActions.updateRequested),
      switchMap(({ id, request }) =>
        from(
          (async () => {
            const profile = await api.updateControlProfile(id, request);
            const profiles = await reloadProfiles(api);
            return ControlProfileApiActions.updateSucceeded({ profile, profiles });
          })(),
        ).pipe(
          catchError((error: unknown) => of(ControlProfileApiActions.updateFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const activate$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(ControlProfilePageActions.activateRequested),
      switchMap(({ id }) =>
        from(
          (async () => {
            await api.activateControlProfile(id);
            const profiles = await reloadProfiles(api);
            return ControlProfileApiActions.activateSucceeded({ profiles });
          })(),
        ).pipe(
          catchError((error: unknown) => of(ControlProfileApiActions.activateFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const deleteProfile$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(ControlProfilePageActions.deleteRequested),
      switchMap(({ id }) =>
        from(
          (async () => {
            await api.deleteControlProfile(id);
            const profiles = await reloadProfiles(api);
            return ControlProfileApiActions.deleteSucceeded({ profiles });
          })(),
        ).pipe(
          catchError((error: unknown) => of(ControlProfileApiActions.deleteFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

// Deliberately no `notifySuccess$`/`notifyFailure$` here (unlike every other N7 slice) — see
// `control-profile-facade.ts`'s own doc comment for why: `ControllerSetupFacade`, the one feature
// facade that calls every mutation below, already owns toasting its own `describeHttpError(error)`
// message, and `rc-monitor.ts`/`fly-hud.ts` deliberately swallow a failed `load()` — a `notifyFailure$`
// here would toast the exact same failure a second time.
export const controlProfileEffects = { load$, create$, update$, activate$, deleteProfile$ };
