import { createFeature, createReducer, on } from '@ngrx/store';
import { SettingsPageActions } from './settings.actions';
import { initialSettingsState } from './settings.model';

export const settingsFeature = createFeature({
  name: 'settings',
  reducer: createReducer(
    initialSettingsState,
    on(SettingsPageActions.advancedModeSet, (state, { advancedMode }) => ({ ...state, advancedMode })),
    on(SettingsPageActions.wallDensitySet, (state, { wallDensity }) => ({ ...state, wallDensity })),
    on(SettingsPageActions.mapLayerSet, (state, { mapLayer }) => ({ ...state, mapLayer })),
    on(SettingsPageActions.eventNotificationsSet, (state, { eventNotifications }) => ({
      ...state,
      eventNotifications,
    })),
    on(SettingsPageActions.flyAssetIdSet, (state, { flyAssetId }) => ({ ...state, flyAssetId })),
    on(SettingsPageActions.declutterLevelSet, (state, { declutterLevel }) => ({ ...state, declutterLevel })),
    on(SettingsPageActions.cropFollowEnabledSet, (state, { cropFollowEnabled }) => ({
      ...state,
      cropFollowEnabled,
    })),
  ),
});
