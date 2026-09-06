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
 *
 * Deliberately **not** gated on `isLiveAvailable()` the way the fly pollers are (SCALE-100 §5 S6):
 * gating trades correctness for almost nothing here. `LiveEnvelope` has no zone topic to project
 * while the poll is paused (zone *breaches* ride the generic `event` topic and are a different
 * signal), so pausing would leave a second operator's newly drawn no-fly zone invisible for the
 * whole session — on a safety-adjacent layer — to save 0.033 req/s against a 0.2 req/s budget.
 * Adding a zones topic to the live stream is the fix that would make gating this correct.
 */
const ZONES_POLL_INTERVAL_MS = 30_000;

/**
 * `GeofenceStore` — the app's one shared source of truth for geofence zones
 * (docs/plans/done/OPS-CORE-PLAN.md §G-c), `providedIn: 'root'` and started at boot like `FleetStore`: zones
 * back both Command's Zones panel/map layer and Fly's read-only map layer, and both pages should
 * see the same list without each standing up its own poller.
 *
 * **CRUD, not truly optimistic** (mirrors `FleetStore`'s own warehouse-mutation precedent: await
 * the API call, then adopt its response — never assume a write succeeded before the network says
 * so): `rename`/`setEnabled` resend the zone's **entire** current body on `PUT` (the wire contract
 * has no partial-patch geofence endpoint, see `GeofenceZoneRequest`'s own doc comment) and adopt
 * the server's own response into `zones` on success, rather than re-`GET`-ting the whole list.
 *
 * **Delete is undoable (docs/plans/done/OPS-CORE-PLAN.md §G-c, 10s, §Q2's shared `UndoToastService`)**:
 * `remove()` deletes immediately (no confirm dialog — "undo over confirm", docs/plans/done/UX-REWORK-PLAN.md
 * §U-a2), then offers `Undo`, which re-`POST`s a fresh zone with the deleted one's exact fields —
 * mirrors `features/devices/devices.ts#archiveAssetNow`'s own "the mutation already happened,
 * Undo re-creates via the API" idiom exactly (the recreated zone gets a new id; nothing in this
 * app's own UI is keyed on a zone id surviving a delete/undo round trip).
 *
 * <h2>Polling is demand-gated (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3), never "since app boot"</h2>
 * See `MarksStore`'s identical doc section for the shared defect/fix. This store's own above
 * "deliberately not gated on `isLiveAvailable()`" note is a *different* axis and stays true: while
 * ≥1 consumer is active the poll never pauses for connectivity reasons, for the safety-adjacent
 * reason already given there. Demand-gating is orthogonal — when `activeConsumers === 0` nobody has
 * any zone layer mounted to show stale data on in the first place, so there is no correctness cost
 * to stopping, only the same cross-page-forever-poll waste every other `core/map-data/**` store had.
 * {@link activate}/{@link release}, called by every direct injector (`ZonesPanel` plus the four
 * routed pages' own facades that render a map — matching `MarksStore`'s consumer list exactly).
 */
@Injectable({ providedIn: 'root' })
export class GeofenceStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly scheduler = inject(PollScheduler);

  private readonly zonesSignal = signal<readonly GeofenceZone[]>([]);
  readonly zones = this.zonesSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure) — distinguishes "loading" from "genuinely empty". */
  readonly loaded = this.loadedSignal.asReadonly();

  /** Ref-count of live consumers — see {@link activate}/{@link release}. */
  private activeConsumers = 0;
  /** The poll's own unsubscribe, held only while `activeConsumers > 0`. */
  private stopPollFn: (() => void) | null = null;

  /**
   * Registers demand — call once from a consumer's own constructor (a routed page's facade, or a
   * non-routed presentational child like `ZonesPanel` that injects this store directly). The first
   * `activate()` since the last full `release()` triggers an immediate re-fetch (this store never
   * destructs, so nothing else would ever refresh a long-stale list) and starts the poll; any
   * further concurrent consumer just bumps the count.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers > 1) {
      return;
    }
    void this.refresh();
    this.stopPollFn = this.scheduler.schedule(ZONES_POLL_INTERVAL_MS, () => this.refresh());
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. Stops the poll once nothing is left. */
  release(): void {
    if (this.activeConsumers === 0) {
      return; // defensive — a mismatched release should never go negative
    }
    this.activeConsumers--;
    if (this.activeConsumers === 0 && this.stopPollFn !== null) {
      this.stopPollFn();
      this.stopPollFn = null;
    }
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
