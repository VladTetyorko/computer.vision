import { readPersistedFlag } from '../../panel-state';
import type { StateHydrator } from '../../state/hydration';
import {
  SIDEBAR_ADVANCED_OPEN_KEY,
  SIDEBAR_COLLAPSED_KEY,
  SIDEBAR_UPCOMING_OPEN_KEY,
  type SidebarState,
} from './sidebar.model';
import { sidebarFeature } from './sidebar.reducer';

/**
 * Only `preference` and the two disclosures are persisted. `fullBleed` belongs to the current route
 * and `override` to the current visit — restoring either across a reload would re-open the bug this
 * slice's precedence list exists to prevent.
 */
export const sidebarHydrator: StateHydrator<SidebarState> = {
  featureKey: sidebarFeature.name,
  read: () => ({
    preference: readPersistedFlag(SIDEBAR_COLLAPSED_KEY, false),
    advancedOpen: readPersistedFlag(SIDEBAR_ADVANCED_OPEN_KEY, false),
    upcomingOpen: readPersistedFlag(SIDEBAR_UPCOMING_OPEN_KEY, false),
  }),
};
