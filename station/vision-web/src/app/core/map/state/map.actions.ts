import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { AssetSummary, TelemetrySample } from '../../api/models';

/** Demand ref-counting, plus the one manual "refetch now" escape hatch `CommandFacade.addTestDrone`
 *  needs (`FleetMapStore.refresh()`'s own public contract — see `map-facade.ts#refresh`). Tracker
 *  lifecycle commands (`Trackers Reconciled`/`Tracker Removed`) are `Map Page` too, not `Map API`:
 *  nothing here is a server response, both are this app's own derived bookkeeping. */
export const MapPageActions = createActionGroup({
  source: 'Map Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
    'Refresh Requested': emptyProps(),
    'Trackers Reconciled': props<{ started: readonly string[]; stopped: readonly string[] }>(),
    'Tracker Removed': props<{ assetId: string }>(),
  },
});

/** Every REST outcome this slice ever produces — one per `FleetMapStore` fetch it replaces (the
 *  asset list, a tracker's backfill, a tracker's fallback poll). */
export const MapApiActions = createActionGroup({
  source: 'Map API',
  events: {
    'Assets Loaded': props<{ assets: readonly AssetSummary[] }>(),
    'Assets Load Failed': emptyProps(),
    'Tracker Backfill Loaded': props<{ assetId: string; samples: readonly TelemetrySample[]; usageId: string }>(),
    'Tracker Poll Loaded': props<{ assetId: string; samples: readonly TelemetrySample[] }>(),
  },
});
