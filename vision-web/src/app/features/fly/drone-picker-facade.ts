import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { PollScheduler } from '../../core/poll-scheduler';
import { sortAssetsForPicker } from './fly-logic';
import type { AssetSummary } from '../../core/api/models';

/** Asset list re-read at this cadence — keeps a still-open picker's cards fresh (a card flipping
 * Offline→Streaming while the operator is looking at it). Unchanged from the pre-split page's own
 * `ASSET_POLL_INTERVAL_MS`. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/**
 * `DronePickerPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/fly`, the drone chooser
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12, docs/extracts/design/01-fly.md). Split out of the old combined
 * `FlyFacade` (docs/plans/done/MVP3-PLAN.md §C-b) when the cockpit gained its own addressable route
 * (`/fly/:assetId`) — see `cockpit-facade.ts`'s own doc comment for the cockpit's half.
 *
 * **Deliberately thin**: every "does this visit need the picker at all" decision now happens one
 * layer up, in `fly-redirect-guard.ts`, before this component (and therefore this facade) ever
 * mounts — a bare `/fly` navigation only ever gets here once the guard has already confirmed there
 * is genuinely nothing to skip straight into. So unlike the old `FlyFacade#initPicker`, this facade
 * holds no `?asset=`/remembered-asset resolution, no redirect, nothing route-input-shaped at all —
 * only the picker grid's own list-and-render concern.
 *
 * **No `EventsStore` activation** (unlike the old combined page, which activated it regardless of
 * which branch was showing): the picker renders no events ticker — only `CockpitPage` does — and
 * with the guard now skipping this page entirely whenever there's a live remembered drone, a bare
 * picker visit is typically brief. Narrowing this page's own dependencies to what it actually
 * displays is a deliberate simplification of the split, not an oversight.
 */
@Injectable()
export class DronePickerFacade {
  private readonly api = inject(VisionApi);

  /** Skeleton card count while the first `listAssets()` call is in flight. */
  readonly skeletonRows = [1, 2, 3] as const;
  readonly pickerAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  readonly pickerError = signal(false);
  readonly orderedPickerAssets = computed(() => sortAssetsForPicker(this.pickerAssets() ?? []));

  constructor() {
    void this.refresh();

    const scheduler = inject(PollScheduler);
    const stopPoll = scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
    inject(DestroyRef).onDestroy(stopPoll);
  }

  retryPicker(): void {
    void this.refresh();
  }

  private async refresh(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.pickerAssets.set(assets);
      this.pickerError.set(false);
    } catch {
      // Only the *first* load is a genuine dead end (nothing to show at all) — a background
      // refresh hiccup on an already-populated grid stays on the stale list silently, matching
      // every other poller in this app.
      if (this.pickerAssets() === undefined) {
        this.pickerError.set(true);
      }
    }
  }
}
