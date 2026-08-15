import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { AccessLevel, CreateLayerRequest, LayerGrant, MapLayer } from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
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
 * connection that was briefly down — and, unlike marks/drawings, to re-read the `grants` lists that
 * deliberately never travel over SSE (docs/plans/done/MAP-REWORK-PLAN.md §4.3).
 */
const LAYERS_POLL_INTERVAL_MS = 30_000;

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
 * previously-known list rather than blanking a manager's open grants editor, and the safety-net poll
 * is what eventually re-reads them authoritatively.
 */
@Injectable({ providedIn: 'root' })
export class LayersStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly live = inject(LiveStore);

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

  constructor() {
    void this.refresh();
    inject(PollScheduler).schedule(LAYERS_POLL_INTERVAL_MS, () => this.refresh());

    // Mirrors `MarksStore`/`DrawingsStore`'s identical cursor over the same shared arrival log — see
    // `core/live/live-store.ts#mapEvents`' own doc comment for why three consumers read one signal.
    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.layersSignal.update((layers) => applyLayerEvents(layers, newEvents));
    });
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

  /** One explained toast per failure, mirroring `OrgStore.run`/`FleetStore.run`'s shared seam. */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
