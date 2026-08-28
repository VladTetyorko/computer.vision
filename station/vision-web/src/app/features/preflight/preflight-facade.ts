import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { readinessCounts, verdictLabel, verdictTone, type ReadinessCounts } from '../../core/readiness/readiness-logic';
import { applyVerdictFilter, blockerSummary, emptyFilterTitle, sortWorstFirst, type BlockerSummary } from './preflight-logic';
import type { ReadinessRow, ReadinessVerdict } from '../../core/api/models';

/**
 * `PreflightPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/operate/preflight`, the fleet
 * readiness board (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1, wave O6). Replaces this page's
 * previous single-drone-picker-plus-live-checklist card: the Fly cockpit already carries that live,
 * telemetry-driven check (`<vision-preflight-checklist>` / `core/telemetry/flight-state-logic.ts#derivePreflight`,
 * both unmodified by this wave — see that module's own doc comment for the split). This board answers
 * a different question — "does each drone's last-observed vehicle link satisfy what this platform's
 * own features need" — sourced entirely from `GET /api/fleet/readiness`, one row per asset the
 * caller's scope includes.
 *
 * Injects `VisionApi` directly rather than growing a shared store — the board's list is page-local,
 * single-consumer state, the same `ModelsFacade`/`DatasetDetailFacade` shape ("a routed page's facade
 * may talk to a service directly, not only a store").
 *
 * **No disabled state.** Unlike the onboarding wizard's own Verify step, `GET /api/fleet/readiness`
 * is never gated by `vision.onboarding.probe.enabled` (verified against `ReadinessController` —
 * no flag check anywhere in it) — an asset that was never probed simply reports every feature
 * `UNKNOWN`, itself an honest, renderable row. The only failure mode this facade handles is a
 * genuine read failure (network down, a 5xx), surfaced via {@link error} the same way every other
 * loading page in this app does.
 *
 * **Advisory only (OQ3, docs/plans/active/DRONE-ONBOARDING-PLAN.md §10 — unanswered by the plan, resolved
 * here pending an operator's own answer):** `NO_GO` sorts first and reads in the danger tone, but no
 * row, link, or control is ever hidden or disabled on a verdict.
 *
 * **P1 (docs/plans/active/OPERATOR-UX-3-PLAN.md finding P1 + §2 P1, 2026-08-28):** the three verdict
 * stat cards are now this board's own filter — {@link verdictFilter} is page-local selection state
 * (the same bare-signal-in-the-facade shape `alerts-facade.ts`'s `labelFilter`/`assetFilter` already
 * use; it isn't a mutually-exclusive *overlay*, so it doesn't belong in a `UiStore` group, and
 * `core/ui/architecture.spec.ts` only forbids an `Open`/`Menu`/`Confirm`/`Editing`-named bare signal
 * on the routed page component itself, not facade state). {@link rows} sorts worst-first
 * (`preflight-logic.ts#sortWorstFirst`, replacing `readiness-logic.ts#sortReadinessRows` for this
 * page only); {@link filteredRows} applies the selected verdict on top, unsorted-again.
 */
@Injectable()
export class PreflightFacade {
  private readonly api = inject(VisionApi);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  private readonly rowsSignal = signal<readonly ReadinessRow[]>([]);

  /** The verdict card currently selected as a filter, or `undefined` when none is (every row shows). */
  readonly verdictFilter = signal<ReadinessVerdict | undefined>(undefined);

  /** `NO_GO` first, then `UNKNOWN` (most failing checks first within it), then `GO` — see {@link sortWorstFirst}'s own doc comment. */
  readonly rows = computed(() => sortWorstFirst(this.rowsSignal()));
  readonly hasAnyAssets = computed(() => this.rowsSignal().length > 0);
  readonly counts = computed<ReadinessCounts>(() => readinessCounts(this.rowsSignal()));

  /** {@link rows}, narrowed to the selected {@link verdictFilter} — equal to {@link rows} itself when no card is selected. */
  readonly filteredRows = computed(() => applyVerdictFilter(this.rows(), this.verdictFilter()));

  /** `vision-empty`'s title for the "this verdict filter matched nothing" state — only ever read once {@link hasAnyAssets} is already true, so this is always a *filtered*-empty message. */
  readonly filteredEmptyTitle = computed(() => {
    const verdict = this.verdictFilter();
    return verdict === undefined ? '' : emptyFilterTitle(verdict);
  });

  /** Thin passthroughs so `preflight.html` never re-derives a verdict/status this facade already fetched — same "expose the pure function as a bound field" idiom `onboarding-facade.ts#connectMethodLabels`/`detailsSummary` use. */
  readonly verdictLabel = verdictLabel;
  readonly verdictTone = verdictTone;
  readonly blocker = (row: ReadinessRow): BlockerSummary => blockerSummary(row.features);

  constructor() {
    void this.refresh();
  }

  /** A stat card's own click: selecting the same verdict again clears the filter. */
  toggleVerdictFilter(verdict: ReadinessVerdict): void {
    this.verdictFilter.update((current) => (current === verdict ? undefined : verdict));
  }

  clearVerdictFilter(): void {
    this.verdictFilter.set(undefined);
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const response = await this.api.fleetReadiness();
      this.rowsSignal.set(response.assets);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }
}
