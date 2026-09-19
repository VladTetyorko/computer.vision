import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import type { AccessLevel, CreateLayerRequest, LayerGrant, MapLayer } from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { accessTo, canContribute, canManage, findLayer, layerName } from './layers-logic';
import { LayersApiActions, LayersPageActions } from './state/layers.actions';
import { layersFeature } from './state/layers.reducer';

/**
 * `LayersStore`'s read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6).
 * `providedIn: 'root'`, exactly like the store it replaces — see that class's own doc comment for
 * why every map host must share the one instance rather than standing up its own poller.
 *
 * The six lookup methods below stay plain synchronous methods reading `this.layers()` (not
 * selectors of their own) so every call site needs nothing beyond the `inject()` swap — they read
 * identically to `LayersStore`'s own methods of the same name.
 *
 * **Page-provided since wave N4, not `providedIn: 'root'`** (NGRX-MIGRATION-PLAN.md §9). Every class
 * that injects this sits behind a lazy route — the four map-surface page facades and the controls
 * inside `<vision-map-tools>` (`shared/map/map-controls/**`) — so the `layers` slice is registered by
 * each of those five routes instead of shipping in every visitor's initial bundle. **Behaviour change
 * this carries:** the slice no longer survives navigating between map surfaces, so entering
 * `/command` from `/fly` reconciles from the server rather than inheriting the previous page's copy.
 * The demand ref-count (`activate()`/`release()`) is unaffected — it always protected *concurrent*
 * consumers within one page, and one page is all that is ever mounted.
 */
@Injectable()
export class LayersFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  /** Every layer this viewer may see, COP first then by name — already scoped server-side. */
  readonly layers = this.store.selectSignal(layersFeature.selectAllLayers);
  /** `true` once the first load has settled (success or failure) — tells "loading" from "genuinely no layers". */
  readonly loaded = this.store.selectSignal(layersFeature.selectLoaded);
  /** The layer picker's own options — everything this viewer may write to. */
  readonly contributable = this.store.selectSignal(layersFeature.selectContributable);
  /** The rows the layer manager may rename/delete/re-grant. */
  readonly manageable = this.store.selectSignal(layersFeature.selectManageable);
  /** The single common-picture layer, or `undefined` if this deployment hasn't created (or shown) one yet. */
  readonly cop = this.store.selectSignal(layersFeature.selectCop);
  /** Which layer a new mark/drawing lands on by default; `undefined` means "let the server decide". */
  readonly defaultLayerId = this.store.selectSignal(layersFeature.selectDefaultLayerId);

  /** Registers demand — see `LayersStore.activate`'s identical doc comment for the full rationale. */
  activate(): void {
    this.store.dispatch(LayersPageActions.activated());
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(LayersPageActions.released());
  }

  // --- Access lookups (the UI's "should I even render this control?" questions) -------------------

  layer(layerId: string | undefined): MapLayer | undefined {
    return findLayer(this.layers(), layerId);
  }

  /** The viewer's own level on `layerId`, or `undefined` for a layer they cannot see. */
  access(layerId: string | undefined): AccessLevel | undefined {
    return accessTo(this.layers(), layerId);
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
    return layerName(this.layers(), layerId);
  }

  /** Whether a mark already sits on the common picture — hides "Promote" for a mark that is already there. */
  isCop(layerId: string | undefined): boolean {
    return layerId !== undefined && this.cop()?.layerId === layerId;
  }

  // --- CRUD ---------------------------------------------------------------------------------------

  /** Creates a TEAM (managers of that group) or PERSONAL (anyone) layer; `null` on failure — a toast already explains why. */
  async create(request: CreateLayerRequest): Promise<MapLayer | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      LayersPageActions.createRequested({ request }),
      LayersApiActions.createSucceeded,
      LayersApiActions.createFailed,
      (action) => action.layer,
      () => null,
    );
  }

  async rename(layerId: string, name: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      LayersPageActions.renameRequested({ layerId, name }),
      LayersApiActions.renameSucceeded,
      LayersApiActions.renameFailed,
      () => true,
      () => false,
    );
  }

  /**
   * Deletes the layer **and everything on it** (§4.1 cascades marks + drawings). No undo offered,
   * deliberately unlike `GeofenceFacade.remove`'s 10s undo — see `layers.reducer.ts
   * #removeSucceeded`'s own doc comment. The layer manager asks for confirmation instead.
   */
  async remove(layerId: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      LayersPageActions.removeRequested({ layerId }),
      LayersApiActions.removeSucceeded,
      LayersApiActions.removeFailed,
      () => true,
      () => false,
    );
  }

  /** Wholesale grants replacement (§4.1's `PUT`) — send every grant that should survive, not a delta. */
  async setGrants(layerId: string, grants: readonly LayerGrant[]): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      LayersPageActions.setGrantsRequested({ layerId, grants }),
      LayersApiActions.setGrantsSucceeded,
      LayersApiActions.setGrantsFailed,
      () => true,
      () => false,
    );
  }
}
