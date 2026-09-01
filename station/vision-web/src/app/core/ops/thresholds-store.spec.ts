import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ThresholdsStore } from './thresholds-store';
import { VisionApi } from '../api/vision-api';
import { DEFAULT_BATTERY_THRESHOLDS } from './thresholds-logic';
import type { OpsThresholdsResponse } from '../api/models';

function create(api: { opsThresholds: ReturnType<typeof vi.fn> }): ThresholdsStore {
  TestBed.configureTestingModule({
    providers: [ThresholdsStore, { provide: VisionApi, useValue: api }],
  });
  return TestBed.inject(ThresholdsStore);
}

/** Lets the fire-and-forget promise chain inside the constructor's `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('ThresholdsStore', () => {
  it('starts at the honest default before the first fetch ever resolves', () => {
    const api = { opsThresholds: vi.fn(() => new Promise<OpsThresholdsResponse>(() => {})) };
    const store = create(api);

    expect(store.battery()).toEqual(DEFAULT_BATTERY_THRESHOLDS);
    expect(store.loaded()).toBe(false);
  });

  it('fetches once at construction and exposes the served thresholds', async () => {
    const api = {
      opsThresholds: vi.fn().mockResolvedValue({ battery: { warningPercent: 30, criticalPercent: 12 } }),
    };
    const store = create(api);
    await flush();

    expect(api.opsThresholds).toHaveBeenCalledOnce();
    expect(store.battery()).toEqual({ warningPercent: 30, criticalPercent: 12 });
    expect(store.loaded()).toBe(true);
    expect(store.error()).toBeUndefined();
  });

  it('degrades to the default on a failed fetch, never blocking or fabricating a value', async () => {
    const api = { opsThresholds: vi.fn().mockRejectedValue(new Error('network down')) };
    const store = create(api);
    await flush();

    expect(store.battery()).toEqual(DEFAULT_BATTERY_THRESHOLDS);
    expect(store.loaded()).toBe(false);
    expect(store.error()).toBeDefined();
  });
});
