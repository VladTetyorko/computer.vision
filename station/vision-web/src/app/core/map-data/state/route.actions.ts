import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { AssetRoute, RouteSpan } from '../route-logic';

/** The two commands `RouteFacade` issues — `'off'` is carried on `Show Requested` itself (rather
 *  than a separate action) so the reducer's own "clear unconditionally, before any fetch starts"
 *  rule (§3.4's frozen "never a moment it's wrong") stays one `on()` clause, not two. */
export const RoutePageActions = createActionGroup({
  source: 'Route Page',
  events: {
    'Show Requested': props<{ assetId: string; span: RouteSpan }>(),
    'Hide Requested': emptyProps(),
  },
});

/**
 * Every outcome of a non-`'off'` `Show Requested`. `Loaded` covers both a populated result *and*
 * the honest zero-usages case (`noUsages: true`, `routes: []`) — both are a successful fetch, never
 * an error; only a genuine `listUsages`/`usageTimeline` failure reaches `Load Failed`.
 */
export const RouteApiActions = createActionGroup({
  source: 'Route API',
  events: {
    Loaded: props<{ routes: readonly AssetRoute[]; noUsages: boolean }>(),
    'Load Failed': emptyProps(),
  },
});
