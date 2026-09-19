import { describe, expect, it } from 'vitest';
import { DEFAULT_BATTERY_THRESHOLDS, DEFAULT_RC_THRESHOLDS } from '../thresholds-logic';
import { ThresholdsApiActions } from './thresholds.actions';
import { initialThresholdsState } from './thresholds.model';
import { thresholdsFeature } from './thresholds.reducer';

const { reducer } = thresholdsFeature;

describe('thresholdsFeature reducer', () => {
  it('starts at the honest defaults before the first fetch ever resolves', () => {
    expect(initialThresholdsState.battery).toEqual(DEFAULT_BATTERY_THRESHOLDS);
    expect(initialThresholdsState.rc).toEqual(DEFAULT_RC_THRESHOLDS);
    expect(initialThresholdsState.loaded).toBe(false);
  });

  it('refreshSucceeded stores the served thresholds, including rc, and clears any error', () => {
    const state = reducer(
      { ...initialThresholdsState, error: 'stale error' },
      ThresholdsApiActions.refreshSucceeded({
        battery: { warningPercent: 30, criticalPercent: 12 },
        rc: { neutralTolerancePercent: 8 },
      }),
    );

    expect(state.battery).toEqual({ warningPercent: 30, criticalPercent: 12 });
    expect(state.rc).toEqual({ neutralTolerancePercent: 8 });
    expect(state.loaded).toBe(true);
    expect(state.error).toBeUndefined();
  });

  it('refreshFailed leaves battery/rc exactly where they were, never blocking or fabricating a value', () => {
    const state = reducer(initialThresholdsState, ThresholdsApiActions.refreshFailed({ error: 'boom' }));

    expect(state.battery).toEqual(DEFAULT_BATTERY_THRESHOLDS);
    expect(state.rc).toEqual(DEFAULT_RC_THRESHOLDS);
    expect(state.loaded).toBe(false);
    expect(state.error).toBe('boom');
  });

  it('refreshFailed after an earlier success leaves the previously-served thresholds untouched', () => {
    const succeeded = reducer(
      initialThresholdsState,
      ThresholdsApiActions.refreshSucceeded({
        battery: { warningPercent: 30, criticalPercent: 12 },
        rc: { neutralTolerancePercent: 8 },
      }),
    );
    const state = reducer(succeeded, ThresholdsApiActions.refreshFailed({ error: 'boom' }));

    expect(state.battery).toEqual({ warningPercent: 30, criticalPercent: 12 });
    expect(state.rc).toEqual({ neutralTolerancePercent: 8 });
    expect(state.loaded).toBe(true);
    expect(state.error).toBe('boom');
  });
});
