import { Injectable, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { GroundingPageActions } from './state/grounding.actions';
import { groundingFeature } from './state/grounding.reducer';

/**
 * Tracks one asset's grounding state for the Fly cockpit (docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * "S1 gate semantics", wave WB1) — a manager's custody-grounding or an open flight-blocking
 * `MaintenanceRecord` refuses arm/engage server-side; before WB1, nothing in `features/fly/**` said
 * a word about it, so the pilot just hit Arm and ate a 409. The asset's own `ReadinessReport` already
 * carries the refusal verbatim (`MAINTENANCE_GROUNDED:<kind>:<summary>`, S1's frozen contract) —
 * this facade reads `GET /api/assets/{id}/readiness` once per selected asset and exposes the parsed
 * banner text `<vision-grounded-banner>` and `flight-command-panel.ts`'s Arm button both render.
 *
 * **Over an NgRx slice since docs/plans/done/NGRX-MIGRATION-PLAN.md wave N8** (replacing
 * `GroundingStore`, deleted): `features/fly/state/grounding.*`, page-scoped and registered by
 * `fly.page-routes.ts`. Every public member below keeps that class's exact name and contract, so
 * `CockpitFacade` changed only its `inject()` line.
 *
 * **Deliberately its own class, not folded into `CockpitFacade`.**
 * `core/telemetry/preflight-readiness-independence.spec.ts` (drone-onboarding wave O6) asserts
 * `cockpit-facade.ts` never references the readiness API at all — a guardrail for the *live-telemetry*
 * preflight checklist (`derivePreflight`/`<vision-preflight-checklist>`), which must render identically
 * whether or not `GET .../readiness` exists, is disabled, or errors (the default deployment has
 * onboarding probing off, D17). Grounding is a genuinely separate concern from that checklist — a
 * different signal, a different UI surface — but the guard scans the whole `cockpit-facade.ts` file
 * for simplicity, so growing that concern there would trip on a design invariant it isn't actually
 * violating.
 *
 * **Component-provided, not `providedIn: 'root'`** — `CockpitPage` lists this in its own `providers`
 * (alongside `TelemetryFacade`/`DetectionsFacade`/`WeatherFacade`/`GeoFacade`/`CockpitFacade`), all
 * one injector, so `CockpitFacade` can `inject()` it too. That page-provided shape is also exactly
 * what makes the slice route-registrable rather than root-registered (wave N-split's eligibility
 * rule). `CockpitFacade` — not the component — drives {@link track}/{@link reset} from its own
 * `activeAssetId`-keyed `effect()`, exactly mirroring how that same facade already drives
 * `GeoFacade#track`/`GeoFacade#reset`.
 *
 * **Degrades honestly** (CLAUDE.md) — a failed read leaves {@link groundedReason} `undefined`
 * (never a fabricated "grounded" the read itself couldn't confirm, and never a blocked Arm button
 * over a read failure that has nothing to do with grounding).
 */
@Injectable()
export class GroundingFacade {
  private readonly store = inject(Store);

  private readonly trackedAssetId = this.store.selectSignal(groundingFeature.selectAssetId);

  /** The parsed grounding blocker, or `undefined` when this asset carries none (or the read hasn't resolved/failed). */
  readonly groundedBlocker = this.store.selectSignal(groundingFeature.selectGroundedBlocker);

  /** One-line grounded reason — the same wording `readiness.html`'s per-asset page renders, so the cockpit and that page never drift. `undefined` when this asset isn't grounded. */
  readonly groundedReason = this.store.selectSignal(groundingFeature.selectGroundedReason);

  /**
   * Starts tracking `assetId` — a no-op when unchanged (mirrors `GeoFacade#track`'s identical guard).
   *
   * The guard is here rather than in the reducer because `grounding.effects.ts#read$` reacts to the
   * *action*: deduping in the reducer would still have refetched on every `effect()` re-run.
   */
  track(assetId: string): void {
    if (this.trackedAssetId() === assetId) {
      return;
    }
    this.store.dispatch(GroundingPageActions.trackRequested({ assetId }));
  }

  /** Stops tracking and clears the latest report (mirrors `GeoFacade#reset`) — called when no asset is selected. */
  reset(): void {
    this.store.dispatch(GroundingPageActions.resetRequested());
  }
}
