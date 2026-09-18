import { describe, expect, it } from 'vitest';
import { DEFAULT_DECLUTTER_LEVEL } from '../../../shared/player/detection-overlay-logic';
import { SettingsPageActions } from './settings.actions';
import { initialSettingsState } from './settings.model';
import { settingsFeature } from './settings.reducer';

const reduce = settingsFeature.reducer;

describe('settings reducer', () => {
  it('starts with the documented defaults', () => {
    expect(initialSettingsState).toEqual({
      advancedMode: false,
      wallDensity: 3,
      mapLayer: 'night',
      eventNotifications: false,
      flyAssetId: null,
      declutterLevel: DEFAULT_DECLUTTER_LEVEL,
      cropFollowEnabled: false,
    });
  });

  it('each Set action updates only its own field', () => {
    const withAdvanced = reduce(initialSettingsState, SettingsPageActions.advancedModeSet({ advancedMode: true }));
    expect(withAdvanced.advancedMode).toBe(true);
    expect(withAdvanced.mapLayer).toBe('night');

    const withDensity = reduce(withAdvanced, SettingsPageActions.wallDensitySet({ wallDensity: 5 }));
    expect(withDensity.wallDensity).toBe(5);
    expect(withDensity.advancedMode).toBe(true); // untouched by the later action

    const withLayer = reduce(withDensity, SettingsPageActions.mapLayerSet({ mapLayer: 'satellite' }));
    expect(withLayer.mapLayer).toBe('satellite');

    const withNotifications = reduce(
      withLayer,
      SettingsPageActions.eventNotificationsSet({ eventNotifications: true }),
    );
    expect(withNotifications.eventNotifications).toBe(true);

    const withFlyAsset = reduce(withNotifications, SettingsPageActions.flyAssetIdSet({ flyAssetId: 'asset-42' }));
    expect(withFlyAsset.flyAssetId).toBe('asset-42');

    const withDeclutter = reduce(withFlyAsset, SettingsPageActions.declutterLevelSet({ declutterLevel: 'locked' }));
    expect(withDeclutter.declutterLevel).toBe('locked');

    const withCropFollow = reduce(
      withDeclutter,
      SettingsPageActions.cropFollowEnabledSet({ cropFollowEnabled: true }),
    );
    expect(withCropFollow.cropFollowEnabled).toBe(true);
    // Every earlier field survives the whole chain.
    expect(withCropFollow.advancedMode).toBe(true);
    expect(withCropFollow.wallDensity).toBe(5);
    expect(withCropFollow.mapLayer).toBe('satellite');
    expect(withCropFollow.eventNotifications).toBe(true);
    expect(withCropFollow.flyAssetId).toBe('asset-42');
    expect(withCropFollow.declutterLevel).toBe('locked');
  });

  it('flyAssetIdSet(null) clears a previously-set asset', () => {
    const set = reduce(initialSettingsState, SettingsPageActions.flyAssetIdSet({ flyAssetId: 'asset-1' }));
    const cleared = reduce(set, SettingsPageActions.flyAssetIdSet({ flyAssetId: null }));
    expect(cleared.flyAssetId).toBeNull();
  });
});
