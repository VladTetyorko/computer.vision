import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { firstValueFrom, ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { VisionApi } from '../../api/vision-api';
import { DEFAULT_RC_THRESHOLDS } from '../thresholds-logic';
import { ThresholdsApiActions, ThresholdsPageActions } from './thresholds.actions';
import { refresh$ } from './thresholds.effects';

function setup(apiOverrides: Partial<Record<string, ReturnType<typeof vi.fn>>> = {}) {
  const actions = new ReplaySubject<Action>(1);
  const api = {
    opsThresholds: vi.fn().mockResolvedValue({ battery: { warningPercent: 25, criticalPercent: 10 } }),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [provideMockActions(() => actions), { provide: VisionApi, useValue: api }],
  });
  return { actions, api };
}

describe('thresholds effects — refresh$', () => {
  it('maps a served response to refreshSucceeded, defaulting a missing rc to DEFAULT_RC_THRESHOLDS', async () => {
    const { actions } = setup();
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(ThresholdsPageActions.refreshRequested());

    expect(await result).toEqual(
      ThresholdsApiActions.refreshSucceeded({ battery: { warningPercent: 25, criticalPercent: 10 }, rc: DEFAULT_RC_THRESHOLDS }),
    );
  });

  it('passes through a served rc unchanged', async () => {
    const { actions } = setup({
      opsThresholds: vi.fn().mockResolvedValue({
        battery: { warningPercent: 30, criticalPercent: 12 },
        rc: { neutralTolerancePercent: 8 },
      }),
    });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(ThresholdsPageActions.refreshRequested());

    expect(await result).toEqual(
      ThresholdsApiActions.refreshSucceeded({
        battery: { warningPercent: 30, criticalPercent: 12 },
        rc: { neutralTolerancePercent: 8 },
      }),
    );
  });

  it('degrades a failed fetch to refreshFailed with a fixed, friendly message — never fabricating a value', async () => {
    const { actions } = setup({ opsThresholds: vi.fn().mockRejectedValue(new Error('network down')) });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(ThresholdsPageActions.refreshRequested());

    expect(await result).toEqual(
      ThresholdsApiActions.refreshFailed({ error: 'Could not read severity thresholds — using defaults.' }),
    );
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
