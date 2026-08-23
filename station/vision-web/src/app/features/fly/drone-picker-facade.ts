import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { AuthStore } from '../../core/auth/auth-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { LiveStore } from '../../core/live/live-store';
import { isLiveAvailable } from '../../core/live/live-fallback-logic';
import { pickerEmptyStateCopy, sortAssetsForPicker } from './fly-logic';
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
 *
 * **`AuthStore` (docs/plans/done/OPS-UX-PLAN.md §2 A2)** — the one addition this wave makes. `emptyState`
 * feeds `drone-picker.html`'s empty leg entirely from `fly-logic.ts#pickerEmptyStateCopy`: `topRole`
 * decides the wording (PILOT vs ADMIN/MANAGER), `memberships` names the PILOT's group. No new HTTP
 * call — `listAssets()` is already visibility-scoped, so an empty response for a PILOT already means
 * "nothing assigned to you" (see that function's own doc comment).
 *
 * **Poll gated on `LiveStore` (docs/plans/done/SCALE-100-PLAN.md §5 S6, item 1)** — mirrors
 * `core/fleet/fleet-store.ts#FleetStore`'s identical transport-switch effect: the 5s poll pauses
 * while `LiveStore` reports an open connection and resumes, refetching immediately, the moment it
 * drops. The `fleet` topic (`List<AssetSummaryResponse>`) is exactly this picker's own domain, so a
 * snapshot arriving while the poll is paused is applied straight onto {@link pickerAssets} — the
 * grid stays live-fresh rather than merely frozen at whatever the poll last fetched, the same
 * "apply the moment one arrives, independent of poll state" idiom `FleetStore.applyDevicesSnapshot`
 * uses for its own `devices` topic.
 */
@Injectable()
export class DronePickerFacade {
  private readonly api = inject(VisionApi);
  private readonly auth = inject(AuthStore);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

  /** Skeleton card count while the first `listAssets()` call is in flight. */
  readonly skeletonRows = [1, 2, 3] as const;
  readonly pickerAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  readonly pickerError = signal(false);
  readonly orderedPickerAssets = computed(() => sortAssetsForPicker(this.pickerAssets() ?? []));

  /** The empty leg's whole view model — see this class's own doc comment. */
  readonly emptyState = computed(() =>
    pickerEmptyStateCopy(this.auth.user()?.topRole, this.auth.user()?.memberships ?? []),
  );

  /** `null` until the poll is actually paused/resumed for the first time — see `applyTransport`. */
  private stopPollFn: (() => void) | null = null;

  constructor() {
    void this.refresh();
    this.stopPollFn = this.schedulePoll();

    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    effect(() => {
      const snapshot = this.live.fleet();
      if (snapshot !== undefined) {
        this.pickerAssets.set(snapshot);
        this.pickerError.set(false);
      }
    });

    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  retryPicker(): void {
    void this.refresh();
  }

  /**
   * Switches whether the local 5s poll is running — mirrors `FleetStore#applyTransport` exactly.
   * `liveAvailable` pauses the poll; its absence resumes it, refetching immediately first (the grid
   * may be stale from however long the live connection was up). A no-op when the poll is already in
   * the requested state (`stopPollFn`'s own nullness tracks that).
   */
  private applyTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      this.stopPolling();
      return;
    }
    if (this.stopPollFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollFn = this.schedulePoll();
  }

  private schedulePoll(): () => void {
    return this.scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
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
