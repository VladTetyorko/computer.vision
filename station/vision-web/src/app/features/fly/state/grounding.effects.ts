import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, from, map, of, switchMap, takeUntil } from 'rxjs';
import { VisionApi } from '../../../core/api/vision-api';
import { GroundingApiActions, GroundingPageActions } from './grounding.actions';

/**
 * Reads `GET /api/assets/{id}/readiness` once per tracked asset — **no poll and no live axis**, the
 * one thing that makes this the smallest slice in the app. A manager's ground/release action is not
 * an event the cockpit needs to reflect inside a single visit (`GroundingStore`'s own original
 * contract, kept verbatim through wave N8).
 *
 * `switchMap` replaces the old class's manual `lastTrackedAssetId` generation check: picking a
 * different asset tears the in-flight read down before the new one starts. `takeUntil(resetRequested)`
 * drops a read whose asset was deselected while it was still in flight, so a late success can never
 * repopulate a banner the operator has already navigated away from.
 *
 * The failure path logs and degrades — it never toasts. A readiness read that fails says nothing
 * about whether the vehicle is grounded, and a toast would invite the pilot to read it as one
 * (CLAUDE.md rule 7); the error stays out of the action for the serializability reason
 * `grounding.actions.ts#readFailed` documents.
 */
export const read$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(GroundingPageActions.trackRequested),
      switchMap(({ assetId }) =>
        from(api.assetReadiness(assetId)).pipe(
          map((report) => GroundingApiActions.readSucceeded({ assetId, report })),
          catchError((error) => {
            console.warn('[grounding] could not load the readiness report — grounded banner stays honest', { assetId, error });
            return of(GroundingApiActions.readFailed({ assetId }));
          }),
          takeUntil(actions$.pipe(ofType(GroundingPageActions.resetRequested))),
        ),
      ),
    ),
  { functional: true },
);

export const groundingEffects = { read$ };
