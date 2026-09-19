import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { firstValueFrom, ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { ControlCatalog, ControlProfile } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { ControlProfileApiActions, ControlProfilePageActions } from './control-profile.actions';
import { activate$, create$, deleteProfile$, load$, update$ } from './control-profile.effects';

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

function catalog(): ControlCatalog {
  return {
    vehicleKinds: [],
    inputKinds: [],
    positions: [],
    actions: [],
    functions: [],
    auxFunctions: [],
    maxRcChannel: 16,
  };
}

function setup(apiOverrides: Partial<Record<string, ReturnType<typeof vi.fn>>> = {}) {
  const actions = new ReplaySubject<Action>(1);
  const api = {
    controlProfiles: vi.fn().mockResolvedValue([profile()]),
    controlCatalog: vi.fn().mockResolvedValue(catalog()),
    createControlProfile: vi.fn().mockResolvedValue(profile()),
    updateControlProfile: vi.fn().mockResolvedValue(profile()),
    activateControlProfile: vi.fn().mockResolvedValue(undefined),
    deleteControlProfile: vi.fn().mockResolvedValue(undefined),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [provideMockActions(() => actions), { provide: VisionApi, useValue: api }],
  });
  return { actions, api };
}

describe('control-profile effects', () => {
  it('load$ succeeds with both profiles and catalog', async () => {
    const { actions } = setup();
    const result = firstValueFrom(TestBed.runInInjectionContext(() => load$()));
    actions.next(ControlProfilePageActions.loadRequested({ force: false }));
    expect(await result).toEqual(ControlProfileApiActions.loadSucceeded({ profiles: [profile()], catalog: catalog() }));
  });

  it('load$ turns a failure into a described Load Failed, never a thrown/swallowed error', async () => {
    const { actions } = setup({ controlProfiles: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => load$()));
    actions.next(ControlProfilePageActions.loadRequested({ force: false }));
    const action = await result;
    expect(action.type).toBe(ControlProfileApiActions.loadFailed.type);
    expect((action as ReturnType<typeof ControlProfileApiActions.loadFailed>).error).toBe(
      'That no longer exists — it may have been removed already.',
    );
  });

  it('create$ creates then folds in the re-read profile list', async () => {
    const created = profile({ id: 'p-2', name: 'Second' });
    const { actions } = setup({
      createControlProfile: vi.fn().mockResolvedValue(created),
      controlProfiles: vi.fn().mockResolvedValue([profile(), created]),
    });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => create$()));
    actions.next(ControlProfilePageActions.createRequested({ request: { kind: 'COPTER', name: 'Second' } }));
    expect(await result).toEqual(
      ControlProfileApiActions.createSucceeded({ profile: created, profiles: [profile(), created] }),
    );
  });

  it('create$ reports Failed with a described message when the create itself throws', async () => {
    const { actions } = setup({ createControlProfile: vi.fn().mockRejectedValue(new Error('down')) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => create$()));
    actions.next(ControlProfilePageActions.createRequested({ request: { kind: 'COPTER', name: 'Second' } }));
    const action = await result;
    expect(action.type).toBe(ControlProfileApiActions.createFailed.type);
    expect((action as ReturnType<typeof ControlProfileApiActions.createFailed>).error).toBe('down');
  });

  it('update$ updates then folds in the re-read profile list', async () => {
    const updated = profile({ name: 'Renamed' });
    const { actions } = setup({
      updateControlProfile: vi.fn().mockResolvedValue(updated),
      controlProfiles: vi.fn().mockResolvedValue([updated]),
    });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => update$()));
    actions.next(
      ControlProfilePageActions.updateRequested({
        id: 'p-1',
        request: { name: 'Renamed', channelMap: [], actionMap: [] },
      }),
    );
    expect(await result).toEqual(ControlProfileApiActions.updateSucceeded({ profile: updated, profiles: [updated] }));
  });

  it('activate$ activates then folds in the re-read profile list', async () => {
    const { actions, api } = setup({ controlProfiles: vi.fn().mockResolvedValue([profile({ active: true })]) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => activate$()));
    actions.next(ControlProfilePageActions.activateRequested({ id: 'p-1' }));
    expect(await result).toEqual(ControlProfileApiActions.activateSucceeded({ profiles: [profile({ active: true })] }));
    expect(api.activateControlProfile).toHaveBeenCalledWith('p-1');
  });

  it('deleteProfile$ deletes then folds in the re-read profile list', async () => {
    const { actions } = setup({ controlProfiles: vi.fn().mockResolvedValue([]) });
    const result = firstValueFrom(TestBed.runInInjectionContext(() => deleteProfile$()));
    actions.next(ControlProfilePageActions.deleteRequested({ id: 'p-1' }));
    expect(await result).toEqual(ControlProfileApiActions.deleteSucceeded({ profiles: [] }));
  });
});
