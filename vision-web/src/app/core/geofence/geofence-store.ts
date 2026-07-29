import { Injectable, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { GeoPosition, GeofenceZone, GeofenceZoneRequest, ZoneKind } from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { PollScheduler } from '../poll-scheduler';

/**
 * Zones are near-static reference data, not a hot poll target — refreshed on mount, on every
 * mutation this store itself makes, and lightly in the background so a second manager's edit (or
 * this same operator's own Command tab open twice) eventually shows up on `/fly`'s read-only layer
 * too, without needing FleetStore's 5s cadence.
 */
const ZONES_POLL_INTERVAL_MS = 30_000;

/**
 * `GeofenceStore` — the app's one shared source of truth for geofence zones
 * (docs/OPS-CORE-PLAN.md §G-c), `providedIn: 'root'` and started at boot like `FleetStore`: zones
 * back both Command's Zones panel/map layer and Fly's read-only map layer, and both pages should
 * see the same list without each standing up its own poller.
 *
 * **CRUD, not truly optimistic** (mirrors `FleetStore`'s own warehouse-mutation precedent: await
 * the API call, then adopt its response — never assume a write succeeded before the network says
 * so): `rename`/`setEnabled` resend the zone's **entire** current body on `PUT` (the wire contract
 * has no partial-patch geofence endpoint, see `GeofenceZoneRequest`'s own doc comment) and adopt
 * the server's own response into `zones` on success, rather than re-`GET`-ting the whole list.
 *
 * **Delete is undoable (docs/OPS-CORE-PLAN.md §G-c, 10s, §Q2's shared `UndoToastService`)**:
 * `remove()` deletes immediately (no confirm dialog — "undo over confirm", docs/UX-REWORK-PLAN.md
 * §U-a2), then offers `Undo`, which re-`POST`s a fresh zone with the deleted one's exact fields —
 * mirrors `features/devices/devices.ts#archiveAssetNow`'s own "the mutation already happened,
 * Undo re-creates via the API" idiom exactly (the recreated zone gets a new id; nothing in this
 * app's own UI is keyed on a zone id surviving a delete/undo round trip).
 */
@Injectable({ providedIn: 'root' })
export class GeofenceStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);

  private readonly zonesSignal = signal<readonly GeofenceZone[]>([]);
  readonly zones = this.zonesSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure) — distinguishes "loading" from "genuinely empty". */
  readonly loaded = this.loadedSignal.asReadonly();

  constructor() {
    void this.refresh(); // one-time initial fetch, mirrors `FleetStore`'s own constructor precedent.
    // Poll-while-visible off the app's one shared timer (`PollScheduler`) — no `DestroyRef` needed,
    // this store is `providedIn: 'root'` and never destroyed during a session, same posture as
    // `FleetStore`'s own always-on poll.
    inject(PollScheduler).schedule(ZONES_POLL_INTERVAL_MS, () => this.refresh());
  }

  async refresh(): Promise<void> {
    try {
      const zones = await this.api.listGeofences();
      this.zonesSignal.set(zones);
    } catch {
      // Silent-degrade, like every other background poller in this app — a transient failure
      // keeps showing the last-known list rather than flashing empty.
    } finally {
      this.loadedSignal.set(true);
    }
  }

  /** Creates a zone; `null` on failure (a toast already explains why). */
  async create(request: GeofenceZoneRequest): Promise<GeofenceZone | null> {
    try {
      const created = await this.api.createGeofence(request);
      this.zonesSignal.update((zones) => [...zones, created]);
      return created;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }

  /** Inline rename — resends the zone's full body with only `name` changed. */
  async rename(zone: GeofenceZone, name: string): Promise<boolean> {
    return this.replace(zone, { name });
  }

  /** The list row's enable/disable toggle — resends the zone's full body with only `enabled` changed. */
  async setEnabled(zone: GeofenceZone, enabled: boolean): Promise<boolean> {
    return this.replace(zone, { enabled });
  }

  /**
   * Redraws a zone's polygon/kind/altitude ceiling — not currently reachable from any UI surface
   * this batch adds (the draw dialog is create-only, see `features/command/geofence-zone-dialog.ts`),
   * kept here so a future "Edit boundary" entry has a ready-made, fully-tested seam rather than a
   * new one, mirroring how `core/fleet/warehouse-logic.ts`'s edit builders stay generic.
   */
  async redraw(
    zone: GeofenceZone,
    edit: { readonly kind?: ZoneKind; readonly polygon?: readonly GeoPosition[]; readonly maxAltitudeMeters?: number },
  ): Promise<boolean> {
    return this.replace(zone, edit);
  }

  private async replace(zone: GeofenceZone, edit: Partial<GeofenceZoneRequest>): Promise<boolean> {
    const request: GeofenceZoneRequest = {
      name: edit.name ?? zone.name,
      kind: edit.kind ?? zone.kind,
      polygon: edit.polygon ?? zone.polygon,
      maxAltitudeMeters: edit.maxAltitudeMeters ?? zone.maxAltitudeMeters,
      enabled: edit.enabled ?? zone.enabled,
    };
    try {
      const updated = await this.api.updateGeofence(zone.id, request);
      this.zonesSignal.update((zones) => zones.map((candidate) => (candidate.id === zone.id ? updated : candidate)));
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return false;
    }
  }

  /**
   * Deletes `zone` immediately, then offers a 10s `Undo` that re-creates an equivalent zone (a new
   * id — see class doc). Failure to delete surfaces a plain error toast; the zone stays in the list.
   */
  async remove(zone: GeofenceZone): Promise<void> {
    try {
      await this.api.deleteGeofence(zone.id);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return;
    }
    this.zonesSignal.update((zones) => zones.filter((candidate) => candidate.id !== zone.id));
    this.undoToast.showUndo(`Deleted zone "${zone.name}".`, () => void this.recreate(zone));
  }

  private async recreate(zone: GeofenceZone): Promise<void> {
    await this.create({
      name: zone.name,
      kind: zone.kind,
      polygon: zone.polygon,
      maxAltitudeMeters: zone.maxAltitudeMeters,
      enabled: zone.enabled,
    });
  }
}
