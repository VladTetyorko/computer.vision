import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MarksStore } from '../../core/marks/marks-store';
import { MARK_KINDS, markColor, markKindIcon, markKindLabel } from '../../core/marks/mark-logic';
import { Icon } from '../../shared/ui/icon';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import type { Mark, MarkKind } from '../../core/api/models';

/**
 * Command's "Marks" drawer (docs/TACTICAL-MARKS-PLAN.md M5) — the manager's own view onto the
 * shared tactical-marks operational picture: create-by-map-click and select/clear/delete, the same
 * store `<vision-fleet-map>` is wired to directly in `command.html`. A non-routed presentational
 * child (`architecture.spec.ts`'s own carve-out), injecting `MarksStore` directly rather than going
 * through `CommandFacade`, mirroring `zones-panel.ts` injecting `GeofenceStore` directly for the
 * identical reason: every action here is already a thin one-line forward to the store.
 *
 * **Simpler than Fly's `<vision-marks-panel>`**: no "Mark target" geolocate action and no
 * bearing/distance readout — both are cockpit concepts tied to *one* actively-flown drone, which
 * Command's map-of-the-whole-fleet view has no equivalent of. Structurally near-identical to Fly's
 * own marks panel otherwise (new-mark kind-picker + draft confirm, the active-marks list with
 * select/clear/delete) — kept as two separate files rather than one shared component because the
 * two hosts' surrounding context (an `assetId`/drone position vs. none) genuinely differs, following
 * this codebase's own "a second consumer moves it to `shared/`, not before" rule (there is no
 * literal reuse here, just family resemblance, same as `zones-panel.ts`/`asset-panel.ts` never
 * sharing a base class with anything).
 *
 * **`<vision-side-panel>`, not a backdrop modal** (unlike `<vision-zones-panel>`) — see
 * `command.ts#CommandOverlay`'s own doc comment for why: a modal backdrop would swallow every click
 * on the map underneath, breaking create-by-map-click.
 */
@Component({
  selector: 'vision-marks-panel',
  imports: [FormsModule, Icon, SidePanel, Notice],
  templateUrl: './marks-panel.html',
  styleUrl: './marks-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarksPanel {
  readonly closePanel = output<void>();

  protected readonly store = inject(MarksStore);

  protected readonly kinds = MARK_KINDS;
  protected readonly markKindLabel = markKindLabel;
  protected readonly markKindIcon = markKindIcon;
  protected readonly markColor = markColor;

  protected readonly draftLabel = signal('');
  protected readonly draftNote = signal('');

  // --- Annotate (label/note/kind) an existing mark ------------------------------------------
  protected readonly editingMarkId = signal<string | null>(null);
  protected readonly editKind = signal<MarkKind>('TARGET');
  protected readonly editLabel = signal('');
  protected readonly editNote = signal('');

  protected close(): void {
    this.closePanel.emit();
  }

  protected beginPlacement(kind: MarkKind): void {
    this.store.beginPlacement(kind);
  }

  protected cancelPlacement(): void {
    this.store.cancelPlacement();
  }

  protected cancelDraft(): void {
    this.draftLabel.set('');
    this.draftNote.set('');
    this.store.cancelDraft();
  }

  protected async confirmDraft(): Promise<void> {
    const label = this.draftLabel().trim();
    if (label.length === 0) {
      return;
    }
    const created = await this.store.confirmDraft(label, this.draftNote().trim() || undefined);
    if (created) {
      this.draftLabel.set('');
      this.draftNote.set('');
    }
  }

  protected selectMark(mark: Mark): void {
    this.editingMarkId.set(null); // switching marks abandons any in-progress edit on the old one
    this.store.select(mark.id);
  }

  protected startEdit(mark: Mark): void {
    this.editingMarkId.set(mark.id);
    this.editKind.set(mark.kind);
    this.editLabel.set(mark.label);
    this.editNote.set(mark.note ?? '');
  }

  protected cancelEdit(): void {
    this.editingMarkId.set(null);
  }

  protected async saveEdit(mark: Mark): Promise<void> {
    const label = this.editLabel().trim();
    if (label.length === 0) {
      return;
    }
    const ok = await this.store.annotate(mark.id, { kind: this.editKind(), label, note: this.editNote().trim() || undefined });
    if (ok) {
      this.editingMarkId.set(null);
    }
  }

  protected async clearMark(mark: Mark): Promise<void> {
    await this.store.clear(mark.id);
  }

  protected async deleteMark(mark: Mark): Promise<void> {
    await this.store.remove(mark.id);
  }
}
