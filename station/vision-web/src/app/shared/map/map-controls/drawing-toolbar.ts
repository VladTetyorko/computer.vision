import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DrawingsFacade } from '../../../core/map-data/drawings-facade';
import { LayersFacade } from '../../../core/map-data/layers-facade';
import { MarksFacade } from '../../../core/map-data/marks-facade';
import { DRAW_KINDS, drawKindLabel, remainingPoints } from '../../../core/map-data/drawings-logic';
import type { DrawKind } from '../../../core/api/models';

/**
 * `<vision-drawing-toolbar>` — the line / area / arrow / label modes and the selected drawing's
 * editor (docs/plans/done/MAP-REWORK-PLAN.md §5.2). Shared by the Fly cockpit's Map drawer and Command's
 * floating map card.
 *
 * **What it drives, and what it doesn't.** Arming a mode writes `DrawingsFacade.mode`, which the
 * host's facade folds into the map's single `[interactionMode]`
 * (`drawings-logic.ts#resolveInteractionMode`). The map itself owns the in-progress vertex list —
 * only it knows where a click landed — and emits `(drawingCompleted)` once the gesture finishes
 * (double-click / Enter, or the first click of a label). This toolbar therefore never sees a
 * half-drawn shape; it shows how many more clicks the current kind still needs
 * (`drawings-logic.ts#remainingPoints`) so the operator is never left guessing why double-clicking
 * did nothing.
 *
 * **Role gating mirrors the server, and hides rather than disables.** With no CONTRIBUTE layer at
 * all, the mode buttons are replaced by one honest sentence instead of four dead buttons — §5.2's
 * "UI hides actions when `myAccess < CONTRIBUTE`". Edit/Delete on the selected drawing follow
 * `DrawingsFacade.canEditSelected` for the same reason. Neither is a security boundary: the server
 * still arbitrates and its 403 surfaces as a toast.
 *
 * **Deviation from §5.2, flagged**: "select → drag vertices to edit" is not implemented — see
 * `DrawingsFacade`'s own class doc. The selected drawing's *details* (label, colour) are editable
 * here, and deleting + redrawing achieves a geometry change without restructuring
 * `<vision-tactical-map>`'s internals, which Wave E is explicitly scoped out of.
 */
@Component({
  selector: 'vision-drawing-toolbar',
  imports: [FormsModule],
  templateUrl: './drawing-toolbar.html',
  styleUrl: './drawing-toolbar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DrawingToolbar {
  /** `'card'` floats over a map (Command); `'stacked'` fills a drawer column (Fly). Layout only — same controls, same order. */
  readonly layout = input<'card' | 'stacked'>('stacked');

  protected readonly drawings = inject(DrawingsFacade);
  protected readonly layers = inject(LayersFacade);
  private readonly marks = inject(MarksFacade);

  constructor() {
    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3 — see `MarksPanel`'s identical constructor comment.
    this.drawings.activate();
    this.layers.activate();
    this.marks.activate();
    inject(DestroyRef).onDestroy(() => {
      this.drawings.release();
      this.layers.release();
      this.marks.release();
    });
  }

  protected readonly kinds = DRAW_KINDS;
  protected readonly drawKindLabel = drawKindLabel;

  protected readonly busy = signal(false);

  /** `true` once the layer list has settled with nothing writable — the modes give way to a sentence. */
  protected readonly noWritableLayer = computed(() => this.layers.loaded() && this.layers.contributable().length === 0);

  /** How many more clicks the armed kind needs before a completion gesture will do anything. */
  protected readonly clicksNeeded = computed(() => {
    const mode = this.drawings.mode();
    return mode === null ? 0 : remainingPoints(mode, 0);
  });

  /**
   * The selected drawing's label as an editable working copy — re-seeded only when the *selected
   * drawing's identity* changes (`DrawingsFacade.selectedDrawingId`, already a primitive id signal),
   * never on an unrelated data refresh of the same drawing. `DrawingsFacade.selected` hands out a
   * brand-new `MapDrawing` object on every SSE event and 30s safety-net poll (`drawings-store.ts`)
   * even when the selection hasn't moved — seeding straight off that object (as this used to) silently
   * discarded an in-progress label edit whenever a poll landed mid-edit. Mirrors `MarkPalette`'s
   * identical fix — "guard a `linkedSignal`'s re-seed on a derived primitive, never the enclosing
   * object" — see `vision-web/MODULE.md` Gotchas ("`linkedSignal` over an object input").
   */
  protected readonly editLabel = linkedSignal<string | undefined, string>({
    source: this.drawings.selectedDrawingId,
    computation: (id, previous) =>
      previous && previous.source === id ? previous.value : (this.drawings.selected()?.label ?? ''),
  });

  protected setMode(kind: DrawKind): void {
    // Both modes write the map's single `[interactionMode]`; arming a drawing disarms mark
    // placement so the toolbar and the palette can never both claim to be live at once.
    this.marks.disarm();
    this.drawings.setMode(kind);
  }

  protected stop(): void {
    this.drawings.stopDrawing();
  }

  protected setColor(token: string): void {
    this.drawings.setColorToken(token);
  }

  protected async recolorSelected(token: string): Promise<void> {
    const selected = this.drawings.selected();
    if (!selected) {
      return;
    }
    await this.run(() => this.drawings.setDetails(selected.drawingId, { colorToken: token }));
  }

  protected async saveLabel(): Promise<void> {
    const selected = this.drawings.selected();
    const label = this.editLabel().trim();
    if (!selected || label === (selected.label ?? '')) {
      return;
    }
    await this.run(() => this.drawings.setDetails(selected.drawingId, { label }));
  }

  protected async removeSelected(): Promise<void> {
    const selected = this.drawings.selected();
    if (!selected) {
      return;
    }
    await this.run(() => this.drawings.remove(selected.drawingId));
  }

  protected deselect(): void {
    this.drawings.deselect();
  }

  private async run(action: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    try {
      await action();
    } finally {
      this.busy.set(false);
    }
  }
}
