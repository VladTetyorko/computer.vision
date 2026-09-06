import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { zoneKindLabel } from '../../core/geofence/geofence-logic';
import type { GeoPosition, GeofenceZone, ZoneKind } from '../../core/api/models';
import { GeofenceZoneDialog, type ZoneDraft } from './geofence-zone-dialog';

/**
 * The Zones section body (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.2/§3.2.1, superseding
 * `docs/plans/done/OPS-CORE-PLAN.md` §G-c) — the fourth, fixed-last section inside every host's
 * `<vision-map-tools>` drawer wherever `capabilities.zones` is on (Command, Fly's `map` door,
 * `/live/:deviceId`, `/assets/:id`).
 *
 * **No backdrop modal anymore.** §3.2.1's second refusal-reconsidered: the old full-screen modal
 * shell existed on the theory that zone management "needs to block accidental map clicks" — false of
 * the shipped code. This list's own actions (rename/enable/delete, "New … zone") never arm the
 * stage map's `[interactionMode]`; only `<vision-geofence-zone-dialog>` (unchanged, still its own
 * backdrop + its own Leaflet mini-map) actually draws a zone, and that dialog's modal already blocks
 * on its own. So this component is now bare drawer content, mirroring `LayerManager`'s/`MarksPanel`'s
 * identical "no self-wrapping shell, the host's `<vision-side-panel>` owns it" posture — `<vision-
 * map-tools>` mounts this directly, no `closePanel` output to wire (the drawer's own close handles
 * dismissal for every section at once).
 *
 * List: kind badge (`.chip.danger`/`.chip.accent` — matching the map layer's own KEEP_OUT-red/
 * KEEP_IN-accent color language), an enable/disable toggle, inline rename (click the name, mirrors
 * `features/devices/devices.ts`'s own "one inline row open at a time" `rowAction`/`renameDraft`
 * idiom, simplified to this panel's single action kind), and delete — **undoable** (10s, via the
 * shared `UndoToastService`, `GeofenceStore.remove` — see that store's own doc comment) rather than
 * a confirm dialog, the "undo over confirm" poka-yoke (docs/plans/done/UX-REWORK-PLAN.md §U-a2) already
 * established for archive/deactivate.
 *
 * "New keep-in/keep-out zone" opens `<vision-geofence-zone-dialog>` with `kind` fixed for that
 * dialog's lifetime; `assetPositions`/`centerHint` (derived by `<vision-map-tools>` from its own
 * `map` input, the host's `TacticalMap` instance) are threaded straight through so the KEEP_IN
 * save-time advisory needs no second lookup of its own.
 *
 * A non-routed presentational child, so it injects `GeofenceStore` directly
 * (`architecture.spec.ts`'s own carve-out). Physically still in `features/command/` (co-located with
 * `GeofenceZoneDialog`, which several unrelated files reference by this path in doc comments only —
 * moving it would ripple untouched files for no behavior change); `shared/map/map-controls/map-
 * tools.ts` imports it directly, an intentional exception to the usual features-depend-on-shared
 * direction, same reasoning `MODULE.md` records for this wave.
 */
@Component({
  selector: 'vision-zones-panel',
  imports: [FormsModule, GeofenceZoneDialog],
  templateUrl: './zones-panel.html',
  styleUrl: './zones-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ZonesPanel {
  protected readonly geofence = inject(GeofenceStore);

  /** Every asset's currently-known position — passed straight through to the draw dialog's own advisory. */
  readonly assetPositions = input<readonly GeoPosition[]>([]);
  readonly centerHint = input<GeoPosition | null>(null);

  protected readonly zoneKindLabel = zoneKindLabel;

  protected readonly drawKind = signal<ZoneKind | null>(null);
  protected readonly renamingZoneId = signal<string | null>(null);
  protected readonly renameDraft = signal('');

  protected readonly sortedZones = computed(() => [...this.geofence.zones()].sort((a, b) => a.name.localeCompare(b.name)));

  constructor() {
    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: this drawer section is a direct injector of the
    // `providedIn: 'root'` store, so it must hold its own demand rather than free-riding on whatever
    // host facade happened to activate it first — `/crew/:assetId`'s Map tools drawer mounts this
    // panel with no host-facade activation of `GeofenceStore` at all (`CrewFacade` injects none of
    // the five map-data stores), so skipping this would leave that route's zones section silently
    // dependent on some *other* page having been visited first in the same session.
    this.geofence.activate();
    inject(DestroyRef).onDestroy(() => this.geofence.release());
  }

  protected startDraw(kind: ZoneKind): void {
    this.drawKind.set(kind);
  }

  protected cancelDraw(): void {
    this.drawKind.set(null);
  }

  protected async saveDraw(draft: ZoneDraft): Promise<void> {
    const kind = this.drawKind();
    if (!kind) {
      return;
    }
    const created = await this.geofence.create({ name: draft.name, kind, polygon: draft.polygon, enabled: true });
    if (created) {
      this.drawKind.set(null);
    }
  }

  protected startRename(zone: GeofenceZone): void {
    this.renamingZoneId.set(zone.id);
    this.renameDraft.set(zone.name);
  }

  protected cancelRename(): void {
    this.renamingZoneId.set(null);
  }

  protected async confirmRename(zone: GeofenceZone): Promise<void> {
    const name = this.renameDraft().trim();
    if (name.length === 0) {
      return;
    }
    this.renamingZoneId.set(null);
    if (name !== zone.name) {
      await this.geofence.rename(zone, name);
    }
  }

  protected toggleEnabled(zone: GeofenceZone, checked: boolean): void {
    void this.geofence.setEnabled(zone, checked);
  }

  protected removeZone(zone: GeofenceZone): void {
    void this.geofence.remove(zone);
  }
}
