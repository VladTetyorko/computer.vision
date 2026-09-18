import { createActionGroup, props } from '@ngrx/store';
import type { TelemetrySample } from '../../api/models';

/**
 * Every command a `TelemetryFacade` host issues. `assetId` is carried explicitly (optional — `LivePage`/
 * `WallTile` only ever have a bare `deviceId`, see `TelemetryFacade`'s own class doc) so the effects
 * (registered once, app-wide) key their per-device work off the action alone, never off which facade
 * instance happened to dispatch it.
 */
export const TelemetryPageActions = createActionGroup({
  source: 'Telemetry Page',
  events: {
    Tracked: props<{ deviceId: string; assetId: string | undefined }>(),
    Reset: props<{ deviceId: string }>(),
  },
});

/**
 * Every outcome of a `track()` session's async lookup chain. `Usage Not Found` is the honest degrade
 * when a device has no currently-open usage to poll/subscribe to — deliberately a modeled action
 * (NGRX-MIGRATION-PLAN.md §3 rule 7) rather than a silently-empty state a reducer never sees.
 */
export const TelemetryApiActions = createActionGroup({
  source: 'Telemetry API',
  events: {
    'Usage Not Found': props<{ deviceId: string }>(),
    'Backfill Received': props<{ deviceId: string; usageId: string; samples: readonly TelemetrySample[] }>(),
    'Poll Received': props<{ deviceId: string; samples: readonly TelemetrySample[] }>(),
    'Poll Failed': props<{ deviceId: string }>(),
  },
});
