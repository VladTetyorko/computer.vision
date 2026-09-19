import { createFeature, createReducer, on } from '@ngrx/store';
import { ThresholdsApiActions } from './thresholds.actions';
import { initialThresholdsState } from './thresholds.model';

export const thresholdsFeature = createFeature({
  name: 'thresholds',
  reducer: createReducer(
    initialThresholdsState,
    on(ThresholdsApiActions.refreshSucceeded, (state, { battery, rc }) => ({
      ...state,
      battery,
      rc,
      loaded: true,
      error: undefined,
    })),
    // Degrades honestly: `battery`/`rc` are left exactly as they were (the initial defaults, unless
    // an earlier refresh already succeeded) — never blocked, never fabricated. Only `error` moves.
    on(ThresholdsApiActions.refreshFailed, (state, { error }) => ({ ...state, error })),
  ),
});
