import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { IconButton } from '../icon-button';

/**
 * `vision-two-pane` — the list-plus-detail shell behind Wave 3 of docs/plans/done/NAV-IA-REDESIGN-PLAN.md
 * (§2.4), generalizing the split `features/command/**` already proves works (asset list beside the
 * live map) so `/assets`, `/devices`, `/monitor/alerts`, `/activity` and `/manage/roster` stop
 * answering "tell me about this row" by navigating away from the list (F6).
 *
 * **Why not `shared/ui/side-panel.ts`.** That component is a `position: fixed` overlay drawer, which
 * is the right shape for Command's tool panels and asset-detail's drill-ins — transient surfaces
 * over a page whose content you are not comparing against. It is the wrong shape for list triage:
 * an overlay covers the very rows the user is working through, so every selection hides the context
 * that made it meaningful. This component **docks** the detail beside the list on wide viewports and
 * only falls back to an overlay when there genuinely isn't room.
 *
 * **Layout.** A two-column grid, `1fr` + a fixed `--two-pane-detail-w`. The detail column exists only
 * while `detailOpen()` — a closed pane collapses the track rather than leaving a gap, so an unselected
 * list is exactly as wide as it was before this component wrapped it. Each pane scrolls
 * independently (`min-height: 0` on both, the flex/grid overflow gotcha this codebase has already been
 * bitten by — see `app.css`'s own sidebar notes), so a long list never pushes the detail off-screen.
 *
 * **Below `--two-pane-bp` (1200px)** the detail becomes a right-docked overlay with a scrim, because
 * a 400px pane beside a list stops being readable much below that. Pure CSS — no JS breakpoint
 * tracking, no `matchMedia` listener to keep in sync, matching how `app-sidebar.css` handles its own
 * three responsive tiers.
 *
 * **Selection lives in the URL, not here.** Hosts drive `detailOpen` from a `?sel=<id>` query param
 * so a selection survives refresh, Back and sharing (§2.4). This component is deliberately ignorant
 * of *what* is selected; it only reports that the user asked to dismiss the pane (`detailClose`), and
 * the host clears the param. That is the same "mounting is the host's job" split `side-panel.ts`
 * already documents, and it keeps this component free of router knowledge.
 *
 * Projection slots, both required in practice:
 * - `[twoPaneList]` — the list/table. Always rendered.
 * - `[twoPaneDetail]` — the detail body. Hosts should render it only while `detailOpen` is true.
 */
@Component({
  selector: 'vision-two-pane',
  imports: [IconButton],
  templateUrl: './two-pane.html',
  styleUrl: './two-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TwoPane {
  /** Whether the detail pane is showing. Drive this from the host's own `?sel=` query param. */
  readonly detailOpen = input(false);
  /** Accessible name for the detail region — usually the selected row's own label. */
  readonly detailLabel = input<string>('Details');

  /** The user asked to dismiss the pane (close button, Esc, or scrim click). Host clears `?sel=`. */
  readonly detailClose = output<void>();
}
