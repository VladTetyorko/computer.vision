import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { rulesFrom } from '../control-action-logic';
import { ControlProfileApiActions, ControlProfilePageActions } from './control-profile.actions';
import { initialControlProfileState } from './control-profile.model';

export const controlProfileFeature = createFeature({
  name: 'controlProfile',
  reducer: createReducer(
    initialControlProfileState,
    on(ControlProfilePageActions.loadRequested, (state) => ({ ...state, loading: true })),
    on(ControlProfileApiActions.loadSucceeded, (state, { profiles, catalog }) => ({
      ...state,
      profiles,
      catalog,
      loading: false,
      loaded: true,
    })),
    // Degrades honestly: `profiles`/`catalog` are left exactly where they were — never blocked,
    // never fabricated. Only `loading` moves, mirroring the old store's try/finally.
    on(ControlProfileApiActions.loadFailed, (state) => ({ ...state, loading: false })),
    // None of the four mutations below ever touched `loading`/`catalog` in the old store either —
    // only `load()` did. A failed mutation (no `on()` case for its `*Failed` action) leaves state
    // untouched: `ControlProfileFacade` re-throws the real error to the caller instead of reporting
    // it here (see that file's own doc comment for why).
    on(ControlProfileApiActions.createSucceeded, (state, { profiles }) => ({ ...state, profiles })),
    on(ControlProfileApiActions.updateSucceeded, (state, { profiles }) => ({ ...state, profiles })),
    on(ControlProfileApiActions.activateSucceeded, (state, { profiles }) => ({ ...state, profiles })),
    on(ControlProfileApiActions.deleteSucceeded, (state, { profiles }) => ({ ...state, profiles })),
  ),
  extraSelectors: ({ selectCatalog }) => ({
    /** The catalogue's own danger flags and switch levels, for `ControlActionDispatcher`. */
    selectRules: createSelector(selectCatalog, rulesFrom),
  }),
});
