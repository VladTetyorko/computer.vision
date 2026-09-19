import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { ReadinessReport } from '../../core/api/models';
import { VisionApi } from '../../core/api/vision-api';
import { provideAppState } from '../../core/state/app-state';
import { GroundingFacade } from './grounding-facade';
import { provideGroundingState } from './state/grounding.providers';

/** Lets the effect's HTTP read settle before asserting — same shape as `core/seat/seat-facade.spec.ts`. */
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

/**
 * `GroundingFacade` is page-provided (`@Injectable()`, listed in `CockpitPage.providers`), so it must
 * be named here explicitly alongside its own slice — `TestBed.inject` cannot construct it otherwise.
 * Wave N4b's gotcha applies: `provideAppState()`'s root effects auto-start on the first inject, which
 * is why `VisionApi` is stubbed whole rather than partially.
 */
function setup(assetReadiness = vi.fn().mockResolvedValue(report('a-1'))) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideGroundingState(),
      GroundingFacade,
      {
        provide: VisionApi,
        useValue: {
          assetReadiness,
          listDevices: vi.fn().mockResolvedValue([]),
          listStreams: vi.fn().mockResolvedValue([]),
          systemStatus: vi.fn().mockResolvedValue({ subsystems: [] }),
        },
      },
    ],
  });
  return { facade: TestBed.inject(GroundingFacade), assetReadiness };
}

describe('GroundingFacade', () => {
  it('surfaces the grounded reason for a tracked, grounded asset', async () => {
    const { facade } = setup(vi.fn().mockResolvedValue(report('a-1', ['MAINTENANCE_GROUNDED:REPAIR:cracked arm'])));

    facade.track('a-1');
    await flush();

    expect(facade.groundedBlocker()).toEqual({ kind: 'REPAIR', summary: 'cracked arm' });
    expect(facade.groundedReason()).toContain('cracked arm');
  });

  it('says nothing for an asset that carries no grounding blocker', async () => {
    const { facade } = setup(vi.fn().mockResolvedValue(report('a-1', ['BATTERY'])));

    facade.track('a-1');
    await flush();

    expect(facade.groundedReason()).toBeUndefined();
  });

  it('track() is a no-op for an unchanged asset — one read per selection, not per effect run', async () => {
    const { facade, assetReadiness } = setup();

    facade.track('a-1');
    facade.track('a-1');
    await flush();

    expect(assetReadiness).toHaveBeenCalledTimes(1);
  });

  it('reset() clears the banner', async () => {
    const { facade } = setup(vi.fn().mockResolvedValue(report('a-1', ['MAINTENANCE_GROUNDED:REPAIR:cracked arm'])));

    facade.track('a-1');
    await flush();
    expect(facade.groundedReason()).toBeDefined();

    facade.reset();
    expect(facade.groundedReason()).toBeUndefined();
  });

  it('a failed read leaves the banner silent rather than fabricating a grounding', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { facade } = setup(vi.fn().mockRejectedValue(new Error('boom')));

    facade.track('a-1');
    await flush();

    expect(facade.groundedReason()).toBeUndefined();
  });
});
