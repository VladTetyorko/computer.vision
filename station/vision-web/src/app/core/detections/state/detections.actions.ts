import { createActionGroup, props } from '@ngrx/store';
import type { DetectionResult, StreamTracksResponse } from '../../api/models';
import type { AssetScopedTransport } from '../../live/live-fallback-logic';

/**
 * Every command a `DetectionsFacade` host issues, across both lifecycles this slice holds (see
 * `detections.model.ts`'s own class doc). `Tracks Tracked` carries `assetId` explicitly — the facade
 * supplies it from its own feed bookkeeping (`DetectionsFacade`'s own `currentAssetId`, itself
 * derived from whichever entry `track()` last wrote), since a `Record` reducer has no way to look
 * "sideways" at another key the way the facade's host-local state can.
 */
export const DetectionsPageActions = createActionGroup({
  source: 'Detections Page',
  events: {
    Tracked: props<{ streamId: string; assetId: string | undefined }>(),
    Reset: props<{ streamId: string }>(),
    'Tracks Tracked': props<{ streamId: string; assetId: string | undefined }>(),
    'Tracks Untracked': props<{ streamId: string }>(),
  },
});

/**
 * Every outcome, from both lifecycles' async work. `Transport Entered` is dispatched once per
 * distinct feed transport decision (including the very first one) — its reducer case is the "seed
 * the other signal from whatever was last visible" bridge `DetectionsStore#applyTransport` used to
 * do imperatively, so a transport flip never blanks the chip strip for a beat (see that reducer
 * case's own doc comment). A tracks poll failure is deliberately **not** silent-degrade-keep-last —
 * see `detections.model.ts#DetectionsSessionState.tracksPollResponse`'s own doc comment for why that
 * field's honesty posture differs from `pollResults`'.
 */
export const DetectionsApiActions = createActionGroup({
  source: 'Detections API',
  events: {
    'Transport Entered': props<{ streamId: string; transport: AssetScopedTransport }>(),
    'Poll Received': props<{ streamId: string; results: readonly DetectionResult[] }>(),
    'Poll Failed': props<{ streamId: string }>(),
    'Live Result Received': props<{ streamId: string; result: DetectionResult }>(),
    'Tracks Poll Received': props<{ streamId: string; response: StreamTracksResponse }>(),
    'Tracks Poll Failed': props<{ streamId: string }>(),
  },
});
