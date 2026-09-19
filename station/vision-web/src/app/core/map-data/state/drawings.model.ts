import type { EntityState } from '@ngrx/entity';
import { createEntityAdapter } from '@ngrx/entity';
import type { DrawKind, MapDrawingResponse } from '../../api/models';
import { DRAWING_COLOR_TOKENS } from '../drawings-logic';

/**
 * `@ngrx/entity` for `MapDrawingResponse` (docs/plans/done/NGRX-MIGRATION-PLAN.md §3 rule 4 —
 * mandatory). **No `sortComparer`** — same reasoning as `marks.model.ts`: the list is newest-first
 * by arrival (`map-event-logic.ts#upsertById` prepends), not by any sortable field. The reducer
 * re-derives the whole array through `applyDrawingEvents` and hands the result to `setAll`.
 */
export const drawingsAdapter = createEntityAdapter<MapDrawingResponse>({
  selectId: (drawing) => drawing.drawingId,
});

export interface DrawingsState extends EntityState<MapDrawingResponse> {
  readonly loaded: boolean;
  readonly activeConsumers: number;
  /** The colour the *next* drawing gets — recolouring an existing one goes through `Patch Requested`. */
  readonly colorToken: string;
  /** Which kind the next map clicks build, or `null` for "not drawing". */
  readonly mode: DrawKind | null;
  readonly selectedDrawingId: string | undefined;
  /** The last `ROUTER_NAVIGATED`'s path (no query/hash) — `null` until the first one. See
   *  `drawings.reducer.ts`'s own `routeChanged` doc comment for the BUG 3 fence this drives. */
  readonly lastRoutePath: string | null;
}

export const initialDrawingsState: DrawingsState = drawingsAdapter.getInitialState({
  loaded: false,
  activeConsumers: 0,
  colorToken: DRAWING_COLOR_TOKENS[0].token,
  mode: null,
  selectedDrawingId: undefined,
  lastRoutePath: null,
});

/** Safety-net poll cadence — see `DrawingsStore`'s own doc comment (ported verbatim). */
export const DRAWINGS_POLL_INTERVAL_MS = 30_000;
