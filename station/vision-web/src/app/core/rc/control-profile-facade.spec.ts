import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ControlCatalog, ControlProfile } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { provideAppState } from '../state/app-state';
import { ControlProfileFacade } from './control-profile-facade';

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

describe('ControlProfileFacade', () => {
  let api: {
    controlProfiles: ReturnType<typeof vi.fn>;
    controlCatalog: ReturnType<typeof vi.fn>;
    createControlProfile: ReturnType<typeof vi.fn>;
    updateControlProfile: ReturnType<typeof vi.fn>;
    activateControlProfile: ReturnType<typeof vi.fn>;
    deleteControlProfile: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      controlProfiles: vi.fn().mockResolvedValue([profile()]),
      controlCatalog: vi.fn().mockResolvedValue(catalog()),
      createControlProfile: vi.fn().mockResolvedValue(profile()),
      updateControlProfile: vi.fn().mockResolvedValue(profile()),
      activateControlProfile: vi.fn().mockResolvedValue(undefined),
      deleteControlProfile: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({ providers: [provideAppState(), { provide: VisionApi, useValue: api }] });
  });

  it('starts empty, unloaded, not loading', () => {
    const facade = TestBed.inject(ControlProfileFacade);
    expect(facade.profiles()).toEqual([]);
    expect(facade.catalog()).toBeUndefined();
    expect(facade.loading()).toBe(false);
    expect(facade.loaded()).toBe(false);
  });

  it('load() populates profiles and catalog, flips loaded', async () => {
    const facade = TestBed.inject(ControlProfileFacade);
    await facade.load();
    expect(facade.profiles()).toEqual([profile()]);
    expect(facade.catalog()).toEqual(catalog());
    expect(facade.loaded()).toBe(true);
  });

  it('load() is idempotent — a second call with no force does not re-fetch', async () => {
    const facade = TestBed.inject(ControlProfileFacade);
    await facade.load();
    await facade.load();
    expect(api.controlProfiles).toHaveBeenCalledOnce();
  });

  it('load({force: true}) re-fetches even after an earlier load already completed', async () => {
    const facade = TestBed.inject(ControlProfileFacade);
    await facade.load();
    await facade.load(true);
    expect(api.controlProfiles).toHaveBeenCalledTimes(2);
  });

  it('load() rejects with the exact describeHttpError message on a failed fetch, for a caller to catch', async () => {
    api.controlProfiles.mockRejectedValue(new HttpErrorResponse({ status: 404 }));
    const facade = TestBed.inject(ControlProfileFacade);

    await expect(facade.load()).rejects.toThrow('That no longer exists — it may have been removed already.');
  });

  it('create() resolves with the created profile', async () => {
    const created = profile({ id: 'p-2', name: 'Second' });
    api.createControlProfile.mockResolvedValue(created);
    api.controlProfiles.mockResolvedValue([profile(), created]);
    const facade = TestBed.inject(ControlProfileFacade);

    const result = await facade.create({ kind: 'COPTER', name: 'Second' });

    expect(result).toEqual(created);
    expect(facade.profiles()).toEqual([profile(), created]);
  });

  it('create() rejects with a describeHttpError-composed message on failure', async () => {
    api.createControlProfile.mockRejectedValue(new HttpErrorResponse({ status: 409 }));
    const facade = TestBed.inject(ControlProfileFacade);

    await expect(facade.create({ kind: 'COPTER', name: 'Second' })).rejects.toThrow(
      'That conflicts with the current state — is the stream already running?',
    );
  });

  it('update() resolves with the updated profile', async () => {
    const updated = profile({ name: 'Renamed' });
    api.updateControlProfile.mockResolvedValue(updated);
    api.controlProfiles.mockResolvedValue([updated]);
    const facade = TestBed.inject(ControlProfileFacade);

    const result = await facade.update('p-1', { name: 'Renamed', channelMap: [], actionMap: [] });

    expect(result).toEqual(updated);
    expect(facade.profiles()).toEqual([updated]);
  });

  it('activate() re-reads the list after activating', async () => {
    api.controlProfiles.mockResolvedValue([profile({ active: true })]);
    const facade = TestBed.inject(ControlProfileFacade);

    await facade.activate('p-1');

    expect(api.activateControlProfile).toHaveBeenCalledWith('p-1');
    expect(facade.profiles()).toEqual([profile({ active: true })]);
  });

  it('delete() re-reads the list after deleting', async () => {
    api.controlProfiles.mockResolvedValue([]);
    const facade = TestBed.inject(ControlProfileFacade);

    await facade.delete('p-1');

    expect(api.deleteControlProfile).toHaveBeenCalledWith('p-1');
    expect(facade.profiles()).toEqual([]);
  });

  it('a failed mutation leaves profiles exactly where they were', async () => {
    const facade = TestBed.inject(ControlProfileFacade);
    await facade.load();
    api.updateControlProfile.mockRejectedValue(new HttpErrorResponse({ status: 403 }));

    await expect(facade.update('p-1', { name: 'x', channelMap: [], actionMap: [] })).rejects.toThrow(
      'You do not have access to that.',
    );
    expect(facade.profiles()).toEqual([profile()]);
  });
});
