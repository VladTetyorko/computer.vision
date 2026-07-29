import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { zoneKindLabel } from '../../core/geofence/geofence-logic';
import type { GeoPosition, GeofenceZone, ZoneKind } from '../../core/api/models';
import { GeofenceZoneDialog, type ZoneDraft } from './geofence-zone-dialog';

/**
 * The Zones management panel (docs/OPS-CORE-PLAN.md §G-c) — a modal overlay opened from Command's
 * topbar "Zones" button (mirrors `shared/map/fleet-plan-dialog/flight-plan-dialog.ts`'s own
 * "modal, not embedded inline" choice, for the identical reason: doesn't touch
 * `command-logic.ts#commandGridColumns`'s own rail/map/panel grid arithmetic at all — a fourth
 * always-reserved track was rejected in favor of an on-demand overlay, since zone management is an
 * occasional admin task, not something browsed side-by-side with the map every session).
 *
 * List: kind badge (`.chip.danger`/`.chip.accent` — matching the map layer's own KEEP_OUT-red/
 * KEEP_IN-accent color language), an enable/disable toggle, inline rename (click the name, mirrors
 * `features/devices/devices.ts`'s own "one inline row open at a time" `rowAction`/`renameDraft`
 * idiom, simplified to this panel's single action kind), and delete — **undoable** (10s, via the
 * shared `UndoToastService`, `GeofenceStore.remove` — see that store's own doc comment) rather than
 * a confirm dialog, the "undo over confirm" poka-yoke (docs/UX-REWORK-PLAN.md §U-a2) already
 * established for archive/deactivate.
 *
 * "New keep-in/keep-out zone" opens `<vision-geofence-zone-dialog>` with `kind` fixed for that
 * dialog's lifetime; `assetPositions` (from `CommandPage`'s own `FleetMapStore.markers()`) is
 * threaded straight through so the KEEP_IN save-time advisory needs no second lookup of its own.
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

  readonly closePanel = output<void>();

  protected readonly zoneKindLabel = zoneKindLabel;

  protected readonly drawKind = signal<ZoneKind | null>(null);
  protected readonly renamingZoneId = signal<string | null>(null);
  protected readonly renameDraft = signal('');

  protected readonly sortedZones = computed(() => [...this.geofence.zones()].sort((a, b) => a.name.localeCompare(b.name)));

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

  protected close(): void {
    this.closePanel.emit();
  }
}
