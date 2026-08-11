import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { DronePickerFacade } from './drone-picker-facade';
import { lastSeenLabel, positionLabel, streamStateLabel } from './fly-logic';
import type { AssetSummary } from '../../core/api/models';

/**
 * `/fly` — the drone chooser (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12, docs/extracts/design/01-fly.md). Split
 * out of the old combined `FlyPage` (docs/plans/done/MVP3-PLAN.md §C-b), which switched between this and the
 * cockpit *internally*, with no URL change — `fly-redirect-guard.ts` now decides, before this
 * component ever mounts, whether a given `/fly` visit should redirect straight into a cockpit
 * instead (a live remembered drone, or an explicit `?asset=` drill-down); this component only ever
 * renders once that guard has confirmed there is genuinely something to ask.
 *
 * **Layered per docs/plans/done/UI-ARCHITECTURE-PLAN.md**: injects only `DronePickerFacade`, mirroring every
 * other routed page — see `architecture.spec.ts`'s own `ROUTED_PAGES` list (this page's own entry
 * replaces the old combined `fly/fly`).
 *
 * A picker card is a plain `routerLink` into `/fly/:assetId` (no facade command needed for the
 * click itself — entering a cockpit is now just a normal navigation, not an internal state flip);
 * `CockpitFacade` records the pick as "remembered" once that cockpit's own `getAsset()` actually
 * resolves, not here (see that facade's own doc comment for why).
 */
@Component({
  selector: 'vision-drone-picker',
  imports: [RouterLink, PageBar],
  templateUrl: './drone-picker.html',
  styleUrl: './drone-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DronePickerFacade],
})
export class DronePickerPage {
  protected readonly facade = inject(DronePickerFacade);

  // --- Picker card facts (docs/plans/done/UX-REWORK-PLAN.md §U-a2 §3 — the asset card rebuild) -----------
  // Pure, stateless, called from the picker's own `@for` — no facade state needed beyond the loop
  // argument itself, same idiom as `asset-detail.ts`'s own `barLabel`/`detailPairs`, kept on the
  // component rather than the facade for the identical reason `fly.ts` used to.

  protected assetStreamState(asset: AssetSummary): 'Streaming' | 'Offline' {
    return streamStateLabel(asset.status);
  }

  protected assetLastSeen(asset: AssetSummary): string | undefined {
    return lastSeenLabel(asset.lastUsedAt, Date.now());
  }

  protected assetPosition(asset: AssetSummary): string | undefined {
    return positionLabel(asset.lastKnownPosition);
  }
}
