import { isBoxesMode } from '../../../shared/player/detection-overlay-logic';
import type { StateHydrator } from '../../state/hydration';
import { SETTINGS_STORAGE_KEY, isMapLayerId, type SettingsState } from './settings.model';

/**
 * Restores whichever fields of the one JSON blob are individually well-formed — a stale field from
 * before this wave, or a hand-edited value, degrades that one field to the reducer's own default
 * rather than discarding the whole blob (this is what the old `SettingsStore#restore()` did field by
 * field, and it matters: a corrupt `declutterLevel` must not also cost a legitimately-set `mapLayer`).
 * A missing key or an unparseable blob returns `undefined` outright, leaving every default standing.
 */
export const settingsHydrator: StateHydrator<SettingsState> = {
  featureKey: 'settings',
  read: () => {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) {
      return undefined;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<Record<keyof SettingsState, unknown>>;
      const restored: { -readonly [K in keyof SettingsState]?: SettingsState[K] } = {};
      if (typeof parsed.advancedMode === 'boolean') {
        restored.advancedMode = parsed.advancedMode;
      }
      if (typeof parsed.wallDensity === 'number') {
        restored.wallDensity = parsed.wallDensity;
      }
      if (isMapLayerId(parsed.mapLayer)) {
        restored.mapLayer = parsed.mapLayer;
      }
      if (typeof parsed.eventNotifications === 'boolean') {
        restored.eventNotifications = parsed.eventNotifications;
      }
      if (typeof parsed.flyAssetId === 'string') {
        restored.flyAssetId = parsed.flyAssetId;
      }
      if (isBoxesMode(parsed.declutterLevel)) {
        restored.declutterLevel = parsed.declutterLevel;
      }
      if (typeof parsed.cropFollowEnabled === 'boolean') {
        restored.cropFollowEnabled = parsed.cropFollowEnabled;
      }
      return restored;
    } catch {
      // Corrupt or stale settings must never keep the app from starting; the next persist() call
      // overwrites the bad blob, so there is no need to clear it here (hydrators only read).
      return undefined;
    }
  },
};
