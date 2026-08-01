import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { TrainingStore } from './training-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import type { Dataset } from '../api/models';

function dataset(overrides: Partial<Dataset> = {}): Dataset {
  return {
    id: 'd-1',
    name: 'Buildings — site A',
    classes: ['building'],
    status: 'OPEN',
    createdAt: '2026-08-01T10:00:00Z',
    sampleCounts: { PENDING: 0, LABELED: 0, DISCARDED: 0 },
    ...overrides,
  };
}

function stubApi(
  overrides: Partial<Record<'listDatasets' | 'createDataset' | 'deleteDataset', ReturnType<typeof vi.fn>>> = {},
) {
  return {
    listDatasets: vi.fn().mockResolvedValue({ datasets: [] }),
    createDataset: vi.fn(),
    deleteDataset: vi.fn(),
    ...overrides,
  };
}

function create(api: ReturnType<typeof stubApi>) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  TestBed.configureTestingModule({
    providers: [TrainingStore, { provide: VisionApi, useValue: api }, { provide: ToastService, useValue: toasts }],
  });
  return { store: TestBed.inject(TrainingStore), toasts };
}

describe('TrainingStore', () => {
  it('starts empty, unloaded, not disabled', () => {
    const { store } = create(stubApi());
    expect(store.datasets()).toEqual([]);
    expect(store.loaded()).toBe(false);
    expect(store.disabled()).toBe(false);
  });

  it('refresh() populates datasets and flips loaded', async () => {
    const api = stubApi({ listDatasets: vi.fn().mockResolvedValue({ datasets: [dataset()] }) });
    const { store } = create(api);

    await store.refresh();

    expect(store.datasets()).toEqual([dataset()]);
    expect(store.loaded()).toBe(true);
    expect(store.disabled()).toBe(false);
  });

  it('a 404 on the list call sets disabled — an honest "not enabled here" state, no toast', async () => {
    const api = stubApi({
      listDatasets: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })),
    });
    const { store, toasts } = create(api);

    await store.refresh();

    expect(store.disabled()).toBe(true);
    expect(store.datasets()).toEqual([]);
    expect(store.loaded()).toBe(true);
    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('a non-404 failure toasts and leaves disabled false', async () => {
    const api = stubApi({
      listDatasets: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 500 })),
    });
    const { store, toasts } = create(api);

    await store.refresh();

    expect(store.disabled()).toBe(false);
    expect(toasts.error).toHaveBeenCalledTimes(1);
  });

  it('a quiet refresh suppresses the error toast', async () => {
    const api = stubApi({
      listDatasets: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 500 })),
    });
    const { store, toasts } = create(api);

    await store.refresh({ quiet: true });

    expect(toasts.error).not.toHaveBeenCalled();
  });

  it('createDataset() creates, refreshes, and toasts ok', async () => {
    const created = dataset({ id: 'd-2', name: 'Tanks' });
    const api = stubApi({
      createDataset: vi.fn().mockResolvedValue(created),
      listDatasets: vi.fn().mockResolvedValue({ datasets: [created] }),
    });
    const { store, toasts } = create(api);

    const result = await store.createDataset({ name: 'Tanks' });

    expect(result).toEqual(created);
    expect(store.datasets()).toEqual([created]);
    expect(toasts.ok).toHaveBeenCalledTimes(1);
  });

  it('createDataset() returns null and toasts on failure', async () => {
    const api = stubApi({
      createDataset: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 403 })),
    });
    const { store, toasts } = create(api);

    const result = await store.createDataset({ name: 'Tanks' });

    expect(result).toBeNull();
    expect(toasts.error).toHaveBeenCalledTimes(1);
  });

  it('deleteDataset() deletes, refreshes, and toasts ok', async () => {
    const api = stubApi({
      deleteDataset: vi.fn().mockResolvedValue(undefined),
      listDatasets: vi.fn().mockResolvedValue({ datasets: [] }),
    });
    const { store, toasts } = create(api);

    const result = await store.deleteDataset('d-1', 'Buildings');

    expect(result).toBe(true);
    expect(toasts.ok).toHaveBeenCalledTimes(1);
  });

  it('deleteDataset() returns false and toasts on failure', async () => {
    const api = stubApi({
      deleteDataset: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 403 })),
    });
    const { store, toasts } = create(api);

    const result = await store.deleteDataset('d-1', 'Buildings');

    expect(result).toBe(false);
    expect(toasts.error).toHaveBeenCalledTimes(1);
  });
});
