import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { describeHttpError } from '../../core/api-error';
import { videoDevices } from '../../core/fleet/device-logic';
import { telemetryDevices, trackingIdChanged } from '../../core/telemetry/telemetry-logic';
import { derivePreflight, type PreflightItem } from '../../core/telemetry/flight-state-logic';
import type { AssetDetails, AssetSummary } from '../../core/api/models';
import { defaultPreflightAssetId, sortAssetsByName } from './preflight-logic';

/**
 * `PreflightPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/operate/preflight`,
 * docs/plans/done/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Pre-flight checklist": a live status card for a
 * selected drone, reusing `core/telemetry/flight-state-logic.ts#derivePreflight` byte-for-byte
 * (the exact function `FlyFacade.preflightItems` already calls) fed by this page's own, much
 * smaller, asset-selection/telemetry-tracking wiring. Saved, editable checklist templates are not
 * built (no backend entity/endpoint) — the page's own honest inline note covers that.
 *
 * **`TelemetryStore` is listed in `PreflightPage`'s own `providers`** (alongside this facade),
 * mirroring `FlyPage`/`AssetDetailPage`'s identical "page-scoped store, injected by both the page and
 * its facade" pattern — a fresh poller per route activation, torn down on route leave.
 */
@Injectable()
export class PreflightFacade {
  private readonly api = inject(VisionApi);
  readonly fleet = inject(FleetStore);
  private readonly settings = inject(SettingsStore);
  readonly telemetry = inject(TelemetryStore);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private readonly assets = signal<readonly AssetSummary[]>([]);
  readonly sortedAssets = computed(() => sortAssetsByName(this.assets()));
  readonly hasAnyAssets = computed(() => this.assets().length > 0);

  readonly selectedAssetId = signal<string | undefined>(undefined);
  readonly asset = signal<AssetDetails | undefined>(undefined);

  private readonly videoDevicesList = computed(() => videoDevices(this.asset()?.devices ?? []));
  readonly hasVideo = computed(() => this.videoDevicesList().length > 0);

  readonly live = computed(() => {
    const device = this.videoDevicesList()[0];
    return device !== undefined && this.fleet.streamFor(device.id) !== undefined;
  });

  private readonly assetTelemetryDevices = computed(() => telemetryDevices(this.asset()?.devices ?? []));

  /** Re-derives whenever the tracked sample/video/live state changes — a ground-check glance, not a
   *  live-ticking instrument, mirroring `FlyFacade.preflightItems`'s identical convention (and its
   *  own doc comment for why `Date.now()` is read at the call site rather than inside the pure
   *  function itself). */
  readonly preflightItems = computed<readonly PreflightItem[]>(() =>
    derivePreflight(this.telemetry.latest(), this.hasVideo(), this.live(), Date.now()),
  );

  /** The last deviceId the telemetry-tracking effect below actually acted on — see that effect's own
   *  doc comment (mirrors `features/asset-detail/asset-detail-facade.ts`'s identical guard). */
  private lastTelemetryDeviceId: string | undefined = undefined;

  constructor() {
    void this.loadAssets();

    // Any device on the asset resolves the same owning-asset/open-usage pair (see
    // `TelemetryStore`'s own doc comment) — passing `assetId` lets it resolve the open usage with
    // one `getAsset()` instead of listing the whole fleet. Guarded on the derived deviceId
    // primitive (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2, R-c follow-up) so re-entering `track()`
    // with an unchanged id on every ~5s asset refresh never becomes a self-sustaining loop.
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      const deviceId = this.assetTelemetryDevices().length > 0 ? devices[0].id : undefined;
      if (!trackingIdChanged(deviceId, this.lastTelemetryDeviceId)) {
        return;
      }
      this.lastTelemetryDeviceId = deviceId;
      if (deviceId) {
        this.telemetry.track(deviceId, this.selectedAssetId());
      } else {
        this.telemetry.reset();
      }
    });

    // Fetches the selected asset's own devices whenever the selection changes.
    effect(() => {
      const id = this.selectedAssetId();
      if (id) {
        void this.loadAsset(id);
      } else {
        this.asset.set(undefined);
      }
    });
  }

  async loadAssets(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const assets = await this.api.listAssets();
      this.assets.set(assets);
      if (this.selectedAssetId() === undefined) {
        this.selectedAssetId.set(defaultPreflightAssetId(assets, this.settings.flyAssetId()));
      }
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadAsset(assetId: string): Promise<void> {
    try {
      this.asset.set(await this.api.getAsset(assetId));
    } catch {
      // Best-effort enrichment, not a user-initiated action — the checklist below just degrades to
      // every row "unknown" (`derivePreflight`'s own honest-absence contract) rather than a toast.
      this.asset.set(undefined);
    }
  }

  selectAsset(assetId: string): void {
    this.selectedAssetId.set(assetId || undefined);
  }
}
