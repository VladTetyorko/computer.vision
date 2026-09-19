import { Injectable, computed, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import type { DrawKind, GeoPosition } from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { LayersFacade } from './layers-facade';
import type { DrawingDraft } from '../../shared/map/tactical-map/tactical-map-logic';
import { DRAWING_COLOR_TOKENS } from './drawings-logic';
import { DrawingsApiActions, DrawingsPageActions } from './state/drawings.actions';
import { drawingsFeature } from './state/drawings.reducer';

/**
 * `DrawingsStore`'s read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6).
 * `providedIn: 'root'`, exactly like the store it replaces — drawing *data* is shared app-wide;
 * only `mode` resets per genuine page change (`drawings.reducer.ts#routeChanged`).
 *
 * `canEditSelected`/`targetLayerId` inject `LayersFacade` directly (unlike `marks.effects.ts
 * #reconcilePalette$`, which reads `layersFeature` through its own selectors from an *effect*):
 * these two are plain derived **reads** with no state to write back, so composing them at the
 * facade layer — exactly how `DrawingsStore` itself called `this.layers.canContributeTo(...)` and
 * `this.layers.defaultLayerId()` — needs no action/effect machinery at all. The distinction is
 * "does this need to persist and be reconciled" (marks' palette does; these do not).
 */
@Injectable({ providedIn: 'root' })
export class DrawingsFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);
  private readonly layersFacade = inject(LayersFacade);

  readonly drawings = this.store.selectSignal(drawingsFeature.selectAllDrawings);
  /** `true` once the first load has settled (success or failure) — tells "loading" from "genuinely empty". */
  readonly loaded = this.store.selectSignal(drawingsFeature.selectLoaded);
  /** `<vision-tactical-map>`'s `[drawings]` — the display projection. */
  readonly displayDrawings = this.store.selectSignal(drawingsFeature.selectDisplayDrawings);
  /** The tokens the toolbar's colour picker offers — re-exported so a consumer needs one import, not two. */
  readonly colorTokens = DRAWING_COLOR_TOKENS;
  readonly colorToken = this.store.selectSignal(drawingsFeature.selectColorToken);
  /** Which kind the next map clicks build, or `null` for "not drawing". */
  readonly mode = this.store.selectSignal(drawingsFeature.selectMode);
  /** The `[interactionMode]` contribution of this facade alone — the host folds it with `MarksFacade.armed()`. */
  readonly interactionMode = this.store.selectSignal(drawingsFeature.selectInteractionMode);
  readonly selectedDrawingId = this.store.selectSignal(drawingsFeature.selectSelectedDrawingId);
  readonly selected = this.store.selectSignal(drawingsFeature.selectSelected);

  /** Whether the viewer may edit/delete the selected drawing at all — the server still arbitrates, this just hides dead controls. */
  readonly canEditSelected = computed(() => {
    const selected = this.selected();
    return selected !== undefined && this.layersFacade.canContributeTo(selected.layerId);
  });

  /** Which layer a new drawing lands on — the same default the mark palette uses; `undefined` lets the server pick. */
  readonly targetLayerId = this.layersFacade.defaultLayerId;

  /** Registers demand — see `LayersFacade.activate`'s identical doc comment for the full rationale. */
  activate(): void {
    this.store.dispatch(DrawingsPageActions.activated());
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(DrawingsPageActions.released());
  }

  // --- Mode + selection -------------------------------------------------------------------------

  /** Arms (or re-arms) a drawing kind; passing the kind already active turns drawing off, so one button toggles. */
  setMode(kind: DrawKind | null): void {
    this.store.dispatch(DrawingsPageActions.modeSet({ kind }));
  }

  stopDrawing(): void {
    this.store.dispatch(DrawingsPageActions.drawingStopped());
  }

  /** The toolbar's colour picker — applies to the *next* drawing; recolouring an existing one goes through {@link setDetails}. */
  setColorToken(token: string): void {
    this.store.dispatch(DrawingsPageActions.colorTokenSet({ token }));
  }

  select(id: string): void {
    this.store.dispatch(DrawingsPageActions.selected({ id }));
  }

  deselect(): void {
    this.store.dispatch(DrawingsPageActions.deselected());
  }

  // --- CRUD ---------------------------------------------------------------------------------------

  /**
   * `(drawingCompleted)`'s handler: creates the drawing on the current default layer with the
   * toolbar's colour, and selects it. A TEXT drawing needs a label (§2.1's own rule), so one is
   * seeded here — `DrawingsStore.completeDraft`'s own contract, ported verbatim.
   */
  async completeDraft(draft: DrawingDraft, options: { readonly label?: string; readonly colorToken?: string } = {}) {
    const label = options.label ?? (draft.kind === 'TEXT' ? DEFAULT_TEXT_LABEL : undefined);
    return dispatchAndAwait(
      this.store,
      this.actions$,
      DrawingsPageActions.completeDraftRequested({ draft, label, colorToken: options.colorToken }),
      DrawingsApiActions.completeDraftSucceeded,
      DrawingsApiActions.completeDraftFailed,
      (action) => action.drawing,
      () => null,
    );
  }

  /** Renames / recolours the selected drawing — an absent field is left alone server-side. */
  async setDetails(id: string, edit: { readonly label?: string; readonly colorToken?: string }): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      DrawingsPageActions.patchRequested({ id, edit }),
      DrawingsApiActions.patchSucceeded,
      DrawingsApiActions.patchFailed,
      () => true,
      () => false,
    );
  }

  /** Replaces a drawing's geometry wholesale — see `DrawingsStore`'s own doc comment on why there are no vertex handles yet. */
  async setGeometry(id: string, points: readonly GeoPosition[]): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      DrawingsPageActions.patchRequested({ id, edit: { points } }),
      DrawingsApiActions.patchSucceeded,
      DrawingsApiActions.patchFailed,
      () => true,
      () => false,
    );
  }

  async remove(id: string): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      DrawingsPageActions.removeRequested({ id }),
      DrawingsApiActions.removeSucceeded,
      DrawingsApiActions.removeFailed,
      () => undefined,
      () => undefined,
    );
  }
}

/** A TEXT drawing must carry a non-blank label (§2.1); this is the placeholder the toolbar puts straight into an editable field. */
const DEFAULT_TEXT_LABEL = 'Label';
