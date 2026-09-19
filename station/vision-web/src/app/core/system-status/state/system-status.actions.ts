import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { SystemStatus } from '../../api/models';

/**
 * The one explicit user intent this slice models — `SystemStatusStore.refresh()`'s own manual-call
 * shape (`features/system-status/system-status-facade.ts`'s constructor + its own `refresh()`
 * passthrough). Every *automatic* refresh (poll tick, live→poll fallback reconcile) goes straight
 * through `system-status.effects.ts`'s own shared `refreshStatus$` helper instead — see that file's
 * `gate$` doc comment for why replicating a literal boot-time dispatch would double the first `GET`.
 */
export const SystemStatusPageActions = createActionGroup({
  source: 'System Status Page',
  events: {
    'Refresh Requested': emptyProps(),
  },
});

/**
 * `SystemStatusStore.refresh()`'s own two outcomes. Never toasted — this store has never had a
 * toast, poll or manual (see the old class's own `refresh()` doc comment) — so `error` exists only
 * to be *read*, not to drive a side effect.
 */
export const SystemStatusApiActions = createActionGroup({
  source: 'System Status API',
  events: {
    'Refresh Succeeded': props<{ status: SystemStatus }>(),
    'Refresh Failed': props<{ error: string }>(),
  },
});
