import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { BatteryThresholds, RcThresholds } from '../../api/models';

/** `ThresholdsFacade`'s constructor dispatches this once (mirrors the old `ThresholdsStore`'s own
 *  `constructor() { void this.refresh(); }`) — {@link ThresholdsFacade.refresh} can also fire it again. */
export const ThresholdsPageActions = createActionGroup({
  source: 'Thresholds Page',
  events: { 'Refresh Requested': emptyProps() },
});

/** `Refresh Succeeded` always carries a real `rc` — the effect degrades a served response that
 *  simply omits it (BK1 landing in parallel, see `thresholds.effects.ts`) before dispatching. */
export const ThresholdsApiActions = createActionGroup({
  source: 'Thresholds API',
  events: {
    'Refresh Succeeded': props<{ battery: BatteryThresholds; rc: RcThresholds }>(),
    'Refresh Failed': props<{ error: string }>(),
  },
});
