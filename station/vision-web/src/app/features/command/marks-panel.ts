import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { MarksStore } from '../../core/map-data/marks-store';
import { LayersStore } from '../../core/map-data/layers-store';
import { countUnverified, filterMarks, isUnverified } from '../../core/map-data/mark-logic';
import { affiliationClass, affiliationLabel, markKindIcon, markKindLabel } from '../../shared/map/tactical-map/tactical-map-logic';
import { MarkPalette } from '../../shared/map/map-controls/mark-palette';
import { VerifyControls } from '../../shared/map/map-controls/verify-controls';
import { Icon } from '../../shared/ui/icon';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import type { MapMark } from '../../core/api/models';

/**
 * Command's "Marks" drawer (docs/plans/done/MAP-REWORK-PLAN.md §5.2, reworked from
 * docs/plans/done/TACTICAL-MARKS-PLAN.md M5) — the manager's own view onto the Common Operational Picture:
 * create-by-map-click through the shared `<vision-mark-palette>`, an UNVERIFIED filter chip, a
 * per-row Confirm shortcut, and the full `<vision-verify-controls>` strip (Confirm / Reject /
 * **Promote to common picture**) on the selected mark. A non-routed presentational child
 * (`architecture.spec.ts`'s own carve-out), injecting the two root map-data stores directly rather
 * than going through `CommandFacade`, mirroring `zones-panel.ts` injecting `GeofenceStore`.
 *
 * **Simpler than Fly's `<vision-marks-panel>`**: no "Mark target" geolocate and no bearing/distance
 * readout — both are cockpit concepts tied to *one* actively-flown drone, which a map-of-the-whole-
 * fleet view has no equivalent of. Everything else is the same shared components, so the two panels
 * stay two thin templates over one behaviour rather than two divergent implementations.
 *
 * **`<vision-side-panel>`, not a backdrop modal** (unlike `<vision-zones-panel>`) — see
 * `command.ts#CommandOverlay`'s own doc comment: a modal backdrop would swallow every click on the
 * map underneath, breaking create-by-map-click.
 */
@Component({
  selector: 'vision-marks-panel',
  imports: [Icon, SidePanel, Notice, MarkPalette, VerifyControls],
  templateUrl: './marks-panel.html',
  // The shared affiliation-swatch rules ride alongside this panel's own layout — see
  // `shared/map/map-controls/mark-symbol.css`'s header for why they are restated rather than shared
  // with the map's own `::ng-deep` rules.
  styleUrls: ['./marks-panel.css', '../../shared/map/map-controls/mark-symbol.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarksPanel {
  readonly closePanel = output<void>();

  protected readonly store = inject(MarksStore);
  protected readonly layers = inject(LayersStore);

  protected readonly markKindLabel = markKindLabel;
  protected readonly markKindIcon = markKindIcon;
  protected readonly affiliationLabel = affiliationLabel;
  protected readonly affiliationClass = affiliationClass;
  protected readonly isUnverified = isUnverified;

  /** The UNVERIFIED view chip (§5.2) — a client-side filter over an already-scoped list, never a visibility rule. */
  protected readonly unverifiedOnly = signal(false);

  protected readonly unverifiedCount = computed(() => countUnverified(this.store.marks()));

  protected readonly visibleMarks = computed(() =>
    filterMarks(this.store.marks(), { unverifiedOnly: this.unverifiedOnly() }),
  );

  protected readonly editingMarkId = signal<string | null>(null);

  protected close(): void {
    this.closePanel.emit();
  }

  protected toggleUnverifiedOnly(): void {
    this.unverifiedOnly.update((on) => !on);
  }

  protected selectMark(mark: MapMark): void {
    this.editingMarkId.set(null); // switching marks abandons any in-progress edit on the old one
    this.store.select(mark.markId);
  }

  protected startEdit(mark: MapMark): void {
    this.editingMarkId.set(mark.markId);
  }

  protected endEdit(): void {
    this.editingMarkId.set(null);
  }

  /** The list's per-row verify shortcut — only rendered where the server resolved MANAGE on that mark's layer. */
  protected canVerify(mark: MapMark): boolean {
    return this.layers.canManageLayer(mark.layerId);
  }

  protected async confirmMark(mark: MapMark): Promise<void> {
    await this.store.verify(mark.markId, 'CONFIRMED');
  }

  protected async clearMark(mark: MapMark): Promise<void> {
    await this.store.clear(mark.markId);
  }

  protected async deleteMark(mark: MapMark): Promise<void> {
    await this.store.remove(mark.markId);
  }
}
