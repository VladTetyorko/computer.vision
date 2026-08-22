import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BUILT_IN_PROFILES, DEFAULT_DETECTION_MODEL, SettingsStore } from './settings-store';

describe('SettingsStore', () => {
  let store: SettingsStore;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    store = TestBed.inject(SettingsStore);
  });

  it('starts on the profile that mirrors the backend defaults', () => {
    expect(store.activeProfile().id).toBe('balanced');
    expect(store.effective()).toEqual({
      confidenceThreshold: 0.4,
      inferenceFps: 5,
      model: DEFAULT_DETECTION_MODEL,
      labelFilter: [],
      labelDenyFilter: [],
      // docs/plans/active/CV-DEMAND-PLAN.md wave D3: detection is opt-in now, off until the operator turns
      // it on, both server-side (PipelineConfig.DEFAULT_DETECTION_ENABLED) and here.
      detectionEnabled: false,
    });
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
    expect(store.effective()).toEqual({
      confidenceThreshold: 0.8,
      inferenceFps: 3,
      model: DEFAULT_DETECTION_MODEL,
      labelFilter: [],
      labelDenyFilter: [],
      detectionEnabled: false,
    });
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
    expect(store.effective()).toEqual({
      confidenceThreshold: 0.25,
      inferenceFps: 12,
      model: DEFAULT_DETECTION_MODEL,
      labelFilter: [],
      labelDenyFilter: [],
      detectionEnabled: false,
    });
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

  // ---- Detection model (docs/plans/done/CV-CONTROL-PLAN.md Wave E, extending docs/plans/done/CV-MODELS-PLAN.md item 4) --

  it('defaults every built-in profile to the general model, all classes, detection off (docs/plans/active/CV-DEMAND-PLAN.md wave D3)', () => {
    for (const profile of BUILT_IN_PROFILES) {
      expect(profile.model).toBe(DEFAULT_DETECTION_MODEL);
      expect(profile.labelFilter).toEqual([]);
      // docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2, wave W5: every built-in also denies nothing.
      expect(profile.labelDenyFilter).toEqual([]);
      expect(profile.detectionEnabled).toBe(false);
    }
  });

  // ---- Label deny-list (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2, wave W5) -------------------------

  it('adjusting labelDenyFilter turns the active profile into a revertible custom draft, independent of labelFilter', () => {
    store.adjust({ labelDenyFilter: ['tree', 'bush'] });

    expect(store.isCustom()).toBe(true);
    expect(store.effective().labelDenyFilter).toEqual(['tree', 'bush']);
    // The allowlist stays untouched by a deny-list edit — the two axes are independent.
    expect(store.effective().labelFilter).toEqual([]);

    store.revertDraft();
    expect(store.effective().labelDenyFilter).toEqual([]);
  });

  it('saves a draft labelDenyFilter choice as a reusable profile', () => {
    store.adjust({ labelDenyFilter: ['tree'] });
    store.saveDraftAs('No trees');

    const saved = store.customProfiles();
    expect(saved).toHaveLength(1);
    expect(saved[0].labelDenyFilter).toEqual(['tree']);
    expect(store.effective().labelDenyFilter).toEqual(['tree']);
  });

  it('backfills a missing labelDenyFilter on a profile/draft saved before this wave to "deny nothing"', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({
        activeProfileId: 'custom-pre-w5',
        customProfiles: [
          {
            id: 'custom-pre-w5',
            name: 'Pre-W5',
            description: 'Based on Balanced.',
            builtIn: false,
            confidenceThreshold: 0.4,
            inferenceFps: 5,
            model: DEFAULT_DETECTION_MODEL,
            labelFilter: [],
            detectionEnabled: false,
            // no `labelDenyFilter` field at all — every profile saved before wave W5.
          },
        ],
        draft: { confidenceThreshold: 0.6, inferenceFps: 8 },
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.customProfiles()[0].labelDenyFilter).toEqual([]);
    expect(reloaded.effective().labelDenyFilter).toEqual([]);
  });

  it('ignores a corrupt persisted draft labelDenyFilter rather than adopting it', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({ draft: { confidenceThreshold: 0.6, inferenceFps: 8, labelDenyFilter: 'tree' } }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.effective().labelDenyFilter).toEqual([]);
    expect(reloaded.effective().confidenceThreshold).toBe(0.6);
  });

  it('adjusting the model turns the active profile into a revertible custom draft', () => {
    store.selectProfile('low-latency');
    store.adjust({ model: 'orion12l.pt' });

    expect(store.isCustom()).toBe(true);
    expect(store.effective()).toEqual({
      confidenceThreshold: 0.5,
      inferenceFps: 3,
      model: 'orion12l.pt',
      labelFilter: [],
      labelDenyFilter: [],
      detectionEnabled: false,
    });
    // Confidence/fps stay exactly what the base preset had — only model moved.
    expect(store.activeProfile().id).toBe('low-latency');

    store.revertDraft();
    expect(store.isCustom()).toBe(false);
    expect(store.effective().model).toBe(DEFAULT_DETECTION_MODEL);
  });

  it('adjusting labelFilter/detectionEnabled turns the active profile into a revertible custom draft', () => {
    store.adjust({ labelFilter: ['person', 'car'], detectionEnabled: true });

    expect(store.isCustom()).toBe(true);
    expect(store.effective().labelFilter).toEqual(['person', 'car']);
    expect(store.effective().detectionEnabled).toBe(true);
    // Everything else stays exactly what Balanced had.
    expect(store.effective().model).toBe(DEFAULT_DETECTION_MODEL);
    expect(store.effective().confidenceThreshold).toBe(0.4);

    store.revertDraft();
    expect(store.effective().labelFilter).toEqual([]);
    // Balanced's own default, docs/plans/active/CV-DEMAND-PLAN.md wave D3.
    expect(store.effective().detectionEnabled).toBe(false);
  });

  it('saves a draft labelFilter/detectionEnabled choice as a reusable profile', () => {
    store.adjust({ labelFilter: ['person', 'building'], detectionEnabled: false });
    store.saveDraftAs('Buildings only');

    const saved = store.customProfiles();
    expect(saved).toHaveLength(1);
    expect(saved[0].labelFilter).toEqual(['person', 'building']);
    expect(saved[0].detectionEnabled).toBe(false);
    expect(store.effective().labelFilter).toEqual(['person', 'building']);
    expect(store.effective().detectionEnabled).toBe(false);
  });

  it('saves a draft model choice as a reusable profile, including the composite id', () => {
    store.adjust({ model: 'yolo11n.pt,orion12l.pt' });
    store.saveDraftAs('Both models');

    const saved = store.customProfiles();
    expect(saved).toHaveLength(1);
    expect(saved[0].model).toBe('yolo11n.pt,orion12l.pt');
    expect(store.isCustom()).toBe(false);
    expect(store.effective().model).toBe('yolo11n.pt,orion12l.pt');
  });

  it('persists a custom profile model choice across reload (round-trip)', () => {
    store.adjust({ model: 'orion12l.pt' });
    store.saveDraftAs('Military watch');
    TestBed.tick();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    const saved = reloaded.customProfiles();
    expect(saved).toHaveLength(1);
    expect(saved[0].model).toBe('orion12l.pt');
    expect(reloaded.activeProfile().model).toBe('orion12l.pt');
    expect(reloaded.effective().model).toBe('orion12l.pt');
  });

  it('accepts a persisted draft model id even one the current roster no longer recognizes (docs/plans/done/CV-CONTROL-PLAN.md Wave E — model is no longer a closed set)', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({ draft: { confidenceThreshold: 0.6, inferenceFps: 8, model: 'a-future-model.pt' } }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    // Not rejected — the picker degrades an unrecognized id to a bare-id display, it never
    // silently substitutes the default the way the old closed-union check used to.
    expect(reloaded.effective().model).toBe('a-future-model.pt');
    expect(reloaded.effective().confidenceThreshold).toBe(0.6);
  });

  it('ignores a non-string persisted draft model rather than adopting it', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({ draft: { confidenceThreshold: 0.6, inferenceFps: 8, model: 42 } }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.effective().model).toBe(DEFAULT_DETECTION_MODEL);
    // The rest of the corrupt-field draft still restores — one bad field doesn't sink it.
    expect(reloaded.effective().confidenceThreshold).toBe(0.6);
  });

  it('ignores a corrupt persisted draft labelFilter/detectionEnabled rather than adopting it', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({
        draft: { confidenceThreshold: 0.6, inferenceFps: 8, labelFilter: 'person', detectionEnabled: 'yes' },
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.effective().labelFilter).toEqual([]);
    // `'yes'` isn't a boolean, so this counts as "no decision was ever made" — backfills to the
    // app's current honest default (off, docs/plans/active/CV-DEMAND-PLAN.md wave D3), not the stale `true`
    // this used to fall back to.
    expect(reloaded.effective().detectionEnabled).toBe(false);
    expect(reloaded.effective().confidenceThreshold).toBe(0.6);
  });

  it('migrates a custom profile saved before the model picker existed (no model/labelFilter/detectionEnabled field at all)', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({
        activeProfileId: 'custom-old-preset',
        customProfiles: [
          {
            id: 'custom-old-preset',
            name: 'Old preset',
            description: 'Based on Balanced.',
            builtIn: false,
            confidenceThreshold: 0.6,
            inferenceFps: 8,
            // no `model`/`labelFilter`/`detectionEnabled` field — this is what every profile saved
            // before this task looked like.
          },
        ],
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.customProfiles()).toHaveLength(1);
    expect(reloaded.customProfiles()[0].model).toBe(DEFAULT_DETECTION_MODEL);
    expect(reloaded.customProfiles()[0].labelFilter).toEqual([]);
    // A field that was never there is "no decision was ever made" — backfills to the app's current
    // honest default (off, docs/plans/active/CV-DEMAND-PLAN.md wave D3), same reasoning as the corrupt-value
    // case right above. Contrast with the next two tests, where the field IS present.
    expect(reloaded.customProfiles()[0].detectionEnabled).toBe(false);
    expect(reloaded.activeProfile().id).toBe('custom-old-preset');
    expect(reloaded.effective().model).toBe(DEFAULT_DETECTION_MODEL);
    // Its own pre-existing fields are untouched by the migration.
    expect(reloaded.effective().confidenceThreshold).toBe(0.6);
    expect(reloaded.effective().inferenceFps).toBe(8);
  });

  it("preserves a returning user's own explicit detectionEnabled=true across this wave's default flip (docs/plans/active/CV-DEMAND-PLAN.md wave D3 — a real prior choice is never silently reverted)", () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({
        activeProfileId: 'custom-watching',
        customProfiles: [
          {
            id: 'custom-watching',
            name: 'Watching',
            description: 'Based on Balanced.',
            builtIn: false,
            confidenceThreshold: 0.4,
            inferenceFps: 5,
            model: DEFAULT_DETECTION_MODEL,
            labelFilter: [],
            detectionEnabled: true, // an explicit choice made before this wave shipped
          },
        ],
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.customProfiles()[0].detectionEnabled).toBe(true);
    expect(reloaded.effective().detectionEnabled).toBe(true);
  });

  it('preserves an explicit persisted draft detectionEnabled=true the same way (not just customProfiles)', () => {
    localStorage.setItem(
      'vision.settings.v1',
      JSON.stringify({
        draft: { confidenceThreshold: 0.6, inferenceFps: 8, detectionEnabled: true },
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const reloaded = TestBed.inject(SettingsStore);

    expect(reloaded.effective().detectionEnabled).toBe(true);
  });

  it('survives corrupt persisted settings', () => {
    localStorage.setItem('vision.settings.v1', '{not json');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    expect(() => TestBed.inject(SettingsStore)).not.toThrow();
  });
});
