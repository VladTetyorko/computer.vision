import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SettingsStore } from './settings-store';
import { DEFAULT_DECLUTTER_LEVEL } from '../../shared/player/detection-overlay-logic';

describe('SettingsStore', () => {
  let store: SettingsStore;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    store = TestBed.inject(SettingsStore);
  });

  it('defaults the map layer to night and persists a change across reload', () => {
    expect(store.mapLayer()).toBe('night');

    store.mapLayer.set('satellite');
    TestBed.tick(); // flushes the `effect()` that persists settings to localStorage
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.mapLayer()).toBe('satellite');
  });

  it('ignores a corrupt persisted map layer rather than adopting it', () => {
    localStorage.setItem('vision.settings.v1', JSON.stringify({ mapLayer: 'lunar' }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.mapLayer()).toBe('night');
  });

  it('defaults event notifications to off and persists a change across reload', () => {
    expect(store.eventNotifications()).toBe(false);

    store.eventNotifications.set(true);
    TestBed.tick();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.eventNotifications()).toBe(true);
  });

  it('ignores a corrupt persisted eventNotifications value rather than adopting it', () => {
    localStorage.setItem('vision.settings.v1', JSON.stringify({ eventNotifications: 'yes' }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.eventNotifications()).toBe(false);
  });

  it('defaults the fly asset to unset and persists a choice across reload', () => {
    expect(store.flyAssetId()).toBeNull();

    store.flyAssetId.set('asset-42');
    TestBed.tick();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.flyAssetId()).toBe('asset-42');
  });

  it('ignores a non-string persisted flyAssetId rather than adopting it', () => {
    localStorage.setItem('vision.settings.v1', JSON.stringify({ flyAssetId: 42 }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.flyAssetId()).toBeNull();
  });

  // ---- Declutter level (docs/plans/active/CV-SETTINGS-PLAN.md wave W7, H12) ------------------
  // One shared, persisted preference — replaces the three unshared in-memory `boxesMode` signals
  // that used to live on `CockpitFacade`/`LiveFacade`/`WallTile`.

  it('defaults the declutter level to the shared default and persists a change across reload', () => {
    expect(store.declutterLevel()).toBe(DEFAULT_DECLUTTER_LEVEL);

    store.declutterLevel.set('locked');
    TestBed.tick();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.declutterLevel()).toBe('locked');
  });

  it('ignores a corrupt/pre-wave persisted declutter level rather than adopting it', () => {
    localStorage.setItem('vision.settings.v1', JSON.stringify({ declutterLevel: 'overlay' }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.declutterLevel()).toBe(DEFAULT_DECLUTTER_LEVEL);
  });

  it('survives corrupt persisted settings', () => {
    localStorage.setItem('vision.settings.v1', '{not json');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    expect(() => TestBed.inject(SettingsStore)).not.toThrow();
  });
});
