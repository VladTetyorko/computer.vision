import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { MarksStore } from '../../../../core/map-data/marks-store';
import { LayersStore } from '../../../../core/map-data/layers-store';
import {
  bearingDistance,
  bearingDistanceLabel,
  countUnverified,
  filterMarks,
  isUnverified,
  markPosition,
} from '../../../../core/map-data/mark-logic';
import { affiliationClass, affiliationLabel, markKindIcon, markKindLabel } from '../../tactical-map/tactical-map-logic';
import { MarkPalette } from '../mark-palette';
import { VerifyControls } from '../verify-controls';
import { Icon } from '../../../ui/icon';
import { Notice } from '../../../ui/notice';
import type { GeoPosition, MapMark } from '../../../../core/api/models';

/** The cockpit-only half of `MarksPanel` — mirrors `MapToolsCapabilities.cockpit`
 * (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.2) exactly, since this is that field's payload. */
export interface MarksPanelCockpit {
  readonly assetId: string;
  /** `undefined` renders "—", never a fabricated bearing/distance. */
  readonly dronePosition: GeoPosition | undefined;
}

/**
 * `<vision-marks-panel>` — the ONE marks section body, shared by every `<vision-map-tools>` host
 * (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.2, D10). Formerly two ~90%-identical copies,
 * `features/command/marks-panel.*` and `features/fly/marks-panel.*` (both deleted by this wave) —
 * this is the Fly copy, generalized: its two cockpit-only affordances ("Mark target" geolocate and
 * the drone bearing/distance readout) now render only when the host supplies {@link cockpit}.
 *
 * **No self-wrapping `<vision-side-panel>` anymore.** `<vision-map-tools>` owns the one drawer shell
 * for all four sections; this component is bare content, mirroring `LayerManager`'s own posture (its
 * `.manager` div, not a panel of its own).
 *
 * **Gating**: Confirm/Reject/Promote render only where the server resolved MANAGE
 * (`<vision-verify-controls>`'s own rule). Edit/Clear/Delete stay visible for every mark — the server
 * gates them by creator-or-manager *and* verification state (§3), finer than anything this panel can
 * know without guessing; `MarksStore` turns a 403 into a friendly toast, the honest arbiter.
 *
 * **Source/Position facts, now on every host** (a small, deliberate parity gain from the merge — see
 * `COMMAND-MAP-FLOW-PLAN.md` close-out): the selected mark's `dl.facts` block used to be Fly-only;
 * neither fact is drone-relative, so there was no reason to keep it cockpit-gated once there was one
 * component to hold it. Only the "From drone" row stays behind {@link cockpit}.
 *
 * A non-routed presentational child, so it injects its root stores directly
 * (`architecture.spec.ts`'s own carve-out, the same one both predecessor panels already relied on).
 */
@Component({
  selector: 'vision-marks-panel',
  imports: [Icon, Notice, MarkPalette, VerifyControls],
  templateUrl: './marks-panel.html',
  // The shared affiliation-swatch rules ride alongside this panel's own layout — see
  // `shared/map/map-controls/mark-symbol.css`'s header for why they are restated rather than shared
  // with the map's own `::ng-deep` rules.
  styleUrls: ['./marks-panel.css', '../mark-symbol.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarksPanel {
  /** Present only on the Fly cockpit's `marks` door — see {@link MarksPanelCockpit}. Absent
   * everywhere else: no drone to target, no bearing/distance to fabricate. */
  readonly cockpit = input<MarksPanelCockpit | undefined>(undefined);

  protected readonly store = inject(MarksStore);
  protected readonly layers = inject(LayersStore);

  constructor() {
    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: a non-routed presentational child that injects these
    // `providedIn: 'root'` stores directly must hold its own demand — see `ZonesPanel`'s identical
    // constructor for why (`/crew/:assetId`'s Map tools drawer has no host-facade activation at all).
    this.store.activate();
    this.layers.activate();
    inject(DestroyRef).onDestroy(() => {
      this.store.release();
      this.layers.release();
    });
  }

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

  /** The selected mark's bearing/distance from the drone, or `null` when there's nothing honest to show (no cockpit, or the drone has no fix). */
  protected readonly readout = computed(() => {
    const selected = this.store.selected();
    const drone = this.cockpit()?.dronePosition;
    if (!selected || !drone) {
      return null;
    }
    return bearingDistanceLabel(bearingDistance(drone, markPosition(selected)));
  });

  protected toggleUnverifiedOnly(): void {
    this.unverifiedOnly.update((on) => !on);
  }

  protected async markTarget(assetId: string): Promise<void> {
    this.geolocating.set(true);
    try {
      await this.store.geolocate(assetId);
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
