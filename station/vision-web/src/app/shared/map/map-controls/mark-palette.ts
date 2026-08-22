import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MarksStore } from '../../../core/map-data/marks-store';
import { LayersStore } from '../../../core/map-data/layers-store';
import { DrawingsStore } from '../../../core/map-data/drawings-store';
import { DEFAULT_MARK_PALETTE, paletteFromMark, type MarkPalette as Palette } from '../../../core/map-data/mark-logic';
import {
  AFFILIATIONS,
  TACTICAL_MARK_KINDS,
  affiliationClass,
  affiliationLabel,
  markKindIcon,
  markKindLabel,
} from '../tactical-map/tactical-map-logic';
import { Icon } from '../../ui/icon';
import { Notice } from '../../ui/notice';
import type { Affiliation, MapMark, MarkKind } from '../../../core/api/models';

/**
 * `<vision-mark-palette>` — what the next mark will be, and where it lands
 * (docs/plans/done/MAP-REWORK-PLAN.md §5.2). Shared verbatim by the Fly cockpit's Marks drawer and Command's
 * Marks panel, which is why it lives in `shared/map/map-controls/` rather than either feature
 * folder (this codebase's own "a second consumer moves it to `shared/`" rule).
 *
 * **Two modes, one component**, chosen by whether `[mark]` is bound:
 * - **create** (no `[mark]`): reads and writes `MarksStore.palette` — the *shared* selection, so the
 *   cockpit's "Mark target" geolocate drops a pin of exactly the kind/affiliation/layer the operator
 *   has picked here, with no second control to keep in sync. Arm → the next map click captures a
 *   draft → label/note → create.
 * - **edit** (`[mark]` bound): a local working copy seeded from that mark, saved with a PATCH.
 *   `(done)` tells the host to close its editor row; it fires on save *and* cancel.
 *
 * **The "kind × affiliation grid" is two axes, not a 20-cell matrix.** §5.2 asks for a grid; drawn
 * literally that is 5 kinds × 4 affiliations = 20 buttons, which at drawer width is unreadable and
 * reads as slop (frontend-style §9: "if a screen looks designed, remove one thing"). Two labelled
 * rows — *what it is* then *whose it is* — express the same two axes, cost two taps instead of one,
 * and stay legible on a phone. Affiliation buttons carry the map's own frame swatch, so the picker
 * and the pin can never disagree about what HOSTILE looks like.
 *
 * **Layer picker gating**: options are `LayersStore.contributable()` only — a layer the viewer may
 * merely VIEW never appears, mirroring the server's own CONTRIBUTE rule (§3). When the viewer has no
 * contributable layer at all, the picker is replaced by an honest line saying the mark will land on
 * their default layer: the request then simply omits `layerId` and the server decides, rather than
 * this UI blocking a legal action it merely can't name yet.
 *
 * A non-routed presentational child, so `architecture.spec.ts`'s facade rule doesn't apply — it
 * injects the three root map-data stores directly, exactly as `features/command/zones-panel.ts`
 * injects `GeofenceStore`.
 */
@Component({
  selector: 'vision-mark-palette',
  imports: [FormsModule, Icon, Notice],
  templateUrl: './mark-palette.html',
  // Two files: this component's own layout, plus the shared affiliation-swatch rules every
  // non-map mark surface pulls in (see `mark-symbol.css`'s own header for why it is restated).
  styleUrls: ['./mark-palette.css', './mark-symbol.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarkPalette {
  /** Bound → edit that mark; unbound → create a new one off the shared palette. */
  readonly mark = input<MapMark | undefined>(undefined);

  /** Fired after a successful save or an explicit cancel — the host closes its editor row. */
  readonly done = output<void>();

  protected readonly marks = inject(MarksStore);
  protected readonly layers = inject(LayersStore);
  private readonly drawings = inject(DrawingsStore);

  protected readonly kinds = TACTICAL_MARK_KINDS;
  protected readonly affiliations = AFFILIATIONS;
  protected readonly markKindLabel = markKindLabel;
  protected readonly markKindIcon = markKindIcon;
  protected readonly affiliationLabel = affiliationLabel;
  protected readonly affiliationClass = affiliationClass;

  protected readonly editing = computed(() => this.mark() !== undefined);

  /**
   * The edit-mode working copies below re-seed on the mark's *identity* (`markId`), never on the mark
   * object itself. `MarksStore` replaces its whole list with brand-new `MapMark` objects on every SSE
   * event and on its 30s safety-net poll (`marks-store.ts`) — if these `linkedSignal`s tracked `mark()`
   * directly (as they used to), an operator's in-progress edit was silently discarded every time that
   * poll landed mid-edit, even though the mark itself hadn't changed. See `vision-web/MODULE.md`
   * Gotchas ("`linkedSignal` over an object input") — "guard a `linkedSignal`'s re-seed on a derived
   * primitive, never the enclosing object", the same fix `DrawingToolbar#editLabel` mirrors.
   */
  private readonly editingMarkId = computed(() => this.mark()?.markId);

  /** Reuses `previous.value` unless `editingMarkId()` has actually moved on — see `editingMarkId`'s doc comment. */
  private reseed<T>(id: string | undefined, previous: { source: string | undefined; value: T } | undefined, computeFresh: () => T): T {
    return previous && previous.source === id ? previous.value : computeFresh();
  }

  /** The edit mode's working copy — re-seeded only when `editingMarkId()` changes, not on every poll refresh. */
  private readonly editPalette = linkedSignal<string | undefined, Palette>({
    source: this.editingMarkId,
    computation: (id, previous) =>
      this.reseed(id, previous, () => {
        const mark = this.mark();
        return mark ? paletteFromMark(mark) : DEFAULT_MARK_PALETTE;
      }),
  });

  private readonly editLabel = linkedSignal<string | undefined, string>({
    source: this.editingMarkId,
    computation: (id, previous) => this.reseed(id, previous, () => this.mark()?.label ?? ''),
  });

  private readonly editNote = linkedSignal<string | undefined, string>({
    source: this.editingMarkId,
    computation: (id, previous) => this.reseed(id, previous, () => this.mark()?.note ?? ''),
  });

  /** The create flow's own label/note fields — only meaningful once a draft has been captured. */
  protected readonly draftLabel = signal('');
  protected readonly draftNote = signal('');

  protected readonly busy = signal(false);

  /** Whichever palette this instance is currently editing — the template binds one set of controls to both modes. */
  protected readonly current = computed<Palette>(() => (this.editing() ? this.editPalette() : this.marks.palette()));

  protected readonly labelText = computed(() => (this.editing() ? this.editLabel() : this.draftLabel()));
  protected readonly noteText = computed(() => (this.editing() ? this.editNote() : this.draftNote()));

  /** `true` once the layer list has settled and holds nothing this viewer may write to — the picker becomes an honest sentence. */
  protected readonly noWritableLayer = computed(() => this.layers.loaded() && this.layers.contributable().length === 0);

  protected readonly canSave = computed(() => this.labelText().trim().length > 0 && !this.busy());

  // --- Palette axes -------------------------------------------------------------------------------

  protected setKind(kind: MarkKind): void {
    if (this.editing()) {
      this.editPalette.update((palette) => ({ ...palette, kind }));
      return;
    }
    this.marks.setKind(kind);
  }

  protected setAffiliation(affiliation: Affiliation): void {
    if (this.editing()) {
      this.editPalette.update((palette) => ({ ...palette, affiliation }));
      return;
    }
    this.marks.setAffiliation(affiliation);
  }

  /** The `<select>`'s empty option is `''` — "let the server pick my default layer", not a layer named "". */
  protected setLayer(layerId: string): void {
    const value = layerId === '' ? undefined : layerId;
    if (this.editing()) {
      this.editPalette.update((palette) => ({ ...palette, layerId: value }));
      return;
    }
    this.marks.setLayer(value);
  }

  protected setLabel(value: string): void {
    (this.editing() ? this.editLabel : this.draftLabel).set(value);
  }

  protected setNote(value: string): void {
    (this.editing() ? this.editNote : this.draftNote).set(value);
  }

  // --- Create flow --------------------------------------------------------------------------------

  /**
   * Arms the next map click. Also stops any drawing in progress: both write the map's single
   * `[interactionMode]`, and an operator who reaches for "Place mark" mid-polygon means the mark —
   * `drawings-logic.ts#resolveInteractionMode` would resolve it that way anyway, so disarming here
   * keeps the toolbar's own buttons honest about which mode is actually live.
   */
  protected arm(): void {
    this.drawings.stopDrawing();
    this.marks.arm();
  }

  protected cancelArm(): void {
    this.marks.disarm();
  }

  protected cancelDraft(): void {
    this.draftLabel.set('');
    this.draftNote.set('');
    this.marks.cancelDraft();
  }

  protected cancelEdit(): void {
    this.done.emit();
  }

  protected async save(): Promise<void> {
    const label = this.labelText().trim();
    if (label.length === 0) {
      return;
    }
    const note = this.noteText().trim() || undefined;
    this.busy.set(true);
    try {
      const mark = this.mark();
      if (mark) {
        if (await this.marks.annotate(mark.markId, this.editPalette(), label, note)) {
          this.done.emit();
        }
        return;
      }
      if (await this.marks.confirmDraft(label, note)) {
        this.draftLabel.set('');
        this.draftNote.set('');
        this.done.emit();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
