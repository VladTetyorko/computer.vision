import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { formatConfidence } from '../../core/events/events-logic';
import type { DetectionEvent } from '../../core/api/models';

/**
 * `vision-event-row` — one detection event, extracted from `shared/ui/events-rail.*`
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4/Wave 3, docs/extracts/design/08-alerts.md's refactor list item 1) so the
 * header bell's dropdown, the Wall rail, and `/monitor/alerts` render the identical row instead of
 * three independent copies drifting apart. Purely presentational — every field is an input, every
 * click is reported via `activated` and left for the host to interpret (select vs. navigate).
 *
 * **Two density variants, one visual language.** The state dot, the conditional `OPEN` chip
 * (`08-alerts.md`: "every row says CLOSED… noise — render a chip only when it differs from the
 * norm"), the capitalized label, and the confidence/source/time fields are identical data and
 * identical styling tokens in both variants — only their arrangement differs, and only because the
 * two hosts genuinely have different width budgets to work with (measured, not assumed):
 *
 * - **`dense` (`/monitor/alerts`'s own two-pane list — its list column is several hundred px wide)**
 *   — a single line, ~32px tall: severity dot · label · confidence · source · relative time. No
 *   action text — Alerts' row *selects* (`?sel=`), it never navigates, so promising "Details ›"
 *   would be a lie (`08-alerts.md`: "the row is the click target; delete the per-row Details › link").
 * - **default (the Wall rail / the header bell's dropdown — 300px / 22rem hosts respectively,
 *   `wall.css#.events-rail`/`notification-bell.css#.bell-dropdown`)** — the pre-existing two-line
 *   layout survives: label/time/action on top, source+confidence below. A single dense line
 *   at that width would either crush the source name to an unreadable sliver or overflow the host's
 *   own card — this component picks the layout that fits its host rather than forcing one shape on
 *   both, which is what "share one visual language" means here: same colors, same chip logic, same
 *   type scale, density adapted to the room available (mirrors `vision-page-bar`'s own "the same
 *   values throughout, presented at the width each page actually has" principle).
 *
 * **One status indicator per row, not two (docs/plans/active/OPERATOR-UX-7-PLAN.md finding W1).**
 * The default variant's top line used to render the always-on severity dot *and* the `OPEN` chip
 * together — reproduced live on `/wall`: the selected row showed a red `OPEN` chip and its own label
 * truncated to `P…`, both non-flexible elements crowding out the label's `flex: 1; min-width: 0`
 * (frontend-style §5's "one chip per row" already named the dot+chip combination as exactly the kind
 * of double indicator to avoid). The chip now *replaces* the dot when `OPEN` (the dot renders only
 * for the norm, `CLOSED`) and sits after the time rather than at the row's front, so the label and
 * time keep their usual position regardless of state — see `event-row.html`'s own comment on the
 * default branch. The `dense` variant (`/monitor/alerts`, out of this finding's own reproduction) is
 * unchanged.
 */
@Component({
  selector: 'vision-event-row',
  templateUrl: './event-row.html',
  styleUrl: './event-row.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventRow {
  readonly event = input.required<DetectionEvent>();
  /**
   * A friendly source name — `events-logic.ts#describeEventSource`'s own result. Resolved by the
   * host (it needs `FleetStore`'s devices/streams snapshots, which this presentational component
   * never injects — see `architecture.spec.ts`'s carve-out for non-routed shared components: this
   * one stays dumb by not injecting a store at all, not just by going through a facade).
   */
  readonly sourceLabel = input.required<string>();
  readonly relativeTime = input.required<string>();
  /** Whether the row responds to a click. `false` renders it visibly inert (`disabled`), never a dead click. */
  readonly clickable = input(true);
  /** `08-alerts.md`'s dense single-line variant. Default `false` keeps the rail/bell's two-line layout. */
  readonly dense = input(false);
  /** Two-pane's own "this is the selected row" highlight (Alerts only — always `false` elsewhere). */
  readonly selected = input(false);
  /**
   * The row's own explicit verb (docs/plans/done/UX-REWORK-PLAN.md U-a2 §2.6 — a hover style alone never says
   * what a click does). Omit (or pass `null`) to render none — dense rows always omit it.
   */
  readonly actionLabel = input<string | null>(null);

  readonly activated = output<void>();

  protected readonly formatConfidence = formatConfidence;
}
