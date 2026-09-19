import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { OpsThresholdsResponse } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { provideAppState } from '../state/app-state';
import { DEFAULT_BATTERY_THRESHOLDS, DEFAULT_RC_THRESHOLDS } from './thresholds-logic';
import { ThresholdsFacade } from './thresholds-facade';

/** Lets the fire-and-forget promise chain inside the constructor's `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('ThresholdsFacade', () => {
  let api: { opsThresholds: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = { opsThresholds: vi.fn().mockResolvedValue({ battery: DEFAULT_BATTERY_THRESHOLDS, rc: DEFAULT_RC_THRESHOLDS }) };
  });

  function create(): ThresholdsFacade {
    TestBed.configureTestingModule({ providers: [provideAppState(), { provide: VisionApi, useValue: api }] });
    return TestBed.inject(ThresholdsFacade);
  }

  it('starts at the honest default before the first fetch ever resolves', async () => {
    // A deliberately-controlled (not permanently hung) promise: the constructor's own
    // `dispatchAndAwait` subscribes to the app-wide Actions stream, and leaving that subscription
    // dangling past this test would outlive TestBed's module reset and surface as an unhandled
    // rejection in whichever test runs next — resolving it before the test ends keeps the module's
    // teardown clean, the same way a real, unbounded HTTP call is never actually left unresolved.
    let resolveFetch!: (response: OpsThresholdsResponse) => void;
    api.opsThresholds.mockReturnValue(new Promise<OpsThresholdsResponse>((resolve) => (resolveFetch = resolve)));
    const facade = create();

    expect(facade.battery()).toEqual(DEFAULT_BATTERY_THRESHOLDS);
    expect(facade.rc()).toEqual(DEFAULT_RC_THRESHOLDS);
    expect(facade.loaded()).toBe(false);

    resolveFetch({ battery: DEFAULT_BATTERY_THRESHOLDS, rc: DEFAULT_RC_THRESHOLDS });
    await flush();
  });

  it('fetches once at construction and exposes the served thresholds, including rc', async () => {
    api.opsThresholds.mockResolvedValue({
      battery: { warningPercent: 30, criticalPercent: 12 },
      rc: { neutralTolerancePercent: 8 },
    });
    const facade = create();
    await flush();

    expect(api.opsThresholds).toHaveBeenCalledOnce();
    expect(facade.battery()).toEqual({ warningPercent: 30, criticalPercent: 12 });
    expect(facade.rc()).toEqual({ neutralTolerancePercent: 8 });
    expect(facade.loaded()).toBe(true);
    expect(facade.error()).toBeUndefined();
  });

  it('degrades rc to the default when a served response simply omits it (BK1 not landed yet)', async () => {
    api.opsThresholds.mockResolvedValue({ battery: { warningPercent: 25, criticalPercent: 10 } });
    const facade = create();
    await flush();

    expect(facade.rc()).toEqual(DEFAULT_RC_THRESHOLDS);
    expect(facade.loaded()).toBe(true);
    expect(facade.error()).toBeUndefined();
  });

  it('degrades everything to the default on a failed fetch, never blocking or fabricating a value', async () => {
    api.opsThresholds.mockRejectedValue(new Error('network down'));
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const facade = create();
    await flush();

    expect(facade.battery()).toEqual(DEFAULT_BATTERY_THRESHOLDS);
    expect(facade.rc()).toEqual(DEFAULT_RC_THRESHOLDS);
    expect(facade.loaded()).toBe(false);
    expect(facade.error()).toBeDefined();
  });

  it('refresh() can be called again manually and re-fetches', async () => {
    const facade = create();
    await flush();
    expect(api.opsThresholds).toHaveBeenCalledTimes(1);

    await facade.refresh();

    expect(api.opsThresholds).toHaveBeenCalledTimes(2);
  });
});
