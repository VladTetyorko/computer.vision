import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MarksStore } from '../../core/marks/marks-store';
import { MARK_KINDS, bearingDistance, bearingDistanceLabel, markColor, markKindIcon, markKindLabel } from '../../core/marks/mark-logic';
import { Icon } from '../../shared/ui/icon';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import type { GeoPosition, Mark, MarkKind } from '../../core/api/models';

/**
 * The Fly cockpit's "Marks" tool-rail drawer (docs/TACTICAL-MARKS-PLAN.md M5) — a non-routed
 * presentational child, so `architecture.spec.ts`'s facade rule doesn't apply here (its own carve-out:
 * "non-routed presentational child components… may still DI-share a host-provided store" — mirrors
 * `flight-command-panel.ts` injecting `VisionApi` directly). Injects `MarksStore` directly rather
 * than going through `FlyFacade`, since every action here (create/geolocate/annotate/clear/delete/
 * select/placement) is already a thin one-line forward to that store — there is no orchestration
 * `FlyFacade` would add, mirroring `zones-panel.ts` injecting `GeofenceStore` directly for the
 * identical reason.
 *
 * Three sections: **Mark target** (one-tap geolocate off the drone's own live telemetry — an honest
 * estimate, see the panel's own notice), **New mark** (arm a kind, then click the shared map inset;
 * `MarksStore.draft` holds the captured position awaiting this panel's label/note confirm step —
 * see `MarksStore`'s own class doc for why that coordination state lives on the store, not here or
 * `FlyFacade`), and the **active marks list** with a select/clear/delete row per mark and the
 * selected mark's bearing/distance readout (from the drone; no "from home" — see `FlyFacade
 * .dronePosition`'s own doc comment for why).
 *
 * Clear/Delete/Edit are shown for every mark, not hard-gated by "is this mine" — the backend gates
 * annotate/clear/delete alike to the mark's creator or a manager and 403s otherwise; `MarksStore`
 * already turns that into a friendly toast (`describeHttpError`'s own 403 branch). Docs/TACTICAL-
 * MARKS-PLAN.md's own Roles section: "surface a friendly message on 403 rather than a dead control;
 * don't hard-hide, but don't lie" — showing the control for everyone and letting the server be the
 * honest arbiter is exactly that, not a role check duplicated (and liable to drift from) the
 * backend's own gate.
 */
@Component({
  selector: 'vision-marks-panel',
  imports: [FormsModule, Icon, SidePanel, Notice],
  templateUrl: './marks-panel.html',
  styleUrl: './marks-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarksPanel {
  readonly assetId = input.required<string>();
  /** The drone's own current position, for the selected mark's bearing/distance readout — `undefined` renders "—", never a fabricated distance. */
  readonly dronePosition = input<GeoPosition | undefined>(undefined);
  /** Whether the `marks` drawer is open — driven by the host's tool-rail (`fly.ts`'s `panels`), mirroring `flight-command-panel.ts#open`. */
  readonly open = input<boolean>(false);
  readonly close = output<void>();

  protected readonly store = inject(MarksStore);

  protected readonly kinds = MARK_KINDS;
  protected readonly markKindLabel = markKindLabel;
  protected readonly markKindIcon = markKindIcon;
  protected readonly markColor = markColor;

  protected readonly geolocating = signal(false);

  protected readonly draftLabel = signal('');
  protected readonly draftNote = signal('');

  // --- Annotate (label/note/kind) an existing mark ------------------------------------------
  protected readonly editingMarkId = signal<string | null>(null);
  protected readonly editKind = signal<MarkKind>('TARGET');
  protected readonly editLabel = signal('');
  protected readonly editNote = signal('');

  /** The selected mark's bearing/distance from the drone, or `null` when there's nothing to show (no selection, or no known drone position). */
  protected readonly readout = computed(() => {
    const selected = this.store.selected();
    const drone = this.dronePosition();
    if (!selected || !drone) {
      return null;
    }
    return bearingDistanceLabel(bearingDistance(drone, selected.position));
  });

  protected async markTarget(): Promise<void> {
    this.geolocating.set(true);
    try {
      await this.store.geolocate(this.assetId());
    } finally {
      this.geolocating.set(false);
    }
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
