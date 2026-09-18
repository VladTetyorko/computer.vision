import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { AccessLevel, CreateLayerRequest, LayerGrant, MapLayer } from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable } from '../live/live-fallback-logic';
import {
  applyLayerEvents,
  accessTo,
  canContribute,
  canManage,
  contributableLayers,
  copLayer,
  defaultContributeLayerId,
  findLayer,
  layerName,
  manageableLayers,
} from './layers-logic';

/**
 * Safety-net only, like `GeofenceStore`'s own 30s cadence: the `map` live topic is always-on and
 * this store folds every layer delta in as it arrives, so this poll exists purely to reconcile a
 * connection that is genuinely down.
 *
 * **Gated on live, not unconditional** (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — runs
 * **only** while `activeConsumers > 0` **and** `LiveStore` is not `'open'`; see
 * `MarksStore.applyTransport`'s identical state table (`applyTransport` below implements the same
 * one). **Grants are the one thing this poll alone used to repair** — they deliberately never
 * travel over SSE (docs/plans/done/MAP-REWORK-PLAN.md §4.3) — so retiring it outright would leave a
 * revoked grant invisible for as long as live stays up; {@link scheduleGrantsReconcile} is the
 * targeted fix that replaces it (see that method's own doc comment).
 */
const LAYERS_POLL_INTERVAL_MS = 30_000;

/**
 * How long {@link scheduleGrantsReconcile} waits for a *quiet* period after the last `layer`-entity
 * live delta before re-reading grants — long enough to coalesce a burst of edits (e.g. a manager
 * re-granting several subjects in a row) into one `GET`, short enough that a solitary revocation
 * still converges in about a second rather than riding out to the next 30s poll tick.
 */
const GRANTS_RECONCILE_DEBOUNCE_MS = 1_000;

/**
 * `LayersStore` — the app's one source of truth for the map's data layers and, crucially, **the
 * viewer's own resolved access to each of them** (docs/plans/done/MAP-REWORK-PLAN.md §3/§5.2).
 * `providedIn: 'root'` and started at boot alongside `GeofenceStore`/`MarksStore`: layers name the
 * rows of `<vision-tactical-map>`'s data-layer panel, gate the mark palette's layer picker, decide
 * which verify/promote controls a manager sees, and back the layer manager — every map host needs
 * the same list, and none should stand up its own poller.
 *
 * <h2>`myAccess` is the server's answer, never this client's</h2>
 * Every layer arrives with `myAccess` already resolved by `MapAccessPolicy` (§3's max-of-the-rules).
 * The `canView`/`canContribute`/`canManage` helpers here (and `layers-logic.ts`'s pure ranking) only
 * let the UI **hide what the server would forbid** — §5.2's own rule — so an operator never faces a
 * button that always 403s. They are not a security boundary: every mutation still goes to the
 * server, and a 403/404 still surfaces as a toast through {@link run}.
 *
 * <h2>Initial GET + live deltas + a grants-shaped caveat</h2>
 * Same posture as every other map-data store: `GET /api/map/layers` first, then fold
 * `LiveStore.mapEvents()` on top (`layers-logic.ts#applyLayerEvents`). The one wrinkle is that a
 * layer arriving over SSE never carries `grants` (§4.3) — `applyLayerEvents` therefore preserves the
 * previously-known list rather than blanking a manager's open grants editor. That fold is correct
 * for every change *except* a grant **revocation**, which it cannot represent (there is no "grants
 * shrank" delta to apply) — {@link scheduleGrantsReconcile} is what eventually re-reads them
 * authoritatively now that the poll no longer runs unconditionally (see D1 below).
 *
 * <h2>Polling is demand-gated (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)</h2>
 * See `MarksStore`'s identical doc section — same defect (a `root`-provided 30s poll that, once
 * started by any map surface, never stopped for the rest of the session), same fix: {@link activate}/
 * {@link release}, called by every direct injector of this store (the four routed pages' own facades
 * that render `<vision-tactical-map>`, plus `MarksPanel`/`MarkPalette`/`DrawingToolbar`/
 * `VerifyControls`/`LayerManager` as non-routed presentational children — `/crew/:assetId` mounts no
 * map of its own and depends entirely on the latter for its Map tools drawer, so skipping those would
 * leave that route's drawer polling on borrowed demand from whichever *other* page happened to be
 * visited first).
 *
 * <h2>...and now also gated on live itself (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)</h2>
 * Same composition as `MarksStore.applyTransport` — `activeConsumers > 0 && !isLiveAvailable(...)`
 * is the only state that runs the 30s poll. Since a genuine revocation would otherwise be invisible
 * for as long as live stays open (the safety-net poll used to be the *only* thing that ever repaired
 * it), this store additionally schedules a short debounced `refresh()` off of `layer`-entity live
 * deltas themselves — {@link scheduleGrantsReconcile} — independent of `activeConsumers`, so grants
 * still converge in about a second even while live is open, instead of never.
 */
@Injectable({ providedIn: 'root' })
export class LayersStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly live = inject(LiveStore);
  private readonly scheduler = inject(PollScheduler);

  private readonly layersSignal = signal<readonly MapLayer[]>([]);
  /** Every layer this viewer may see, COP first then by name — already scoped server-side. */
  readonly layers = this.layersSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure) — tells "loading" from "genuinely no layers". */
  readonly loaded = this.loadedSignal.asReadonly();

  /** The layer picker's own options — everything this viewer may write to. */
  readonly contributable = computed(() => contributableLayers(this.layersSignal()));

  /** The rows the layer manager may rename/delete/re-grant. Empty for a viewer who manages nothing — the whole editor then never renders. */
  readonly manageable = computed(() => manageableLayers(this.layersSignal()));

  /** The single common-picture layer, or `undefined` if this deployment's bootstrap hasn't created it (or it isn't visible yet). */
  readonly cop = computed(() => copLayer(this.layersSignal()));

  /** Which layer a new mark/drawing lands on by default; `undefined` means "omit `layerId` and let §3's server-side default apply". */
  readonly defaultLayerId = computed(() => defaultContributeLayerId(this.layersSignal()));

  /** How many live map deltas (`LiveStore.mapEvents()`, a chronological append-only log) this store has folded in. */
  private processedLiveEventCount = 0;

  /** Ref-count of live consumers — see {@link activate}/{@link release}. */
  private activeConsumers = 0;
  /** The safety-net poll's own unsubscribe, held only while `activeConsumers > 0`. */
  private stopPollFn: (() => void) | null = null;

  /**
   * `false` while this store is (or should be) relying on the safety-net poll rather than live —
   * see {@link applyTransport}'s own doc comment for the full state table this tracks. Starts
   * `false` so this store's very first `applyTransport` call — whichever way `liveAvailable`
   * resolves — is always treated as a genuine transition, never a spurious no-op.
   */
  private liveGated = false;

  /** {@link scheduleGrantsReconcile}'s own debounce handle — `null` while no reconcile is pending. */
  private grantsReconcileTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // Mirrors `MarksStore`/`DrawingsStore`'s identical cursor over the same shared arrival log — see
    // `core/live/live-store.ts#mapEvents`' own doc comment for why three consumers read one signal.
    // Left unconditional (unlike the GET + poll below): folding an already-arrived SSE event is an
    // in-memory reduction with no network cost, so there is nothing to gate on demand.
    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.layersSignal.update((layers) => applyLayerEvents(layers, newEvents));
      if (newEvents.some((event) => event.entity === 'layer')) {
        this.scheduleGrantsReconcile();
      }
    });

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops
    // (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — mirrors `FleetStore`/
    // `EventsStore`'s identical reconnect-driven effect.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });
  }

  /**
   * Registers demand — see `MarksStore.activate`'s identical doc comment for the full rationale.
   * The first `activate()` since the last full `release()` routes through {@link applyTransport}
   * with the current transport; further concurrent consumers just bump the count.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers > 1) {
      return;
    }
    this.applyTransport(isLiveAvailable(this.live.connectionState()));
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
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
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3) — see
   * `MarksStore.applyTransport`'s own doc comment for the full state table; this is the identical
   * shape. Called both by the reconnect-driven `effect()` above and by `activate()` itself.
   */
  private applyTransport(liveAvailable: boolean): void {
    if (this.activeConsumers === 0) {
      this.stopPolling();
      // Forget the transport mode too. A live outage that starts *and ends* while nothing is
      // mounted delivers no deltas and leaves no trace, so a stale `liveGated` would make the next
      // `activate()` skip its reconcile and show data missing everything the outage swallowed.
      // Clearing it here also restores `activate()`'s documented "first consumer re-fetches"
      // contract, which the live gate would otherwise have quietly weakened.
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
    this.stopPollFn = this.scheduler.schedule(LAYERS_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
  }

  /**
   * The L1c fix (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L1): a layer arriving over SSE
   * never carries `grants` (§4.3), and `applyLayerEvents` compensates by preserving the
   * previously-known list — correct for every change except a **revocation**, which that fold
   * cannot represent. Debounced (not fired per event) so a burst of grant edits costs one `GET`, not
   * N; deliberately independent of `activeConsumers` — a layer changing is rare enough that always
   * reconciling it costs nothing, and other stores (`MarksStore`'s palette, `DrawingsStore`'s
   * `targetLayerId`) read this store's access decisions even on a page that never itself calls
   * `LayersStore.activate()`.
   */
  private scheduleGrantsReconcile(): void {
    if (this.grantsReconcileTimer !== null) {
      clearTimeout(this.grantsReconcileTimer);
    }
    this.grantsReconcileTimer = setTimeout(() => {
      this.grantsReconcileTimer = null;
      void this.refresh();
    }, GRANTS_RECONCILE_DEBOUNCE_MS);
  }

  async refresh(): Promise<void> {
    try {
      this.layersSignal.set(await this.api.listMapLayers());
    } catch {
      // Silent-degrade, like every other background poller here — a transient failure keeps the
      // last-known list rather than flashing every map layer away.
    } finally {
      this.loadedSignal.set(true);
    }
  }

  // --- Access lookups (the UI's "should I even render this control?" questions) -------------------

  layer(layerId: string | undefined): MapLayer | undefined {
    return findLayer(this.layersSignal(), layerId);
  }

  /** The viewer's own level on `layerId`, or `undefined` for a layer they cannot see. */
  access(layerId: string | undefined): AccessLevel | undefined {
    return accessTo(this.layersSignal(), layerId);
  }

  canContributeTo(layerId: string | undefined): boolean {
    return canContribute(this.layer(layerId));
  }

  /** Gates the verify/promote controls and the grants editor — §3's own `canManage` rule, as the server already resolved it. */
  canManageLayer(layerId: string | undefined): boolean {
    return canManage(this.layer(layerId));
  }

  /** A layer's display name, or `undefined` — callers render an em dash rather than a raw uuid. */
  nameOf(layerId: string | undefined): string | undefined {
    return layerName(this.layersSignal(), layerId);
  }

  /** Whether a mark already sits on the common picture — hides "Promote" for a mark that is already there. */
  isCop(layerId: string | undefined): boolean {
    return layerId !== undefined && this.cop()?.layerId === layerId;
  }

  // --- CRUD ---------------------------------------------------------------------------------------

  /** Creates a TEAM (managers of that group) or PERSONAL (anyone) layer; `null` on failure — a toast already explains why. */
  async create(request: CreateLayerRequest): Promise<MapLayer | null> {
    return this.run(async () => {
      const created = await this.api.createMapLayer(request);
      this.adopt(created);
      this.toasts.ok(`Created layer "${created.name}".`);
      return created;
    });
  }

  async rename(layerId: string, name: string): Promise<boolean> {
    const updated = await this.run(() => this.api.renameMapLayer(layerId, { name }));
    if (updated) {
      this.adopt(updated);
    }
    return updated !== null;
  }

  /**
   * Deletes the layer **and everything on it** (§4.1 cascades marks + drawings). No undo offered,
   * deliberately unlike `GeofenceStore.remove`'s 10s undo: re-creating a layer would not bring its
   * marks and drawings back, so an Undo affordance here would promise a restore it cannot deliver.
   * The layer manager asks for confirmation instead.
   */
  async remove(layerId: string): Promise<boolean> {
    const ok = await this.run(async () => {
      await this.api.deleteMapLayer(layerId);
      return true;
    });
    if (ok) {
      this.layersSignal.update((layers) => layers.filter((layer) => layer.layerId !== layerId));
    }
    return ok === true;
  }

  /** Wholesale grants replacement (§4.1's `PUT`) — send every grant that should survive, not a delta. */
  async setGrants(layerId: string, grants: readonly LayerGrant[]): Promise<boolean> {
    const updated = await this.run(() => this.api.setMapLayerGrants(layerId, { grants }));
    if (updated) {
      this.adopt(updated);
      this.toasts.ok(`Updated access for "${updated.name}".`);
    }
    return updated !== null;
  }

  /** Replaces (or inserts) one layer from a server response, keeping the list's own COP-first ordering. */
  private adopt(layer: MapLayer): void {
    this.layersSignal.update((layers) => applyLayerEvents(layers, [{ entity: 'layer', action: 'updated', layerId: layer.layerId, layer }]));
  }

  /** One explained toast per failure, mirroring `OrgFacade.run`/`FleetStore.run`'s shared seam. */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
