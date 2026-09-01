import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { groundedBannerText, groundingBlocker } from '../../core/readiness/readiness-logic';
import type { ReadinessReport } from '../../core/api/models';

/**
 * Tracks one asset's grounding state for the Fly cockpit (docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * "S1 gate semantics", wave WB1) — a manager's custody-grounding or an open flight-blocking
 * `MaintenanceRecord` refuses arm/engage server-side; before WB1, nothing in `features/fly/**` said
 * a word about it, so the pilot just hit Arm and ate a 409. The asset's own `ReadinessReport` already
 * carries the refusal verbatim (`MAINTENANCE_GROUNDED:<kind>:<summary>`, S1's frozen contract) —
 * this store reads `GET /api/assets/{id}/readiness` once per selected asset and exposes the parsed
 * banner text `<vision-grounded-banner>` and `flight-command-panel.ts`'s Arm button both render.
 *
 * **Deliberately its own class, not folded into `CockpitFacade`.**
 * `core/telemetry/preflight-readiness-independence.spec.ts` (drone-onboarding wave O6) asserts
 * `cockpit-facade.ts` never references the readiness API at all — a guardrail for the *live-telemetry*
 * preflight checklist (`derivePreflight`/`<vision-preflight-checklist>`), which must render identically
 * whether or not `GET .../readiness` exists, is disabled, or errors (the default deployment has
 * onboarding probing off, D17). Grounding is a genuinely separate concern from that checklist — a
 * different signal, a different UI surface — but the guard scans the whole `cockpit-facade.ts` file
 * for simplicity, so growing that concern there would trip on a design invariant it isn't actually
 * violating. A small, single-purpose store is also just the correct shape for one fetch keyed on one
 * id — the same "one job" reasoning `GeoStore`/`WeatherStore` already follow, simplified further
 * since this has no live-transport/polling half (a one-shot fetch per asset selection is enough: a
 * manager's ground/release action is not an event this page needs to reflect within the same visit).
 *
 * **Component-provided, not `providedIn: 'root'`** — `CockpitPage` lists this in its own `providers`
 * (alongside `TelemetryStore`/`DetectionsStore`/`WeatherStore`/`GeoStore`/`CockpitFacade`), all one
 * injector, so `CockpitFacade` can `inject()` it too. `CockpitFacade` — not the component — drives
 * {@link track}/{@link reset} from its own `activeAssetId`-keyed `effect()`, exactly mirroring how
 * that same facade already drives `GeoStore#track`/`GeoStore#reset`.
 *
 * **Degrades honestly** (CLAUDE.md) — a failed read leaves {@link groundedReason} `undefined`
 * (never a fabricated "grounded" the read itself couldn't confirm, and never a blocked Arm button
 * over a read failure that has nothing to do with grounding).
 */
@Injectable()
export class GroundingStore {
  private readonly api = inject(VisionApi);

  private readonly reportSignal = signal<ReadinessReport | undefined>(undefined);
  private lastTrackedAssetId: string | undefined;

  /** The parsed grounding blocker, or `undefined` when this asset carries none (or the read hasn't resolved/failed). */
  readonly groundedBlocker = computed(() => {
    const report = this.reportSignal();
    return report ? groundingBlocker(report.blockers) : undefined;
  });

  /** One-line grounded reason — the same wording `readiness.html`'s per-asset page renders, so the cockpit and that page never drift. `undefined` when this asset isn't grounded. */
  readonly groundedReason = computed(() => {
    const blocker = this.groundedBlocker();
    return blocker ? groundedBannerText(blocker) : undefined;
  });

  /** Starts tracking `assetId` — a no-op when unchanged (mirrors `GeoStore#track`'s identical guard). */
  track(assetId: string): void {
    if (this.lastTrackedAssetId === assetId) {
      return;
    }
    this.lastTrackedAssetId = assetId;
    this.reportSignal.set(undefined);
    void this.load(assetId);
  }

  /** Stops tracking and clears the latest report (mirrors `GeoStore#reset`) — called when no asset is selected. */
  reset(): void {
    this.lastTrackedAssetId = undefined;
    this.reportSignal.set(undefined);
  }

  private async load(assetId: string): Promise<void> {
    try {
      const report = await this.api.assetReadiness(assetId);
      if (this.lastTrackedAssetId === assetId) {
        this.reportSignal.set(report);
      }
    } catch (error) {
      console.warn('[grounding-store] could not load the readiness report — grounded banner stays honest', { assetId, error });
      if (this.lastTrackedAssetId === assetId) {
        this.reportSignal.set(undefined);
      }
    }
  }
}
