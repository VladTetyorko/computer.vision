import { describe, expect, it } from 'vitest';
import type { ControlCatalog, ControlProfile } from '../../api/models';
import { ControlProfileApiActions, ControlProfilePageActions } from './control-profile.actions';
import { initialControlProfileState } from './control-profile.model';
import { controlProfileFeature } from './control-profile.reducer';

const { reducer } = controlProfileFeature;

function profile(overrides: Partial<ControlProfile> = {}): ControlProfile {
  return {
    id: 'p-1',
    source: 'SAVED',
    kind: 'COPTER',
    code: 'p-1',
    name: 'My layout',
    active: true,
    channelMap: [],
    actionMap: [],
    stickMode: 2,
    forwardIsUp: true,
    ...overrides,
  };
}

function catalog(overrides: Partial<ControlCatalog> = {}): ControlCatalog {
  return {
    vehicleKinds: [],
    inputKinds: [],
    positions: [{ name: 'HIGH', label: 'High', level: 2 }],
    actions: [{ name: 'RETURN_TO_HOME', label: 'Return to launch', parameter: 'NONE', dangerous: true }],
    functions: [],
    auxFunctions: [],
    maxRcChannel: 16,
    ...overrides,
  };
}

describe('controlProfileFeature reducer', () => {
  it('starts empty, unloaded, not loading', () => {
    expect(initialControlProfileState.profiles).toEqual([]);
    expect(initialControlProfileState.catalog).toBeUndefined();
    expect(initialControlProfileState.loading).toBe(false);
    expect(initialControlProfileState.loaded).toBe(false);
  });

  it('loadRequested flips loading on', () => {
    const state = reducer(initialControlProfileState, ControlProfilePageActions.loadRequested({ force: false }));
    expect(state.loading).toBe(true);
  });

  it('loadSucceeded stores profiles and catalog, flips loaded, clears loading', () => {
    const state = reducer(
      { ...initialControlProfileState, loading: true },
      ControlProfileApiActions.loadSucceeded({ profiles: [profile()], catalog: catalog() }),
    );
    expect(state.profiles).toEqual([profile()]);
    expect(state.catalog).toEqual(catalog());
    expect(state.loaded).toBe(true);
    expect(state.loading).toBe(false);
  });

  it('loadFailed only clears loading — profiles/catalog are left exactly where they were', () => {
    const loaded = reducer(
      initialControlProfileState,
      ControlProfileApiActions.loadSucceeded({ profiles: [profile()], catalog: catalog() }),
    );
    const state = reducer(
      { ...loaded, loading: true },
      ControlProfileApiActions.loadFailed({ error: 'boom' }),
    );
    expect(state.profiles).toEqual([profile()]);
    expect(state.catalog).toEqual(catalog());
    expect(state.loading).toBe(false);
  });

  it('a mutation success replaces profiles only — never loading/catalog', () => {
    const loaded = reducer(
      initialControlProfileState,
      ControlProfileApiActions.loadSucceeded({ profiles: [profile()], catalog: catalog() }),
    );
    const created = profile({ id: 'p-2', name: 'Second layout' });
    const state = reducer(
      loaded,
      ControlProfileApiActions.createSucceeded({ profile: created, profiles: [profile(), created] }),
    );
    expect(state.profiles).toEqual([profile(), created]);
    expect(state.catalog).toEqual(catalog());
    expect(state.loading).toBe(false);
  });

  it('a mutation failure (unhandled action) leaves state untouched', () => {
    const loaded = reducer(
      initialControlProfileState,
      ControlProfileApiActions.loadSucceeded({ profiles: [profile()], catalog: catalog() }),
    );
    const state = reducer(loaded, ControlProfileApiActions.createFailed({ error: 'boom' }));
    expect(state).toEqual(loaded);
  });

  describe('selectRules', () => {
    it('is empty before any catalog has loaded', () => {
      const rules = controlProfileFeature.selectRules.projector(initialControlProfileState.catalog);
      expect(rules.dangerous.size).toBe(0);
      expect(rules.levels.size).toBe(0);
    });

    it('reflects the loaded catalog once one exists', () => {
      const state = reducer(
        initialControlProfileState,
        ControlProfileApiActions.loadSucceeded({ profiles: [], catalog: catalog() }),
      );
      const rules = controlProfileFeature.selectRules.projector(state.catalog);
      expect(rules.dangerous.has('RETURN_TO_HOME')).toBe(true);
      expect(rules.levels.get('HIGH')).toBe(2);
    });
  });
});
