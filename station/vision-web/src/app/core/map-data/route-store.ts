import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { ROUTE_MAX_POINTS, buildAssetRoute, routeUsageLimit, type AssetRoute, type RouteSpan } from './route-logic';

/** `RouteStore.state`'s own tri-state — `AssetPanel`'s Telemetry tab reads this, never a raw try/catch. */
export type RouteLoadState = 'idle' | 'loading' | 'loaded' | 'error';

/**
 * `RouteStore` — the on-demand two-hop fetch behind §3.4's "route on click"
 * (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md), unlike every other `core/map-data/**` store: those
 * are always-on, `providedIn: 'root'`, folding a live event stream. A route is the opposite shape —
 * fetched once per `show()` call, never live-updated, and shown for at most one asset at a time — so
 * this store is **page-provided**, the same posture as `FleetMapStore`/`WeatherStore`
 * (`core/map/map-store.ts`'s own class doc comment): `CommandPage` lists it in its own `providers`,
 * `CommandFacade` injects and orchestrates it, and it starts/stops with that route activation.
 *
 * <h2>The two hops, both already wrapped in `VisionApi`</h2>
 * `show(assetId, span)` first calls `listUsages({ assetId, limit })` (scoped — a viewer only ever
 * sees usages for assets their visibility scope already grants), then `usageTimeline(usageId,
 * { maxPoints: ROUTE_MAX_POINTS })` for each usage returned (NOT independently scoped — a
 * pre-existing gap in `AssetController`/`UsageTimelineController`, acceptable here since every
 * `usageId` this store ever asks for came out of the scoped hop 1, never off a URL). **Never**
 * `usageTelemetry` (`.../telemetry`) — that endpoint is earliest-not-latest (D1, `docs/plans/active/COMMAND-MAP-FLOW-PLAN.md`
 * §3.7's own backend defect, fixed independently on another branch) — a route always reads
 * `.../timeline`, the same endpoint `features/replay/**` already uses for exactly this reason.
 *
 * <h2>Degrades honestly, one state at a time</h2>
 * `state` is `'error'` only when a fetch itself failed (network/5xx) — never fabricated from an
 * empty result. Zero usages for the asset is not an error: `routes` is simply `[]` and `noUsages`
 * flips true, so `AssetPanel` can show *"No recorded flights for this asset"* rather than a generic
 * failure. A usage with samples but none carrying a GPS fix is not an error either — it comes back
 * as an `AssetRoute` with `points: []`, which the panel names *"This flight recorded no positions"*
 * per-route, right next to the flights that did.
 *
 * <h2>Generation-guarded, not cancellable</h2>
 * `fetch`/`XMLHttpRequest` promises can't be aborted through `VisionApi`'s current surface, so a
 * superseded `show()` call (the operator changed span, or selected a different asset, mid-flight)
 * is instead detected on return via a bumped `generation` counter and its result silently dropped —
 * the same "generation" idiom `core/map/map-store.ts#AssetTracker`/`initTracker` already uses for
 * an identical race.
 */
@Injectable()
export class RouteStore {
  private readonly api = inject(VisionApi);

  private readonly routesSignal = signal<readonly AssetRoute[]>([]);
  readonly routes = this.routesSignal.asReadonly();

  private readonly stateSignal = signal<RouteLoadState>('idle');
  readonly state = this.stateSignal.asReadonly();
  readonly loading = computed(() => this.stateSignal() === 'loading');
  readonly error = computed(() => this.stateSignal() === 'error');

  /** True once a `show()` call resolved and found zero usages at all for the asset. */
  private readonly noUsagesSignal = signal(false);
  readonly noUsages = this.noUsagesSignal.asReadonly();

  private generation = 0;

  /**
   * Fetches and shows `assetId`'s route(s) for `span` — `'off'` is a fast path that just clears the
   * map without a request. Safe to call repeatedly (a fresh asset selection, or the segmented
   * control changing span): each call supersedes whatever the previous one was doing.
   */
  async show(assetId: string, span: RouteSpan): Promise<void> {
    const generation = ++this.generation;
    // Cleared unconditionally, before the fetch even starts — never leaves a *previous* selection's
    // (or a stale span's) route lingering on the map while this one loads, which is what the
    // frozen "never more than one asset's route on the map at once" rule (§3.4) actually requires:
    // not just "the final state is right", but "there is never a moment it's wrong".
    this.routesSignal.set([]);
    if (span === 'off') {
      this.stateSignal.set('idle');
      this.noUsagesSignal.set(false);
      return;
    }
    this.stateSignal.set('loading');
    this.noUsagesSignal.set(false);
    try {
      const usages = await this.api.listUsages({ assetId, limit: routeUsageLimit(span) });
      if (generation !== this.generation) {
        return; // superseded
      }
      if (usages.length === 0) {
        this.routesSignal.set([]);
        this.noUsagesSignal.set(true);
        this.stateSignal.set('loaded');
        return;
      }
      const timelines = await Promise.all(
        usages.map((usage) => this.api.usageTimeline(usage.usageId, { maxPoints: ROUTE_MAX_POINTS })),
      );
      if (generation !== this.generation) {
        return; // superseded
      }
      this.routesSignal.set(usages.map((usage, index) => buildAssetRoute(assetId, usage, timelines[index])));
      this.stateSignal.set('loaded');
    } catch {
      if (generation !== this.generation) {
        return; // superseded
      }
      this.routesSignal.set([]);
      this.stateSignal.set('error');
    }
  }

  /** Clears the map and supersedes any in-flight fetch — deselecting an asset always clears its route. */
  hide(): void {
    this.generation++;
    this.routesSignal.set([]);
    this.stateSignal.set('idle');
    this.noUsagesSignal.set(false);
  }
}
