import { createActionGroup, props } from '@ngrx/store';
import type { BoxesMode } from '../../../shared/player/player';
import type { MapLayerId } from './settings.model';

/**
 * Every settings field is set directly from wherever an operator makes the choice — the account
 * settings page, a map's layer switcher, the Fly asset picker, a follow-HUD toggle — there is no
 * single "Settings Page" host, but there is exactly one *kind* of thing happening (a person setting
 * one client-only preference), so one source covers it (docs/plans/active/NGRX-MIGRATION-PLAN.md §3
 * rule 1).
 */
export const SettingsPageActions = createActionGroup({
  source: 'Settings Page',
  events: {
    'Advanced Mode Set': props<{ advancedMode: boolean }>(),
    'Wall Density Set': props<{ wallDensity: number }>(),
    'Map Layer Set': props<{ mapLayer: MapLayerId }>(),
    'Event Notifications Set': props<{ eventNotifications: boolean }>(),
    'Fly Asset Id Set': props<{ flyAssetId: string | null }>(),
    'Declutter Level Set': props<{ declutterLevel: BoxesMode }>(),
    'Crop Follow Enabled Set': props<{ cropFollowEnabled: boolean }>(),
  },
});
