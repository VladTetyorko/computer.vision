import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import type { FleetSummary } from '../../core/api/models';
import { LiveStore } from '../../core/live/live-store';
import { activePipelineErrorMessagesByStreamId } from '../../core/system-events/system-events-logic';
import { pluralize } from '../../shared/ui/page-bar/page-bar';
import { attentionRows, categoryBars, reportKpis } from './reports-logic';

/**
 * `ReportsPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — one `VisionApi.fleetSummary()` fetch
 * (`GET /api/fleet/summary`, the same aggregated poll `CommandFacade` already drives, called
 * independently here — a second, unrelated page reading the same endpoint, not a shared store; see
 * `core/events/events-store.ts`'s own doc comment for the "only share a store when continuity across
 * navigation matters" line this dashboard's own one-shot read doesn't need to cross) backs every KPI
 * tile, category bar, and attention row below via {@link reportKpis}/{@link categoryBars}/
 * {@link attentionRows}. No role gate — same openness as `/command`, which this summary already
 * powers.
 */
@Injectable()
export class ReportsFacade {
  private readonly api = inject(VisionApi);
  private readonly liveStore = inject(LiveStore);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private readonly summarySignal = signal<FleetSummary | undefined>(undefined);

  /**
   * `streamId → active PIPELINE_ERROR message` (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4) — unlike
   * `CommandFacade`'s own version of this computed, there is no periodic poll here to piggyback a
   * clock tick on (this page is a one-shot `load()`, not a poller — see this class's own doc
   * comment), so this simply reads `Date.now()` at evaluation time. That means the 15-minute decay
   * only advances when *something* re-triggers this computed (a new live event arriving, or a
   * manual reload) rather than continuously — an acceptable gap for a static dashboard snapshot,
   * not for Command's own live rail.
   */
  private readonly pipelineErrorMessagesByStreamId = computed(() =>
    activePipelineErrorMessagesByStreamId(this.liveStore.liveEvents(), Date.now()),
  );

  readonly kpis = computed(() => reportKpis(this.summarySignal(), this.pipelineErrorMessagesByStreamId()));
  readonly bars = computed(() => categoryBars(this.summarySignal()?.categories ?? []));
  readonly attention = computed(() =>
    attentionRows(this.summarySignal()?.assets ?? [], this.pipelineErrorMessagesByStreamId()),
  );
  /** "N asset(s) flagged" was a literal placeholder string — `pluralize` (`shared/ui/page-bar`) is
   *  this app's one regular-English pluralisation helper, docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2. */
  readonly attentionSubtitle = computed(() => `${pluralize(this.attention().length, 'asset')} flagged`);

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.summarySignal.set(await this.api.fleetSummary());
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }
}
