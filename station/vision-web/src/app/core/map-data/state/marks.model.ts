import type { EntityState } from '@ngrx/entity';
import { createEntityAdapter } from '@ngrx/entity';
import type { MapMark } from '../../api/models';
import { DEFAULT_MARK_PALETTE, type MarkDraft, type MarkPalette } from '../mark-logic';

/**
 * `@ngrx/entity` for `MapMark` (docs/plans/active/NGRX-MIGRATION-PLAN.md §3 rule 4 — mandatory).
 *
 * **No `sortComparer`**: the list is newest-first by *arrival*, not by any field
 * (`map-event-logic.ts#upsertById`'s own doc comment: "prepends a genuinely new one"), which a
 * field-based comparator cannot express. The reducer re-derives the whole array through the
 * existing pure `applyMarkEvents` fold and hands the result to `setAll`, which keeps exactly that
 * order — same pattern as `tracks.model.ts`/`layers.model.ts`.
 */
export const marksAdapter = createEntityAdapter<MapMark>({
  selectId: (mark) => mark.markId,
});

export interface MarksState extends EntityState<MapMark> {
  /** `true` once the first load has settled (success or failure) — tells "loading" from "genuinely empty". */
  readonly loaded: boolean;
  /** Ref-count of live consumers — see `MarksFacade.activate`/`.release`. */
  readonly activeConsumers: number;
  readonly selectedMarkId: string | undefined;
  /** What the next mark will be: kind × affiliation × layer. Always present — arming is a separate flag. */
  readonly palette: MarkPalette;
  /** `true` while the next map click means "place a mark here". */
  readonly armed: boolean;
  /** The captured click, awaiting the label/confirm step in `<vision-mark-palette>`. */
  readonly draft: MarkDraft | null;
  /** The last `ROUTER_NAVIGATED`'s path (no query/hash) — `null` until the first one. See
   *  `marks.reducer.ts`'s own `routeChanged` doc comment for the BUG 3 fence this drives. */
  readonly lastRoutePath: string | null;
}

export const initialMarksState: MarksState = marksAdapter.getInitialState({
  loaded: false,
  activeConsumers: 0,
  selectedMarkId: undefined,
  palette: DEFAULT_MARK_PALETTE,
  armed: false,
  draft: null,
  lastRoutePath: null,
});

/** Safety-net poll cadence — see `MarksStore`'s own doc comment (ported verbatim). */
export const MARKS_POLL_INTERVAL_MS = 30_000;
