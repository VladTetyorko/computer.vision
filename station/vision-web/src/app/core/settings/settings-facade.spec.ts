import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_DECLUTTER_LEVEL } from '../../shared/player/detection-overlay-logic';
import { provideAppState } from '../state/app-state';
import { SettingsFacade } from './settings-facade';

const STORAGE_KEY = 'vision.settings.v1';

/**
 * The settings slice end to end — facade → action → reducer → effect → one `localStorage` blob.
 * Replaces `SettingsStore`'s own spec case for case (docs/plans/done/NGRX-MIGRATION-PLAN.md N2).
 */
describe('SettingsFacade', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideAppState()] });
  });

  function reload(): SettingsFacade {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideAppState()] });
    return TestBed.inject(SettingsFacade);
  }

  it('defaults the map layer to night and persists a change across reload', () => {
    const facade = TestBed.inject(SettingsFacade);
    expect(facade.mapLayer()).toBe('night');

    facade.mapLayer.set('satellite');
    expect(reload().mapLayer()).toBe('satellite');
  });

  it('ignores a corrupt persisted map layer rather than adopting it', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ mapLayer: 'lunar' }));
    expect(reload().mapLayer()).toBe('night');
  });

  it('defaults event notifications to off and persists a change across reload', () => {
    const facade = TestBed.inject(SettingsFacade);
    expect(facade.eventNotifications()).toBe(false);

    facade.eventNotifications.set(true);
    expect(reload().eventNotifications()).toBe(true);
  });

  it('ignores a corrupt persisted eventNotifications value rather than adopting it', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ eventNotifications: 'yes' }));
    expect(reload().eventNotifications()).toBe(false);
  });

  it('defaults the fly asset to unset and persists a choice across reload', () => {
    const facade = TestBed.inject(SettingsFacade);
    expect(facade.flyAssetId()).toBeNull();

    facade.flyAssetId.set('asset-42');
    expect(reload().flyAssetId()).toBe('asset-42');
  });

  it('ignores a non-string persisted flyAssetId rather than adopting it', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ flyAssetId: 42 }));
    expect(reload().flyAssetId()).toBeNull();
  });

  it('defaults the declutter level to the shared default and persists a change across reload', () => {
    const facade = TestBed.inject(SettingsFacade);
    expect(facade.declutterLevel()).toBe(DEFAULT_DECLUTTER_LEVEL);

    facade.declutterLevel.set('locked');
    expect(reload().declutterLevel()).toBe('locked');
  });

  it('ignores a corrupt/pre-wave persisted declutter level rather than adopting it', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ declutterLevel: 'overlay' }));
    expect(reload().declutterLevel()).toBe(DEFAULT_DECLUTTER_LEVEL);
  });

  it('defaults cropFollowEnabled to false and persists a change across reload', () => {
    const facade = TestBed.inject(SettingsFacade);
    expect(facade.cropFollowEnabled()).toBe(false);

    facade.cropFollowEnabled.set(true);
    expect(reload().cropFollowEnabled()).toBe(true);
  });

  it('ignores a corrupt persisted cropFollowEnabled value rather than adopting it', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ cropFollowEnabled: 'yes' }));
    expect(reload().cropFollowEnabled()).toBe(false);
  });

  it('survives corrupt persisted settings', () => {
    localStorage.setItem(STORAGE_KEY, '{not json');
    expect(() => reload()).not.toThrow();
  });

  it('update() works exactly like a real WritableSignal, for the aliasing consumers (WallFacade et al.)', () => {
    const facade = TestBed.inject(SettingsFacade);
    facade.wallDensity.set(3);

    facade.wallDensity.update((n) => n + 1);
    expect(facade.wallDensity()).toBe(4);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}').wallDensity).toBe(4);
  });

  it('advancedMode persists alongside every other field in the same JSON blob', () => {
    const facade = TestBed.inject(SettingsFacade);
    facade.advancedMode.set(true);
    facade.wallDensity.set(6);

    const restored = reload();
    expect(restored.advancedMode()).toBe(true);
    expect(restored.wallDensity()).toBe(6);
  });
});
