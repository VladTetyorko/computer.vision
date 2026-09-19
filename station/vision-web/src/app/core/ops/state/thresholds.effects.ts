import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, from, map, of, switchMap } from 'rxjs';
import { VisionApi } from '../../api/vision-api';
import { DEFAULT_RC_THRESHOLDS } from '../thresholds-logic';
import { ThresholdsApiActions, ThresholdsPageActions } from './thresholds.actions';

/** Stable per-file console tag, mirroring the old `ThresholdsStore`'s own `[ops-thresholds]` prefix. */
const LOG_PREFIX = '[ops-thresholds]';

/**
 * Fetch-once, not a `PollScheduler` poller (see `thresholds.model.ts` class doc). Degrades `rc` to
 * {@link DEFAULT_RC_THRESHOLDS} both on a failed fetch *and* on a response that simply doesn't carry
 * `rc` yet (BK1, landing in parallel with this wave) — the exact same two occasions `battery`
 * degrades to its own default.
 */
export const refresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(ThresholdsPageActions.refreshRequested),
      switchMap(() =>
        from(api.opsThresholds()).pipe(
          map((response) =>
            ThresholdsApiActions.refreshSucceeded({ battery: response.battery, rc: response.rc ?? DEFAULT_RC_THRESHOLDS }),
          ),
          catchError((error: unknown) => {
            console.warn(`${LOG_PREFIX} could not read /api/ops/thresholds — using defaults`, { error });
            return of(ThresholdsApiActions.refreshFailed({ error: 'Could not read severity thresholds — using defaults.' }));
          }),
        ),
      ),
    ),
  { functional: true },
);

export const thresholdsEffects = { refresh$ };
