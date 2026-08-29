import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { verdictLabel, verdictTone } from '../../core/readiness/readiness-logic';
import {
  applyVerdictFilter,
  blockerSummary,
  boardCounts,
  buildBoardGroups,
  buildFleetVehicleRows,
  emptyFilterTitle,
  vehicleStatusOverride,
  type BlockerSummary,
  type BoardCounts,
  type BoardFilter,
  type FleetVehicleRow,
} from './preflight-logic';
import type { AssetDetails, ReadinessRow } from '../../core/api/models';
import type { PickerGroups } from '../../core/fleet/triage-logic';

/** `hideSimulated`'s persisted key — deliberately the *same* key `/fly`'s picker and Command's rail
 * already use (docs/plans/active/OPERATOR-UX-7-PLAN.md P1, §2 P1: "the shared `vision.fly.hideSimulated`
 * flag"), so hiding simulated assets is one preference across every page that triages this way, not a
 * fourth copy of it. */
const HIDE_SIMULATED_KEY = 'vision.fly.hideSimulated';

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
 * `UNKNOWN`, itself an honest, renderable row. The only failure mode this facade's *primary* read
 * handles is a genuine read failure (network down, a 5xx), surfaced via {@link error} the same way
 * every other loading page in this app does.
 *
 * **Advisory only (OQ3, docs/plans/active/DRONE-ONBOARDING-PLAN.md §10 — unanswered by the plan, resolved
 * here pending an operator's own answer):** `NO_GO` sorts first and reads in the danger tone, but no
 * row, link, or control is ever hidden or disabled on a verdict.
 *
 * **P1 (docs/plans/active/OPERATOR-UX-3-PLAN.md finding P1 + §2 P1, 2026-08-28):** the three verdict
 * stat cards are this board's own filter — {@link verdictFilter} is page-local selection state (the
 * same bare-signal-in-the-facade shape `alerts-facade.ts`'s `labelFilter`/`assetFilter` already use;
 * it isn't a mutually-exclusive *overlay*, so it doesn't belong in a `UiStore` group, and
 * `core/ui/architecture.spec.ts` only forbids an `Open`/`Menu`/`Confirm`/`Editing`-named bare signal
 * on the routed page component itself, not facade state).
 *
 * **OPERATOR-UX-7 P1 (§2 P1, wave W1) — triage + honest never-probed/no-telemetry copy.** Fixes the
 * live-walkthrough finding that eighteen assets — a mix of real rovers, an IP camera and 14
 * simulated aircraft — all rendered as identical `Unknown` rows with a nonsensical `Map position +10`
 * attention cell, because every one of them had simply never been probed. Three changes, all in
 * `preflight-logic.ts` (see that file's own doc comments for the reasoning):
 * - {@link vehicleRows}/{@link boardGroups} triage the board into **Your vehicles** / **Simulated**
 *   groups, same as `/fly`/Command, via the shared {@link hideSimulated} flag.
 * - A row `isNeverProbedRow` is true for renders `Not probed yet` (or `No telemetry device`, once
 *   positive evidence says the asset owns no `TELEMETRY`-capable device) in place of the `Unknown`
 *   chip, and its attention cell is `—` — see {@link statusOverride}.
 * - {@link counts} gains `notProbed`, so the `Unknown` stat only ever counts a row `isNeverProbedRow`
 *   does *not* already claim (today always 0 — see `boardCounts`'s own doc comment).
 *
 * The triage/telemetry-device facts need `AssetDetails` (`category`/`status`/`lastUsedAt`/`devices`)
 * that `ReadinessRow` itself does not carry (verified against `models.ts` — see `preflight-logic.ts`'s
 * top-of-file doc comment) — {@link loadVehicleDetails} fans a `getAsset` read out over every row
 * exactly like `features/assets/assets-facade.ts#refreshAssets` already does for its own grid, and
 * degrades the same way: a failure never blocks or errors the page, it just leaves that read's rows
 * without the enrichment (safe fallbacks documented on `buildFleetVehicleRows`).
 */
@Injectable()
export class PreflightFacade {
  private readonly api = inject(VisionApi);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  private readonly rowsSignal = signal<readonly ReadinessRow[]>([]);
  private readonly detailsByIdSignal = signal<ReadonlyMap<string, AssetDetails>>(new Map());

  /** The verdict/NOT_PROBED card currently selected as a filter, or `undefined` when none is (every row shows). */
  readonly verdictFilter = signal<BoardFilter | undefined>(undefined);

  /** Whether the **Simulated** group's rows are collapsed — persisted per operator under the same key
   * `/fly`'s picker and Command's rail already use (`HIDE_SIMULATED_KEY`'s own doc comment). The group
   * header itself (with its own count) always stays visible; only the rows beneath it collapse. */
  readonly hideSimulated = signal(readPersistedFlag(HIDE_SIMULATED_KEY, false));

  readonly totalCount = computed(() => this.rowsSignal().length);
  readonly hasAnyAssets = computed(() => this.rowsSignal().length > 0);
  readonly counts = computed<BoardCounts>(() => boardCounts(this.rowsSignal()));

  /** Every row, triage-enriched (`preflight-logic.ts#FleetVehicleRow`) with whatever `AssetDetails`
   * {@link loadVehicleDetails} has resolved so far — degrades per-row, never blocks. */
  readonly vehicleRows = computed(() => buildFleetVehicleRows(this.rowsSignal(), this.detailsByIdSignal()));

  /** **Your vehicles** / **Simulated**, each worst-verdict-first — see `preflight-logic.ts#buildBoardGroups`'s own doc comment for why this isn't `groupAndSort`'s bundled streaming-first order. */
  readonly boardGroups = computed<PickerGroups<FleetVehicleRow>>(() => buildBoardGroups(this.vehicleRows()));

  /** {@link boardGroups}, each group narrowed to the selected {@link verdictFilter} — equal to {@link boardGroups} itself when no card is selected. */
  readonly filteredGroups = computed<PickerGroups<FleetVehicleRow>>(() => {
    const filter = this.verdictFilter();
    const groups = this.boardGroups();
    return {
      yours: applyVerdictFilter(groups.yours, filter),
      simulated: applyVerdictFilter(groups.simulated, filter),
    };
  });

  /** Rows visible across both groups under the active filter — `0` is this page's "the filter matched nothing" gate. */
  readonly filteredCount = computed(() => this.filteredGroups().yours.length + this.filteredGroups().simulated.length);

  /** `vision-empty`'s title for the "this filter matched nothing" state — only ever read once {@link hasAnyAssets} is already true, so this is always a *filtered*-empty message. */
  readonly filteredEmptyTitle = computed(() => {
    const filter = this.verdictFilter();
    return filter === undefined ? '' : emptyFilterTitle(filter);
  });

  /** Thin passthroughs so `preflight.html` never re-derives a verdict/status this facade already fetched — same "expose the pure function as a bound field" idiom `onboarding-facade.ts#connectMethodLabels`/`detailsSummary` use. */
  readonly verdictLabel = verdictLabel;
  readonly verdictTone = verdictTone;
  readonly blocker = (row: ReadinessRow): BlockerSummary => blockerSummary(row.features);
  readonly statusOverride = (row: FleetVehicleRow): string | undefined => vehicleStatusOverride(row);

  constructor() {
    void this.refresh();
    effect(() => writePersistedFlag(HIDE_SIMULATED_KEY, this.hideSimulated()));
  }

  /** A stat card's own click: selecting the same filter again clears it. */
  toggleVerdictFilter(filter: BoardFilter): void {
    this.verdictFilter.update((current) => (current === filter ? undefined : filter));
  }

  clearVerdictFilter(): void {
    this.verdictFilter.set(undefined);
  }

  toggleHideSimulated(): void {
    this.hideSimulated.update((hidden) => !hidden);
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    let rows: readonly ReadinessRow[];
    try {
      const response = await this.api.fleetReadiness();
      rows = response.assets;
      this.rowsSignal.set(rows);
    } catch (error) {
      this.error.set(describeHttpError(error));
      this.loading.set(false);
      return;
    }
    await this.loadVehicleDetails(rows);
    this.loading.set(false);
  }

  /**
   * Best-effort triage/telemetry-device enrichment — fans `getAsset` out over every row exactly like
   * `AssetsFacade#refreshAssets` does for its own grid. Deliberately **not** the primary load's own
   * try/catch: a failure here degrades every row to `buildFleetVehicleRows`'s documented safe
   * fallbacks (never fabricated, never blocking) rather than putting the whole board into its error
   * state over what is, for this page's own purpose, enrichment.
   */
  private async loadVehicleDetails(rows: readonly ReadinessRow[]): Promise<void> {
    try {
      const details = await Promise.all(rows.map((row) => this.api.getAsset(row.assetId)));
      this.detailsByIdSignal.set(new Map(details.map((asset) => [asset.assetId, asset])));
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }
}
