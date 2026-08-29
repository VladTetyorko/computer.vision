import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { Stat } from '../../shared/ui/stat';
import { PreflightFacade } from './preflight-facade';

/**
 * `/operate/preflight` — the fleet readiness board (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1,
 * wave O6): every drone the caller may see, its overall verdict (`GO`/`NO_GO`/`UNKNOWN`), and a
 * compact rollup of which platform features aren't ready — sourced from `GET /api/fleet/readiness`.
 * Replaces this page's previous single-drone-picker-plus-live-checklist card outright (this wave's
 * own "What to build" item 3) — the Fly cockpit still carries that live, telemetry-driven checklist
 * unmodified (`<vision-preflight-checklist>`/`derivePreflight`); this board is a different,
 * server-evaluated question about the last-observed vehicle link, not a live instrument.
 *
 * Each row links to `/assets/:assetId/readiness` (`features/readiness`) for the full per-feature
 * report — this page is deliberately a compact rollup, not the detail surface (`dataviz`'s own
 * "compact rollups over per-row chip clutter" — eleven per-row status chips would be exactly that
 * clutter).
 *
 * No role gate — reading a drone's own readiness is not a management action, same openness as
 * `/fly`/the old single-card page it replaces.
 *
 * **Advisory only (OQ3, docs/plans/active/DRONE-ONBOARDING-PLAN.md §10):** `NO_GO` sorts first and
 * reads in the danger tone, but nothing on this page is ever hidden or disabled on a verdict.
 *
 * **Triages like `/fly`/Command, and never fabricates a checklist for a row with nothing to check
 * (docs/plans/active/OPERATOR-UX-7-PLAN.md finding P1, §2 P1, wave W1).** Rows split into **Your
 * vehicles** / **Simulated** groups (same `vision.fly.hideSimulated` toggle those two pages already
 * use); a row that has never been probed (or owns no `TELEMETRY`-capable device at all) reads a plain
 * `Not probed yet`/`No telemetry device` label instead of a muted `Unknown` chip, with its attention
 * cell `—` — see `preflight-facade.ts`'s own doc comment for the full reasoning.
 */
@Component({
  selector: 'vision-preflight',
  imports: [RouterLink, EmptyState, PageBar, Stat],
  templateUrl: './preflight.html',
  styleUrl: './preflight.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [PreflightFacade],
})
export class PreflightPage {
  protected readonly facade = inject(PreflightFacade);
}
