import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { firstValueFrom, ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { Dataset } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { ToastService } from '../../toast.service';
import { TrainingApiActions, TrainingPageActions } from './training.actions';
import { create$, deleteDataset$, notifyFailure$, notifySuccess$, refresh$ } from './training.effects';

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

function setup(apiOverrides: Partial<Record<string, ReturnType<typeof vi.fn>>> = {}) {
  const actions = new ReplaySubject<Action>(1);
  const toasts = { ok: vi.fn(), error: vi.fn() };
  const api = {
    listDatasets: vi.fn().mockResolvedValue({ datasets: [dataset()] }),
    createDataset: vi.fn().mockResolvedValue(dataset()),
    deleteDataset: vi.fn().mockResolvedValue(undefined),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
    ],
  });
  return { actions, toasts, api };
}

describe('training effects', () => {
  it('refresh$ succeeds with the dataset list', async () => {
    const { actions } = setup();
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(TrainingPageActions.refreshRequested({ quiet: false }));
    expect(await result).toEqual(TrainingApiActions.refreshSucceeded({ datasets: [dataset()] }));
  });

  it('refresh$ turns a 404 into Refresh Not Found, never an error', async () => {
    const { actions } = setup({ listDatasets: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(TrainingPageActions.refreshRequested({ quiet: false }));
    expect(await result).toEqual(TrainingApiActions.refreshNotFound());
  });

  it('refresh$ carries the error and the original quiet flag on a non-404 failure', async () => {
    const { actions } = setup({ listDatasets: vi.fn().mockRejectedValue(new Error('down')) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => refresh$()));
    actions.next(TrainingPageActions.refreshRequested({ quiet: true }));
    const action = await result;
    expect(action.type).toBe(TrainingApiActions.refreshFailed.type);
    expect((action as ReturnType<typeof TrainingApiActions.refreshFailed>).quiet).toBe(true);
  });

  it('create$ succeeds and folds in the post-create refresh', async () => {
    const { actions } = setup();
    const result = firstValueFrom(TestBed.runInInjectionContext(() => create$()));
    actions.next(TrainingPageActions.createRequested({ request: { name: 'Buildings' } }));
    const action = await result;
    expect(action.type).toBe(TrainingApiActions.createSucceeded.type);
    const succeeded = action as ReturnType<typeof TrainingApiActions.createSucceeded>;
    expect(succeeded.dataset).toEqual(dataset());
    expect(succeeded.datasets).toEqual([dataset()]);
    expect(succeeded.message).toBe('Created dataset "Buildings".');
  });

  it('create$ reports Failed when the create itself throws', async () => {
    const { actions } = setup({ createDataset: vi.fn().mockRejectedValue(new Error('409')) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => create$()));
    actions.next(TrainingPageActions.createRequested({ request: { name: 'Buildings' } }));
    expect((await result).type).toBe(TrainingApiActions.createFailed.type);
  });

  it('deleteDataset$ succeeds and folds in the post-delete refresh', async () => {
    const { actions } = setup({ listDatasets: vi.fn().mockResolvedValue({ datasets: [] }) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => deleteDataset$()));
    actions.next(TrainingPageActions.deleteRequested({ id: 'd-1', name: 'Buildings' }));
    const action = await result;
    expect(action).toEqual(TrainingApiActions.deleteSucceeded({ datasets: [], message: 'Deleted "Buildings".' }));
  });

  it('notifySuccess$ toasts the pre-built message for a succeeded action', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifySuccess$()).subscribe();
    actions.next(TrainingApiActions.createSucceeded({ dataset: dataset(), datasets: [dataset()], message: 'Created Buildings.' }));
    expect(toasts.ok).toHaveBeenCalledWith('Created Buildings.');
  });

  it('notifyFailure$ suppresses the toast for a quiet refreshFailed but not a loud one', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();
    actions.next(TrainingApiActions.refreshFailed({ error: 'nope', quiet: true }));
    expect(toasts.error).not.toHaveBeenCalled();

    actions.next(TrainingApiActions.refreshFailed({ error: 'nope', quiet: false }));
    expect(toasts.error).toHaveBeenCalledWith('nope');
  });

  it('notifyFailure$ always toasts a create/delete failure', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();
    actions.next(TrainingApiActions.createFailed({ error: 'nope' }));
    expect(toasts.error).toHaveBeenCalledWith('nope');
  });
});
