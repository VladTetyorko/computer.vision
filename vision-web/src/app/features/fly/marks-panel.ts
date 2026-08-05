import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MarksStore } from '../../core/map-data/marks-store';
import { LayersStore } from '../../core/map-data/layers-store';
import {
  bearingDistance,
  bearingDistanceLabel,
  countUnverified,
  filterMarks,
  isUnverified,
  markPosition,
} from '../../core/map-data/mark-logic';
import { affiliationClass, affiliationLabel, markKindIcon, markKindLabel } from '../../shared/map/tactical-map/tactical-map-logic';
import { MarkPalette } from '../../shared/map/map-controls/mark-palette';
import { VerifyControls } from '../../shared/map/map-controls/verify-controls';
import { Icon } from '../../shared/ui/icon';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import type { GeoPosition, MapMark } from '../../core/api/models';

/**
 * The Fly cockpit's "Marks" tool-rail drawer (docs/MAP-REWORK-PLAN.md §5.2, reworked from
 * docs/TACTICAL-MARKS-PLAN.md M5) — a non-routed presentational child, so `architecture.spec.ts`'s
 * facade rule doesn't apply (its own carve-out: "non-routed presentational child components… may
 * still DI-share a host-provided store"). Injects the two root map-data stores directly rather than
 * going through `CockpitFacade`, since every action here is a thin forward to them — mirroring
 * `zones-panel.ts` injecting `GeofenceStore` for the identical reason.
 *
 * **What Wave E changed.** The hand-rolled kind picker and label/note draft form are gone: both are
 * `<vision-mark-palette>` now, shared byte-for-byte with Command. New here: an **UNVERIFIED filter
 * chip** with a live count, a per-row **verify shortcut** (Confirm, for managers, without opening
 * the mark first), and the full `<vision-verify-controls>` strip on the selected mark. List rows
 * carry the map's own affiliation symbology, so a row looks like its pin.
 *
 * **Mark target** (one-tap geolocate off the drone's own live telemetry) stays, and now carries the
 * palette: the pin it drops is the kind/affiliation/layer the operator has selected, not a fixed
 * `TARGET`. It is still an estimate — the panel says so, and the pin stays draggable to correct.
 *
 * **Gating**: Confirm/Reject/Promote render only where the server resolved MANAGE
 * (`<vision-verify-controls>`'s own rule). Edit/Clear/Delete stay visible for every mark, unchanged
 * from M5 and deliberately: the server gates them by creator-or-manager *and* verification state
 * (§3), which is finer than anything this panel can know without guessing — `MarksStore` turns a
 * 403 into a friendly toast, which is the honest arbiter.
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
  readonly assetId = input.required<string>();
  /** The drone's own current position, for the selected mark's bearing/distance readout — `undefined` renders "—", never a fabricated distance. */
  readonly dronePosition = input<GeoPosition | undefined>(undefined);
  /** Whether the `marks` drawer is open — driven by the host's tool-rail (`cockpit.ts`'s `panels`). */
  readonly open = input<boolean>(false);
  readonly close = output<void>();

  protected readonly store = inject(MarksStore);
  protected readonly layers = inject(LayersStore);

  protected readonly markKindLabel = markKindLabel;
  protected readonly markKindIcon = markKindIcon;
  protected readonly affiliationLabel = affiliationLabel;
  protected readonly affiliationClass = affiliationClass;
  protected readonly isUnverified = isUnverified;

  protected readonly geolocating = signal(false);

  /** The UNVERIFIED view chip (§5.2) — a plain client-side filter over an already-scoped list, never a visibility rule. */
  protected readonly unverifiedOnly = signal(false);

  protected readonly unverifiedCount = computed(() => countUnverified(this.store.marks()));

  protected readonly visibleMarks = computed(() =>
    filterMarks(this.store.marks(), { unverifiedOnly: this.unverifiedOnly() }),
  );

  /** Which mark's inline editor is open — `<vision-mark-palette>` in edit mode. */
  protected readonly editingMarkId = signal<string | null>(null);

  /** The selected mark's bearing/distance from the drone, or `null` when there's nothing honest to show. */
  protected readonly readout = computed(() => {
    const selected = this.store.selected();
    const drone = this.dronePosition();
    if (!selected || !drone) {
      return null;
    }
    return bearingDistanceLabel(bearingDistance(drone, markPosition(selected)));
  });

  protected toggleUnverifiedOnly(): void {
    this.unverifiedOnly.update((on) => !on);
  }

  protected async markTarget(): Promise<void> {
    this.geolocating.set(true);
    try {
      await this.store.geolocate(this.assetId());
    } finally {
      this.geolocating.set(false);
    }
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
