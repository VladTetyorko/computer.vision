import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { FleetMapStore } from '../../core/map/map-store';
import { resolveEventTarget } from '../../core/events/events-logic';
import { FleetMap } from '../../shared/map/fleet-map';
import { LiveDock } from '../../shared/map/live-dock';
import { EventsRail } from '../../shared/ui/events-rail';
import { LiveStripTile } from './live-strip-tile';
import { attentionAgeLabel, buildAttentionQueue, sortReadinessTiles, streamingAssets, totalStreaming } from './command-logic';
import type { AssetAttention, DetectionEvent, FleetSummary } from '../../core/api/models';

/** The one poll driving the attention queue, the strip's membership, and the readiness tiles alike. */
const SUMMARY_POLL_INTERVAL_MS = 5_000;

/**
 * `/command` — the manager dashboard (docs/MVP3-PLAN.md §C-c), the second of the plan's two
 * job-oriented pages: *does attention triage, not watching* — "an operator opens the app and is
 * flying-aware in one click; a manager opens the app and knows within five seconds which of 100
 * assets needs attention."
 *
 * **One aggregated poll drives everything** (bullet 6): `GET /api/fleet/summary` every 5s
 * (`refreshSummary`, via the shared `PollScheduler`) populates one `summary` signal; the attention
 * queue, the live strip's membership, and the warehouse readiness tiles are all pure `computed()`s
 * over that one signal (`command-logic.ts`) — there is no code path anywhere on this page that
 * issues a second request per asset. The only *other* requests this page makes are: one snapshot
 * poll per **visible** live-strip tile (`live-strip-tile.ts`, gated by `IntersectionObserver` +
 * CDK virtual scroll — see that component's own doc comment), and whatever `shared/map/fleet-map.ts`'s own
 * already-established polling does for its embedded map (see below) — never anything proportional
 * to total fleet size from this page's own code.
 *
 * **Composition, not new UI** — every section reuses an existing piece, each justified below (the
 * plan's own binding "reuse-first" rule):
 * - **Attention queue** — new markup (a list has to render *somewhere*), but zero new polling: pure
 *   `computed()`s over `summary()` via `command-logic.ts#buildAttentionQueue`.
 * - **Live strip** — `LiveStripTile` is genuinely new (no existing component polls a snapshot
 *   `<img>`), but it's a thin composition of an `<img>` + the same `IntersectionObserver`/
 *   `PollScheduler` idioms `features/wall/wall-tile.ts` already established, not a new pattern.
 * - **Fleet map** — `<vision-fleet-map>` (`shared/map/fleet-map.ts`) reused **unmodified**, own
 *   `FleetMapStore` instance (own `providers`, like `MapPage`). Its own already-existing 5s
 *   `GET /api/assets` poll + per-*streaming*-asset 2s telemetry pollers are unchanged, pre-existing
 *   behavior from C6/CU-b, not new surface this cycle adds — see the Status/report note on why this
 *   doesn't count against this page's own "one summary poll" duty. **Watch** here still emits an
 *   assetId (unchanged component contract), but this page wires it straight to `watchAsset` —
 *   `/fly?asset=<id>&watch=1` (no device lookup needed, unlike `MapPage`'s `/live/:deviceId`) —
 *   **Preview** still docks `<vision-live-dock>` exactly like `MapPage` (`onPreview`).
 * - **Warehouse readiness tiles** — plain buttons over `summary().categories` (never capped,
 *   server-side truth regardless of the `assets` list's own 500-row cap); click navigates to
 *   `/devices` (CD-b's own asset-first list has no category filter to deep-link into yet — per the
 *   plan's own instruction, this does **not** add one; a future cycle that adds Devices filtering
 *   is where this tile's click would gain a query param).
 * - **Events rail** — `<vision-events-rail>` (`shared/ui/events-rail.ts`, extracted from `features/wall/`
 *   this same cycle so Command could reuse it too — see that component's own doc comment) reused
 *   unmodified; this page owns `EventsStore.activate()`/`release()` exactly like `WallPage`/
 *   `MapPage`/`AssetDetailPage` already do (one more of the handful of pages keeping that shared
 *   poll alive while mounted) and resolves a clicked row's navigation target itself
 *   (`openEventFromRail`), identical to `WallPage`'s own `openEvent`.
 *
 * **CDK virtual scroll** (bullet 3/scale duty) on the live strip — this app's second use of
 * `@angular/cdk/scrolling` beyond `features/devices/devices.ts`'s asset list (U2's own precedent,
 * horizontal here rather than vertical: `orientation="horizontal"`, still a fixed `itemSize`).
 * Rendering cost for the strip stays bounded to roughly "however many tiles fit the viewport plus a
 * small buffer" regardless of how many assets in the fleet happen to be streaming at once.
 */
@Component({
  selector: 'vision-command',
  imports: [ScrollingModule, FleetMap, LiveDock, EventsRail, LiveStripTile],
  templateUrl: './command.html',
  styleUrl: './command.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `MapPage`'s own `FleetMapStore`.
  providers: [FleetMapStore],
})
export class CommandPage {
  private readonly router = inject(Router);
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  protected readonly events = inject(EventsStore);
  protected readonly mapStore = inject(FleetMapStore);

  protected readonly summary = signal<FleetSummary | undefined>(undefined);
  /** Only ever set when the *very first* load fails — a background poll failure silently degrades. */
  protected readonly summaryError = signal(false);
  protected readonly includeArchived = signal(false);

  protected readonly attentionRows = computed(() => buildAttentionQueue(this.summary()?.assets ?? []));
  protected readonly stripAssets = computed(() => streamingAssets(this.summary()?.assets ?? []));
  protected readonly readinessTiles = computed(() => sortReadinessTiles(this.summary()?.categories ?? []));
  protected readonly totalAssetsCount = computed(() => this.summary()?.totalAssets ?? 0);
  protected readonly totalStreamingCount = computed(() => totalStreaming(this.summary()?.categories ?? []));

  // --- Docked live preview (bullet 3, LiveDock precedent) — at most one at a time, identical
  // shape to `MapPage`'s own `dockedAssetId`/`dockedDeviceId`/`dockedMarker`.
  protected readonly dockedAssetId = signal<string | null>(null);
  protected readonly dockedDeviceId = signal<string | null>(null);
  protected readonly dockedMarker = computed(() => {
    const assetId = this.dockedAssetId();
    return assetId ? this.mapStore.markers().find((marker) => marker.assetId === assetId) : undefined;
  });

  constructor() {
    this.events.activate();
    void this.refreshSummary();
    const stopPoll = inject(PollScheduler).schedule(SUMMARY_POLL_INTERVAL_MS, () => this.refreshSummary());
    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      stopPoll();
    });
  }

  private async refreshSummary(): Promise<void> {
    try {
      const data = await this.api.fleetSummary(this.includeArchived());
      this.summary.set(data);
      this.summaryError.set(false);
    } catch {
      if (this.summary() === undefined) {
        this.summaryError.set(true);
      }
      // else: silent-degrade — a background poll failure keeps showing the last-known summary,
      // matching every other poller in this app.
    }
  }

  protected toggleIncludeArchived(checked: boolean): void {
    this.includeArchived.set(checked);
    void this.refreshSummary(); // don't make the toggle wait up to 5s for the next scheduled poll
  }

  // --- Navigation (the drill-down target every surface on this page shares) -------------------

  /** `router.navigate(['/fly'], {queryParams: {asset, watch: 1}})` — C-a/C-b's own pinned contract. */
  protected watchAsset(assetId: string): void {
    void this.router.navigate(['/fly'], { queryParams: { asset: assetId, watch: 1 } });
  }

  protected openAsset(assetId: string): void {
    void this.router.navigate(['/assets', assetId]);
  }

  protected openDevices(): void {
    void this.router.navigate(['/devices']);
  }

  protected ageLabel(asset: AssetAttention): string {
    return attentionAgeLabel(asset);
  }

  // --- Fleet map wiring (mirrors `MapPage`'s own split — see class doc) -----------------------

  protected async onPreview(assetId: string): Promise<void> {
    const device = await this.mapStore.resolveWatchDevice(assetId);
    if (device) {
      this.dockedAssetId.set(assetId);
      this.dockedDeviceId.set(device.id);
    }
  }

  protected closeDock(): void {
    this.dockedAssetId.set(null);
    this.dockedDeviceId.set(null);
  }

  // --- Events rail wiring (identical to `WallPage`'s own `openEvent`) --------------------------

  protected openEventFromRail(event: DetectionEvent): void {
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    void this.router.navigate(target.kind === 'asset' ? ['/assets', target.id] : ['/live', target.id]);
  }

  /** `cdkVirtualFor`'s own `trackBy` shape (the `@for` blocks above track by a plain expression instead). */
  protected trackAsset(_: number, asset: AssetAttention): string {
    return asset.assetId;
  }
}
