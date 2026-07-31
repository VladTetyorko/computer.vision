import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import type { FleetSummary } from '../../core/api/models';
import { attentionRows, categoryBars, reportKpis } from './reports-logic';

/**
 * `ReportsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — one `VisionApi.fleetSummary()` fetch
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

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private readonly summarySignal = signal<FleetSummary | undefined>(undefined);

  readonly kpis = computed(() => reportKpis(this.summarySignal()));
  readonly bars = computed(() => categoryBars(this.summarySignal()?.categories ?? []));
  readonly attention = computed(() => attentionRows(this.summarySignal()?.assets ?? []));

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
