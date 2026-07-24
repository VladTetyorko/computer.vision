import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BUILT_IN_PROFILES, SettingsStore } from './settings-store';

describe('SettingsStore', () => {
  let store: SettingsStore;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    store = TestBed.inject(SettingsStore);
  });

  it('starts on the profile that mirrors the backend defaults', () => {
    expect(store.activeProfile().id).toBe('balanced');
    expect(store.effective()).toEqual({ confidenceThreshold: 0.4, inferenceFps: 5 });
    expect(store.isCustom()).toBe(false);
  });

  it('applies a preset wholesale', () => {
    store.selectProfile('low-latency');
    expect(store.effective().inferenceFps).toBe(3);
    expect(store.isCustom()).toBe(false);
  });

  it('turns an edit into a revertible custom draft rather than losing the preset', () => {
    store.selectProfile('low-latency');
    store.adjust({ confidenceThreshold: 0.8 });

    expect(store.isCustom()).toBe(true);
    expect(store.effective()).toEqual({ confidenceThreshold: 0.8, inferenceFps: 3 });
    // The preset it is based on is still identifiable, which is what the badge shows.
    expect(store.activeProfile().id).toBe('low-latency');

    store.revertDraft();
    expect(store.isCustom()).toBe(false);
    expect(store.effective().confidenceThreshold).toBe(0.5);
  });

  it('discards a draft when another preset is chosen', () => {
    store.adjust({ inferenceFps: 20 });
    store.selectProfile('high-quality');
    expect(store.isCustom()).toBe(false);
    expect(store.effective().inferenceFps).toBe(10);
  });

  it('saves a draft as a reusable profile and selects it', () => {
    store.adjust({ inferenceFps: 12, confidenceThreshold: 0.25 });
    store.saveDraftAs('Driveway');

    const saved = store.customProfiles();
    expect(saved).toHaveLength(1);
    expect(saved[0].name).toBe('Driveway');
    expect(saved[0].builtIn).toBe(false);
    expect(store.activeProfileId()).toBe(saved[0].id);
    expect(store.isCustom()).toBe(false);
    expect(store.effective()).toEqual({ confidenceThreshold: 0.25, inferenceFps: 12 });
  });

  it('falls back to a built-in when a deleted profile was active', () => {
    store.adjust({ inferenceFps: 7 });
    store.saveDraftAs('Temporary');
    store.deleteCustomProfile(store.activeProfileId());

    expect(store.customProfiles()).toHaveLength(0);
    expect(store.activeProfile().id).toBe(BUILT_IN_PROFILES[0].id);
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

  it('survives corrupt persisted settings', () => {
    localStorage.setItem('vision.settings.v1', '{not json');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    expect(() => TestBed.inject(SettingsStore)).not.toThrow();
  });
});
