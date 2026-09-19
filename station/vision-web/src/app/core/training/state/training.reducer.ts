import { createFeature, createReducer, on } from '@ngrx/store';
import { TrainingApiActions, TrainingPageActions } from './training.actions';
import { initialTrainingState } from './training.model';

export const trainingFeature = createFeature({
  name: 'training',
  reducer: createReducer(
    initialTrainingState,
    on(TrainingPageActions.refreshRequested, (state) => ({ ...state, loading: true })),
    on(TrainingApiActions.refreshSucceeded, (state, { datasets }) => ({
      ...state,
      datasets,
      disabled: false,
      loaded: true,
      loading: false,
    })),
    on(TrainingApiActions.refreshNotFound, (state) => ({
      ...state,
      datasets: [],
      disabled: true,
      loaded: true,
      loading: false,
    })),
    // A genuine (non-404) failure only clears `loading` — the previous list/loaded/disabled survive,
    // mirroring `OrgApiActions.refreshFailed`'s identical shape.
    on(TrainingApiActions.refreshFailed, (state) => ({ ...state, loading: false })),
    on(TrainingApiActions.createSucceeded, (state, { datasets }) => ({
      ...state,
      datasets,
      disabled: false,
      loaded: true,
    })),
    on(TrainingApiActions.deleteSucceeded, (state, { datasets }) => ({
      ...state,
      datasets,
      disabled: false,
      loaded: true,
    })),
  ),
});
