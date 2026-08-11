import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { DrawKind, GeoPosition, MapDrawingResponse } from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { LayersStore } from './layers-store';
import type { DrawingDraft } from '../../shared/map/tactical-map/tactical-map-logic';
import {
  DRAWING_COLOR_TOKENS,
  applyDrawingEvents,
  createDrawingRequest,
  interactionModeForDrawKind,
  toMapDrawings,
} from './drawings-logic';

/** Safety-net only — same reasoning and cadence as `MarksStore`/`LayersStore`; the `map` topic is the primary path. */
const DRAWINGS_POLL_INTERVAL_MS = 30_000;

/**
 * `DrawingsStore` — lines, areas, arrows and text labels on map layers (docs/plans/done/MAP-REWORK-PLAN.md
 * §2.1/§5.2), `providedIn: 'root'` alongside `LayersStore`/`MarksStore` for the identical reason:
 * every map host renders the same drawings and none should poll for them separately.
 *
 * <h2>Also owns the drawing *mode*, not just the data</h2>
 * `mode` (which `DrawKind` the next clicks build, or `null`) is UI-orchestration state that has to
 * coordinate two unrelated DOM subtrees — `<vision-tactical-map>` (which turns clicks into vertices)
 * and `<vision-drawing-toolbar>` (which arms them) — exactly the situation that put selection and
 * the palette on `MarksStore`. The host's own facade folds it together with `MarksStore.armed()` via
 * `drawings-logic.ts#resolveInteractionMode` into the single `[interactionMode]` the map takes.
 *
 * <h2>Completion, not vertex bookkeeping</h2>
 * The in-progress vertex list lives in the map component (it is drawn on the map, and only the map
 * knows where a click landed); this store only ever sees a *completed* `DrawingDraft` through
 * `(drawingCompleted)` and turns it into a `POST`. A degenerate shape never gets that far —
 * `tactical-map-logic.ts#completedDraft` refuses it first.
 *
 * <h2>Editing</h2>
 * §5.2 asks for select → PATCH geometry/label/colour → delete. Selection and the details PATCH are
 * here and driven by the toolbar; **vertex dragging is not implemented** — `<vision-tactical-map>`
 * renders drawings as plain Leaflet paths with no per-vertex handles, and adding them would mean
 * restructuring that component, which Wave E is explicitly scoped out of. {@link setGeometry} is the
 * ready seam for it: it is used today by "redraw" (draw a replacement shape for the selected
 * drawing), which delivers the same outcome without new map internals.
 */
@Injectable({ providedIn: 'root' })
export class DrawingsStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly live = inject(LiveStore);
  private readonly layers = inject(LayersStore);

  private readonly drawingsSignal = signal<readonly MapDrawingResponse[]>([]);
  /** Every drawing on a layer this viewer may see — already scoped server-side. */
  readonly drawings = this.drawingsSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  readonly loaded = this.loadedSignal.asReadonly();

  /** `<vision-tactical-map>`'s `[drawings]` — the display projection, computed once for every host. */
  readonly displayDrawings = computed(() => toMapDrawings(this.drawingsSignal()));

  /** The colour tokens the toolbar offers — re-exported so a consumer needs one import, not two. */
  readonly colorTokens = DRAWING_COLOR_TOKENS;

  /**
   * The colour the next drawing gets. Lives here rather than in the toolbar component because the
   * *completion* happens elsewhere: `(drawingCompleted)` fires on the map and is handled by the
   * host's facade, which must not have to reach into a sibling component to learn what colour the
   * operator picked. Defaults to `accent`, which is also `drawingColor`'s own fallback.
   */
  private readonly colorTokenSignal = signal<string>(DRAWING_COLOR_TOKENS[0].token);
  readonly colorToken = this.colorTokenSignal.asReadonly();

  // --- Mode + selection --------------------------------------------------------------------------
  private readonly modeSignal = signal<DrawKind | null>(null);
  /** Which kind the next map clicks build, or `null` for "not drawing". */
  readonly mode = this.modeSignal.asReadonly();

  /** The `[interactionMode]` contribution of this store alone — the host folds it with `MarksStore.armed()`. */
  readonly interactionMode = computed(() => interactionModeForDrawKind(this.modeSignal()));

  private readonly selectedDrawingIdSignal = signal<string | undefined>(undefined);
  readonly selectedDrawingId = this.selectedDrawingIdSignal.asReadonly();
  readonly selected = computed(() =>
    this.drawingsSignal().find((drawing) => drawing.drawingId === this.selectedDrawingIdSignal()),
  );

  /** Whether the viewer may edit/delete the selected drawing at all — the server still arbitrates, this just hides dead controls. */
  readonly canEditSelected = computed(() => {
    const selected = this.selected();
    return selected !== undefined && this.layers.canContributeTo(selected.layerId);
  });

  /** Which layer a new drawing lands on — the same default the mark palette uses; `undefined` lets the server pick. */
  readonly targetLayerId = computed(() => this.layers.defaultLayerId());

  private processedLiveEventCount = 0;

  constructor() {
    void this.refresh();
    inject(PollScheduler).schedule(DRAWINGS_POLL_INTERVAL_MS, () => this.refresh());

    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.drawingsSignal.update((drawings) => applyDrawingEvents(drawings, newEvents));
    });
  }

  async refresh(): Promise<void> {
    try {
      this.drawingsSignal.set(await this.api.listMapDrawings());
    } catch {
      // Silent-degrade, like every other background poller in this app.
    } finally {
      this.loadedSignal.set(true);
    }
  }

  // --- Mode + selection commands -----------------------------------------------------------------

  /** Arms (or re-arms) a drawing kind; passing the kind already active turns drawing off, so one button toggles. */
  setMode(kind: DrawKind | null): void {
    this.modeSignal.update((current) => (current === kind ? null : kind));
  }

  stopDrawing(): void {
    this.modeSignal.set(null);
  }

  /** The toolbar's colour picker — applies to the *next* drawing; recolouring an existing one goes through {@link setDetails}. */
  setColorToken(token: string): void {
    this.colorTokenSignal.set(token);
  }

  select(id: string): void {
    this.selectedDrawingIdSignal.set(this.selectedDrawingIdSignal() === id ? undefined : id);
  }

  deselect(): void {
    this.selectedDrawingIdSignal.set(undefined);
  }

  // --- CRUD ---------------------------------------------------------------------------------------

  /**
   * `(drawingCompleted)`'s handler: creates the drawing on the current default layer with the
   * toolbar's colour, and selects it. A TEXT drawing needs a label (§2.1's own rule), so one is
   * seeded here and immediately editable in the toolbar rather than blocking the gesture behind a
   * modal — the operator drew it, they should see it land.
   */
  async completeDraft(draft: DrawingDraft, options: { readonly label?: string; readonly colorToken?: string } = {}): Promise<MapDrawingResponse | null> {
    const label = options.label ?? (draft.kind === 'TEXT' ? DEFAULT_TEXT_LABEL : undefined);
    const colorToken = options.colorToken ?? this.colorTokenSignal();
    const created = await this.run(() =>
      this.api.createMapDrawing(createDrawingRequest(draft, { layerId: this.targetLayerId(), label, colorToken })),
    );
    if (created) {
      this.adopt(created);
      this.selectedDrawingIdSignal.set(created.drawingId);
    }
    return created;
  }

  /** Renames / recolours the selected drawing — an absent field is left alone server-side. */
  async setDetails(id: string, edit: { readonly label?: string; readonly colorToken?: string }): Promise<boolean> {
    const updated = await this.run(() => this.api.patchMapDrawing(id, edit));
    if (updated) {
      this.adopt(updated);
    }
    return updated !== null;
  }

  /** Replaces a drawing's geometry wholesale — see this class's own doc comment on why there are no vertex handles yet. */
  async setGeometry(id: string, points: readonly GeoPosition[]): Promise<boolean> {
    const updated = await this.run(() => this.api.patchMapDrawing(id, { points }));
    if (updated) {
      this.adopt(updated);
    }
    return updated !== null;
  }

  async remove(id: string): Promise<void> {
    const ok = await this.run(async () => {
      await this.api.deleteMapDrawing(id);
      return true;
    });
    if (!ok) {
      return;
    }
    this.drawingsSignal.update((drawings) => drawings.filter((drawing) => drawing.drawingId !== id));
    if (this.selectedDrawingIdSignal() === id) {
      this.selectedDrawingIdSignal.set(undefined);
    }
  }

  private adopt(drawing: MapDrawingResponse): void {
    this.drawingsSignal.update((drawings) =>
      applyDrawingEvents(drawings, [{ entity: 'drawing', action: 'updated', layerId: drawing.layerId, drawing }]),
    );
  }

  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}

/** A TEXT drawing must carry a non-blank label (§2.1); this is the placeholder the toolbar puts straight into an editable field. */
const DEFAULT_TEXT_LABEL = 'Label';
