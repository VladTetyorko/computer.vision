import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SidePanel } from '../../shared/ui/side-panel';
import { EventRow } from '../../shared/ui/event-row';
import { relativeTimeLabel } from '../../core/events/events-logic';
import { PollScheduler } from '../../core/poll-scheduler';
import type { WallActivityRow } from './wall-logic';

/** Keeps `relativeTime` advancing while the drawer stays open — the same 1s-multiple tick every
 *  other relative-time surface in this app runs, on its own local clock (`architecture.spec.ts`'s
 *  non-routed-child carve-out lets this component DI-share `PollScheduler`, which is not a
 *  `*Store`, without a facade of its own). */
const CLOCK_TICK_MS = 1_000;

/**
 * The wall's own activity drawer (docs/plans/active/WALL-FLOW-PLAN.md §3.2 A4, wave W4) — the
 * replacement for the permanently-mounted, fleet-wide events rail this page used to carry (D14).
 * `[rows]` already comes pre-scoped to this wall's own streams, inside `ACTIVITY_WINDOW_MS`
 * (`wallActivityRows`, `wall-logic.ts`) — every row's `sourceLabel` is therefore its tile's own
 * asset name by construction; `Removed device · …` cannot occur here (the fix for D15/D16).
 *
 * A row never navigates (D17) — `(rowActivated)` hands `wall.ts` the row's `streamId`, which the
 * page forwards to `WallFacade.focus()`, the same drill-in a tile click itself performs. The drawer
 * stays open while a row is clicked (A4: "not mutually exclusive" with the focus view), so a
 * watcher can step through several rows in sequence.
 */
@Component({
  selector: 'vision-wall-activity',
  imports: [SidePanel, EventRow, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wall-activity.html',
  styleUrl: './wall-activity.css',
})
export class WallActivity {
  readonly rows = input.required<readonly WallActivityRow[]>();

  readonly closed = output<void>();
  readonly rowActivated = output<string>();

  protected readonly nowSignal = signal(Date.now());

  protected relativeTime(row: WallActivityRow): string {
    return relativeTimeLabel(row.event.lastSeen, this.nowSignal());
  }

  constructor() {
    const stop = inject(PollScheduler).schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(stop);
  }
}
