import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReadinessReport } from '../../../core/api/models';
import { VisionApi } from '../../../core/api/vision-api';
import { GroundingApiActions, GroundingPageActions } from './grounding.actions';
import { read$ } from './grounding.effects';

/** Lets the effect's own promise chain settle before asserting — mirrors `geo.effects.spec.ts#flush`. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function report(assetId: string, blockers: readonly string[] = []): ReadinessReport {
  return {
    assetId,
    verdict: 'NO_GO',
    evaluatedAt: '2024-01-01T00:00:00Z',
    profileObservedAt: null,
    features: [],
    blockers,
  };
}

function setup(assetReadiness = vi.fn().mockResolvedValue(report('a-1'))) {
  const actions = new ReplaySubject<Action>(1);
  TestBed.configureTestingModule({
    providers: [provideMockActions(() => actions), { provide: VisionApi, useValue: { assetReadiness } }],
  });
  return { actions, assetReadiness };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('grounding effects — read$', () => {
  it('reads the tracked asset exactly once, with no poll behind it', async () => {
    const { actions, assetReadiness } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => read$()).subscribe((a) => seen.push(a));

    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    await flush();

    expect(assetReadiness).toHaveBeenCalledExactlyOnceWith('a-1');
    expect(seen).toEqual([GroundingApiActions.readSucceeded({ assetId: 'a-1', report: report('a-1') })]);
  });

  it('degrades honestly on a failed read — a readFailed, never a fabricated grounding', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { actions } = setup(vi.fn().mockRejectedValue(new Error('boom')));
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => read$()).subscribe((a) => seen.push(a));

    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    await flush();

    expect(seen).toEqual([GroundingApiActions.readFailed({ assetId: 'a-1' })]);
  });

  it('keeps reading after a failure — one bad asset never kills the effect for the next one', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const assetReadiness = vi
      .fn()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(report('a-2'));
    const { actions } = setup(assetReadiness);
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => read$()).subscribe((a) => seen.push(a));

    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    await flush();
    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-2' }));
    await flush();

    expect(seen).toEqual([
      GroundingApiActions.readFailed({ assetId: 'a-1' }),
      GroundingApiActions.readSucceeded({ assetId: 'a-2', report: report('a-2') }),
    ]);
  });

  it('a superseding track drops the in-flight read for the previous asset', async () => {
    let resolveFirst: ((value: ReadinessReport) => void) | undefined;
    const assetReadiness = vi
      .fn()
      .mockImplementationOnce(() => new Promise<ReadinessReport>((resolve) => (resolveFirst = resolve)))
      .mockResolvedValueOnce(report('a-2'));
    const { actions } = setup(assetReadiness);
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => read$()).subscribe((a) => seen.push(a));

    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-2' }));
    await flush();
    resolveFirst?.(report('a-1'));
    await flush();

    expect(seen).toEqual([GroundingApiActions.readSucceeded({ assetId: 'a-2', report: report('a-2') })]);
  });

  it('a reset drops an in-flight read rather than repopulating a banner the operator left', async () => {
    let resolveRead: ((value: ReadinessReport) => void) | undefined;
    const { actions } = setup(vi.fn().mockImplementation(() => new Promise<ReadinessReport>((resolve) => (resolveRead = resolve))));
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => read$()).subscribe((a) => seen.push(a));

    actions.next(GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    await flush();
    actions.next(GroundingPageActions.resetRequested());
    resolveRead?.(report('a-1'));
    await flush();

    expect(seen).toEqual([]);
  });
});
