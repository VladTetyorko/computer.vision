import { Injectable, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { GeoPosition, GeofenceZone, GeofenceZoneEventPayload, GeofenceZoneRequest, ZoneKind } from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable } from '../live/live-fallback-logic';

/**
 * Zones are near-static reference data, not a hot poll target — refreshed on mount, on every
 * mutation this store itself makes, and lightly in the background so a second manager's edit (or
 * this same operator's own Command tab open twice) eventually shows up on `/fly`'s read-only layer
 * too, without needing FleetStore's 5s cadence.
 *
 * **Gated on live, not unconditional** (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1, wave
 * L5) — this poll now runs **only** while `activeConsumers > 0` **and** `LiveStore` is not `'open'`.
 * While live is open, the `zones` topic (added in wave L3) already delivers every
 * created/updated/deleted delta for free, so scheduling this poll on top of it would just be a
 * redundant `GET` every 30s; see {@link applyTransport} for the exact state table. This supersedes
 * this constant's own former doc comment, which argued gating was *unsafe* because `LiveEnvelope`
 * had no zone topic to project onto while paused — wave L3 gave it exactly that topic, so the
 * argument is answered, not overridden; see the class doc's own "gated on live itself" section.
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
 * See `MarksStore`'s identical doc section for the shared defect/fix: when `activeConsumers === 0`
 * nobody has any zone layer mounted to show stale data on in the first place, so there is no
 * correctness cost to stopping, only the same cross-page-forever-poll waste every other
 * `core/map-data/**` store had. {@link activate}/{@link release}, called by every direct injector
 * (`ZonesPanel` plus the four routed pages' own facades that render a map — matching `MarksStore`'s
 * consumer list exactly).
 *
 * <h2>...and now also gated on live itself (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1,
 * wave L5)</h2>
 * The two axes are orthogonal and compose in one method, {@link applyTransport}, exactly like
 * `MarksStore`'s own: **demand** (`activeConsumers > 0`) says whether anyone needs zone data at all;
 * **transport** (`isLiveAvailable(...)`) says whether the `zones` topic (wave L3) is already
 * delivering it for free. The poll now runs only when demand says yes *and* transport says no. This
 * is what makes this class's former "deliberately not gated on `isLiveAvailable()`" stance (SCALE-100
 * §5 S6) **obsolete, not overridden**: that note's whole argument was that gating would leave a
 * newly-drawn zone invisible for a whole session because `LiveEnvelope` had no zone topic to project
 * onto while paused — wave L3 gave it exactly that topic, so the objection is answered by the thing
 * it asked for. `liveGated` remembers which side of the live/poll line this store was last actually
 * on, so a genuine transition into live reconciles once (deltas alone would miss whatever changed
 * before this store's first activation, or while it was polling), while a call that finds nothing
 * changed is a pure no-op — see {@link applyTransport}'s own doc comment for the full table.
 *
 * <h2>Initial GET + live deltas, not poll-only (wave L3/L5)</h2>
 * The `zones` topic is deliberately not snapshot-on-connect (§4.1) — a fresh connection sees nothing
 * until the next edit — so this store always does its own initial `GET` (`refresh()`, on
 * `activate()` and on the safety-net poll) and folds `LiveStore.zoneEvents()` arrivals on top via
 * this file's own `applyZoneEvents`: `CREATED`/`UPDATED` upsert by id, `DELETED` removes by id. Both
 * halves are idempotent by construction — the same event replayed across a reconnect (a real
 * possibility given `LiveStore`'s own at-least-once framing) just re-applies the same upsert or
 * removal — so a double delivery can never duplicate a zone or resurrect one already deleted.
 */
@Injectable({ providedIn: 'root' })
export class GeofenceStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

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
   * `false` while this store is (or should be) relying on the safety-net poll rather than live —
   * see {@link applyTransport}'s own doc comment for the full state table this tracks. Starts
   * `false` so this store's very first `applyTransport` call — whichever way `liveAvailable`
   * resolves — is always treated as a genuine transition (never a spurious no-op before this store
   * has ever actually fetched anything).
   */
  private liveGated = false;

  /** How many live `zones` deltas this store has already folded in — see `MarksStore`'s identical cursor. */
  private processedLiveZoneEventCount = 0;

  constructor() {
    // Folds every `zones` arrival into `zonesSignal` — runs unconditionally from construction (not
    // gated by activate/release), mirroring `MarksStore`'s own `mapEvents` fold: it's an in-memory
    // upsert/remove with no network cost, and keeping the cursor advancing means a consumer that
    // (re)activates after a gap doesn't replay deltas the next `refresh()` GET already supersedes.
    effect(() => {
      const events = this.live.zoneEvents();
      if (events.length <= this.processedLiveZoneEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveZoneEventCount);
      this.processedLiveZoneEventCount = events.length;
      this.zonesSignal.update((zones) => applyZoneEvents(zones, newEvents));
    });

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops
    // (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — mirrors `MarksStore`/
    // `DiscoveryInboxStore`'s identical reconnect-driven effect.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });
  }

  /**
   * Registers demand — call once from a consumer's own constructor (a routed page's facade, or a
   * non-routed presentational child like `ZonesPanel` that injects this store directly). The first
   * `activate()` since the last full `release()` routes through {@link applyTransport} with the
   * current transport (this store never destructs, so nothing else would ever refresh a long-stale
   * list) — a fresh `GET` happens immediately unless live is already open, in which case there is
   * nothing to poll for yet. Any further concurrent consumer just bumps the count.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers > 1) {
      return;
    }
    this.applyTransport(isLiveAvailable(this.live.connectionState()));
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

  /**
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3): the safety-net poll runs
   * **only** while `activeConsumers > 0` **and** live is unavailable — identical shape to
   * `MarksStore.applyTransport`'s own doc comment.
   *
   * | `activeConsumers` | `liveAvailable` | previous (`liveGated`) | Action |
   * |---|---|---|---|
   * | `0` | any | any | stop poll; no refresh |
   * | `>0` | `true` | `false` (poll) | stop poll; refresh once (the reconcile) |
   * | `>0` | `true` | `true` (live) | nothing |
   * | `>0` | `false` | `true` (live) | refresh once, then start poll |
   * | `>0` | `false` | `false` (poll) | nothing (already polling) |
   *
   * Called both by the reconnect-driven `effect()` in the constructor and by `activate()` itself.
   */
  private applyTransport(liveAvailable: boolean): void {
    if (this.activeConsumers === 0) {
      this.stopPolling();
      // Forget the transport mode too. A live outage that starts *and ends* while nothing is
      // mounted delivers no deltas and leaves no trace, so a stale `liveGated` would make the next
      // `activate()` skip its reconcile and show data missing everything the outage swallowed.
      this.liveGated = false;
      return;
    }
    if (liveAvailable) {
      if (!this.liveGated) {
        this.stopPolling();
        void this.refresh();
        this.liveGated = true;
      }
      return;
    }
    this.liveGated = false;
    if (this.stopPollFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollFn = this.scheduler.schedule(ZONES_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
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

/**
 * Folds new `zones` SSE deltas onto the current zone list — the same "poll gives the full picture,
 * live deltas are incremental on top" pattern `mark-logic.ts#applyMarkEvents`/
 * `discovery-inbox-logic.ts#applyDiscoveryEvents` establish for `map`/`discovery`. `CREATED` and
 * `UPDATED` are handled identically, an upsert by id (replace if already present, insert if not) —
 * treating them the same is what makes a double-delivered event (a real possibility across a
 * reconnect, per `LiveStore`'s own at-least-once framing) idempotent: replaying the same `CREATED` a
 * second time just replaces the entry with an identical copy of itself. `DELETED` removes by id; a
 * second `DELETED` for an already-absent id is a no-op filter, equally safe to repeat.
 */
function applyZoneEvents(
  zones: readonly GeofenceZone[],
  events: readonly GeofenceZoneEventPayload[],
): readonly GeofenceZone[] {
  let result = zones;
  for (const event of events) {
    if (event.action === 'DELETED') {
      result = result.filter((zone) => zone.id !== event.zone.id);
      continue;
    }
    const index = result.findIndex((zone) => zone.id === event.zone.id);
    result = index === -1 ? [...result, event.zone] : result.map((zone, i) => (i === index ? event.zone : zone));
  }
  return result;
}
