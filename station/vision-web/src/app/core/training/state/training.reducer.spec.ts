import { describe, expect, it } from 'vitest';
import type { Dataset } from '../../api/models';
import { TrainingApiActions, TrainingPageActions } from './training.actions';
import { initialTrainingState } from './training.model';
import { trainingFeature } from './training.reducer';

const reduce = trainingFeature.reducer;

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

describe('training reducer', () => {
  it('starts empty, unloaded, not disabled', () => {
    expect(initialTrainingState).toEqual({ datasets: [], loading: false, loaded: false, disabled: false });
  });

  it('refreshRequested flips loading on; refreshSucceeded populates and flips loaded', () => {
    const requested = reduce(initialTrainingState, TrainingPageActions.refreshRequested({ quiet: false }));
    expect(requested.loading).toBe(true);

    const succeeded = reduce(requested, TrainingApiActions.refreshSucceeded({ datasets: [dataset()] }));
    expect(succeeded).toEqual({ datasets: [dataset()], loading: false, loaded: true, disabled: false });
  });

  it('refreshNotFound sets disabled and loaded, clearing datasets — an honest "not enabled here" state', () => {
    const loaded = reduce(initialTrainingState, TrainingApiActions.refreshSucceeded({ datasets: [dataset()] }));
    const requested = reduce(loaded, TrainingPageActions.refreshRequested({ quiet: false }));
    const notFound = reduce(requested, TrainingApiActions.refreshNotFound());

    expect(notFound).toEqual({ datasets: [], loading: false, loaded: true, disabled: true });
  });

  it('refreshFailed only clears loading — the previous list/loaded/disabled survive', () => {
    const loaded = reduce(initialTrainingState, TrainingApiActions.refreshSucceeded({ datasets: [dataset()] }));
    const requested = reduce(loaded, TrainingPageActions.refreshRequested({ quiet: true }));
    const failed = reduce(requested, TrainingApiActions.refreshFailed({ error: 'boom', quiet: true }));

    expect(failed).toEqual({ ...loaded, loading: false });
  });

  it('createSucceeded/deleteSucceeded replace the list, flip loaded, and clear disabled', () => {
    const disabled = { ...initialTrainingState, disabled: true };
    const created = reduce(
      disabled,
      TrainingApiActions.createSucceeded({ dataset: dataset(), datasets: [dataset()], message: 'x' }),
    );
    expect(created).toEqual({ datasets: [dataset()], loading: false, loaded: true, disabled: false });

    const deleted = reduce(created, TrainingApiActions.deleteSucceeded({ datasets: [], message: 'x' }));
    expect(deleted).toEqual({ datasets: [], loading: false, loaded: true, disabled: false });
  });
});
