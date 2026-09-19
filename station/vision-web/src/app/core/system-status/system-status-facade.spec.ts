import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { SystemStatus } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { SystemStatusFacade } from './system-status-facade';

/**
 * `SystemStatusFacade`'s own read/dispatch boundary spec (docs/plans/done/NGRX-MIGRATION-PLAN.md
 * wave N4b) — `provideAppState()` registers the real `systemStatus` slice + its effects (root, see
 * `core/state/app-state.ts`'s own doc comment), `VisionApi`/`PollScheduler` stubbed. The slice's own
 * folding/gating logic already has full coverage in `state/system-status.reducer.spec.ts`/
 * `state/system-status.effects.spec.ts`; this file only proves the facade signals/`refresh()` read
 * and dispatch correctly end to end — also needs no override for `fleet.effects.ts#gate$` (rides
 * along automatically, see `core/live/poll-rate.spec.ts`'s own "wave N4b" doc comment) since
 * `VisionApi`'s real class isn't constructed here (its whole surface is stubbed below).
 */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function status(partial: Partial<SystemStatus> = {}): SystemStatus {
  return { overall: 'OK', checkedAt: '2026-08-15T00:00:00Z', subsystems: [], ...partial };
}

function create(apiOverrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  const api = {
    systemStatus: vi.fn().mockResolvedValue(status()),
    listDevices: vi.fn().mockResolvedValue([]),
    listStreams: vi.fn().mockResolvedValue([]),
    getCvModels: vi.fn().mockResolvedValue({ models: [] }),
    getCvTrackers: vi.fn().mockResolvedValue({ trackers: [] }),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: { schedule: vi.fn().mockReturnValue(vi.fn()) } },
    ],
  });
  return { facade: TestBed.inject(SystemStatusFacade), api };
}

describe('SystemStatusFacade', () => {
  it('fetches at boot (gate$) and exposes status/overall', async () => {
    const { facade } = create({ systemStatus: vi.fn().mockResolvedValue(status({ overall: 'DEGRADED' })) });
    await flush();

    expect(facade.status()?.overall).toBe('DEGRADED');
    expect(facade.overall()).toBe('DEGRADED');
    expect(facade.error()).toBeUndefined();
  });

  it('refresh() resolves once the reconcile settles, and a rejected fetch sets error without wiping status', async () => {
    const systemStatus = vi.fn().mockResolvedValueOnce(status({ overall: 'OK' })).mockRejectedValueOnce(new Error('down'));
    const { facade } = create({ systemStatus });
    await flush();
    expect(facade.status()?.overall).toBe('OK');

    await facade.refresh();

    expect(facade.status()?.overall).toBe('OK'); // stale-but-present, never wiped
    expect(facade.error()).toBeDefined();
  });
});
