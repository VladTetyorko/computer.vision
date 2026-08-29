import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar, pluralize } from '../../shared/ui/page-bar/page-bar';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import { ReplayPage } from './replay';
import { ReplayLibraryFacade } from './replay-library-facade';
import { formatUsageDuration, usageStatus, type UsageStatus } from './replay-library-logic';
import type { UsageSummary } from '../../core/api/models';

/**
 * `/replay` — the replay library (docs/extracts/design/10-replay.md, Wave 4, closing F8: "Scrub any
 * finished flight, frame by frame" used to land on `ReplayPage`'s own bare "Replay unavailable —
 * No usage specified." empty state, a nav entry advertising a feature that had never existed).
 * This is now the routed component for the flat `replay` path (`replay.routes.ts`) — `ReplayPage`
 * keeps its own separate, unmodified route (`assets/:assetId/replay/:usageId`).
 *
 * **The deep link keeps behaving exactly as today (docs/plans/done/OPS-CORE-PLAN.md §Q1) — by literally
 * reusing the same component, not reimplementing its logic.** `/replay?asset=…&usage=…&t=…` (the
 * event → replay deep link's own shipped target — `shared/ui/notification-bell.ts`,
 * `features/wall/wall-facade.ts`, `features/alerts/alerts-facade.ts`, all grep-verified live call
 * sites) still needs to land straight on the full cockpit, not this library. `usageIdParam`
 * (aliased `'usage'`, the exact same alias `ReplayPage` itself already declares) is this
 * component's own signal for "was a deep-link target usage given at all" — when it is, the
 * template below renders `<vision-replay>` directly, forwarding the three identically-aliased
 * inputs straight through as plain property bindings. This is not a second route binding (Angular
 * only lets the *routed* component receive one via `withComponentInputBinding()`, and this
 * component is now that one for `/replay`) — it's the ordinary "parent passes an `@Input`-style
 * value to a child" binding, using the same public (aliased) names so nothing about the value
 * itself changes shape on the way through. `ReplayPage`/`ReplayFacade` are untouched by this wave:
 * every value/behavior the deep link relies on is exactly the code that already shipped it, just
 * reached one component deeper — see this task's own report for how that was verified live.
 *
 * **The library itself** (`usageIdParam` absent — the ordinary "Monitor → Replay library" click,
 * or a bare `/replay`): `ReplayLibraryFacade` owns the `GET /api/usages` read model, the asset/
 * time-range filters, and the two-pane `?sel=` selection (`shared/ui/two-pane/two-pane.ts`, mirrors
 * `AssetsFacade`'s own identical convention — see that class's doc comment). Selecting a row
 * previews the flight's own already-loaded summary fields (asset, started, duration, samples) with
 * **no extra fetch** — nothing here joins a thumbnail or an event-density sparkline. Both were in
 * the design doc's original sketch and both are deliberately scoped out: nothing stores a
 * per-usage frame (the only image endpoint returns a stream's *current* frame, which would
 * fabricate a value for a finished flight — the same trap that stopped the Alerts frame preview in
 * wave 3), and detection events aren't joined to usages. "Open replay ›" hands off to the real
 * cockpit — `ReplayPage`, unmodified, which already redirects an open usage to a "Watch live"
 * notice on its own, so this library never needs to duplicate that judgment call for its own
 * still-flying rows.
 */
@Component({
  selector: 'vision-replay-library',
  imports: [FormsModule, RouterLink, ReplayPage, PageBar, TwoPane, EmptyState],
  templateUrl: './replay-library.html',
  styleUrl: './replay-library.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReplayLibraryFacade],
})
export class ReplayLibraryPage {
  /** The event → replay deep link's own query params (docs/plans/done/OPS-CORE-PLAN.md §Q1) — the same three
   *  aliases `ReplayPage` itself declares; see this class's own doc comment for why they're
   *  forwarded straight through rather than re-derived. */
  readonly assetIdParam = input<string | undefined>(undefined, { alias: 'asset' });
  readonly usageIdParam = input<string | undefined>(undefined, { alias: 'usage' });
  readonly deepLinkOffsetParam = input<string | undefined>(undefined, { alias: 't' });

  /** `?sel=<usageId>` — the library's own two-pane selection (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4),
   *  bound the same way `AssetsPage`'s own `sel` input is (see that component's doc comment): only
   *  a component can receive a route input, so this constructor's `effect()` is what forwards it
   *  into the facade. */
  readonly sel = input<string | undefined>(undefined);

  protected readonly facade = inject(ReplayLibraryFacade);
  protected readonly pluralize = pluralize;
  protected readonly formatUsageDuration = formatUsageDuration;

  constructor() {
    effect(() => {
      this.facade.selectedId.set(this.sel());
    });
  }

  /** `"04 Aug 10:36"` — day + month + local time, enough to tell same-day flights apart without a
   *  full ISO timestamp; the detail pane's "Ended" row reuses this verbatim for `endedAt`. */
  protected startedLabel(iso: string): string {
    return new Date(iso).toLocaleString(undefined, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
  }

  /** The row/detail-pane's one status derivation (OPERATOR-UX-5-PLAN.md finding U1, §2 U1) — a thin
   *  wrapper supplying `Date.now()` (the one non-pure input `usageStatus` itself never reads), so
   *  both surfaces below call through the identical logic rather than re-deriving "is this flying"
   *  independently. */
  protected usageStatus(usage: UsageSummary): UsageStatus {
    return usageStatus(usage, Date.now());
  }
}
