import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Dataset } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { provideAppState } from '../state/app-state';
import { TrainingFacade } from './training-facade';

function dataset(overrides: Partial<Dataset> = {}): Dataset {
  return {
    id: 'd-1',
    name: 'Buildings',
    classes: ['building'],
    status: 'OPEN',
    createdAt: '2026-08-01T10:00:00Z',
    sampleCounts: { PENDING: 0, LABELED: 0, DISCARDED: 0 },
    ...overrides,
  };
}

describe('TrainingFacade', () => {
  let api: {
    listDatasets: ReturnType<typeof vi.fn>;
    createDataset: ReturnType<typeof vi.fn>;
    deleteDataset: ReturnType<typeof vi.fn>;
  };
  let toasts: { ok: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      listDatasets: vi.fn().mockResolvedValue({ datasets: [dataset()] }),
      createDataset: vi.fn().mockResolvedValue(dataset()),
      deleteDataset: vi.fn().mockResolvedValue(undefined),
    };
    toasts = { ok: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [provideAppState(), { provide: VisionApi, useValue: api }, { provide: ToastService, useValue: toasts }],
    });
  });

  it('starts empty, unloaded, not disabled', () => {
    const facade = TestBed.inject(TrainingFacade);
    expect(facade.datasets()).toEqual([]);
    expect(facade.loaded()).toBe(false);
    expect(facade.disabled()).toBe(false);
  });

  it('refresh() populates datasets and flips loaded', async () => {
    const facade = TestBed.inject(TrainingFacade);
    await facade.refresh();
    expect(facade.datasets()).toEqual([dataset()]);
    expect(facade.loaded()).toBe(true);
    expect(facade.disabled()).toBe(false);
  });

  it('a 404 on the list call sets disabled — an honest "not enabled here" state, no toast', async () => {
    api.listDatasets.mockRejectedValue(new HttpErrorResponse({ status: 404 }));
    const facade = TestBed.inject(TrainingFacade);

    await facade.refresh();

    expect(facade.disabled()).toBe(true);
    expect(facade.datasets()).toEqual([]);
    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('a non-404 failure toasts and leaves disabled false', async () => {
    api.listDatasets.mockRejectedValue(new HttpErrorResponse({ status: 500 }));
    const facade = TestBed.inject(TrainingFacade);

    await facade.refresh();

    expect(facade.disabled()).toBe(false);
    expect(toasts.error).toHaveBeenCalledTimes(1);
  });

  it('a quiet refresh suppresses the error toast', async () => {
    api.listDatasets.mockRejectedValue(new HttpErrorResponse({ status: 500 }));
    const facade = TestBed.inject(TrainingFacade);

    await facade.refresh({ quiet: true });

    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('createDataset() creates, refreshes, and toasts ok', async () => {
    const created = dataset({ id: 'd-2', name: 'Tanks' });
    api.createDataset.mockResolvedValue(created);
    api.listDatasets.mockResolvedValue({ datasets: [created] });
    const facade = TestBed.inject(TrainingFacade);

    const result = await facade.createDataset({ name: 'Tanks' });

    expect(result).toEqual(created);
    expect(facade.datasets()).toEqual([created]);
    expect(toasts.ok).toHaveBeenCalledTimes(1);
  });

  it('createDataset() returns null and toasts on failure', async () => {
    api.createDataset.mockRejectedValue(new HttpErrorResponse({ status: 403 }));
    const facade = TestBed.inject(TrainingFacade);

    const result = await facade.createDataset({ name: 'Tanks' });

    expect(result).toBeNull();
    expect(toasts.error).toHaveBeenCalledTimes(1);
  });

  it('deleteDataset() deletes, refreshes, and toasts ok', async () => {
    api.listDatasets.mockResolvedValue({ datasets: [] });
    const facade = TestBed.inject(TrainingFacade);

    const result = await facade.deleteDataset('d-1', 'Buildings');

    expect(result).toBe(true);
    expect(toasts.ok).toHaveBeenCalledTimes(1);
  });

  it('deleteDataset() returns false and toasts on failure', async () => {
    api.deleteDataset.mockRejectedValue(new HttpErrorResponse({ status: 403 }));
    const facade = TestBed.inject(TrainingFacade);

    const result = await facade.deleteDataset('d-1', 'Buildings');

    expect(result).toBe(false);
    expect(toasts.error).toHaveBeenCalledTimes(1);
  });
});
