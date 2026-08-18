import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar, type PageBarCrumb } from '../../shared/ui/page-bar/page-bar';
import { ReadinessFacade } from './readiness-facade';
import type { FeatureKey, RemediationResult } from '../../core/api/models';

/**
 * `/assets/:assetId/readiness` — the per-asset readiness report (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §8.1, wave O6): every feature this platform needs, this vehicle's last-observed status against it,
 * and why (`detail`), plus a "Probe now" re-check and a per-row "Remediate" action for the one remedy
 * kind this build can dispatch (`MESSAGE_INTERVAL`). Reached from the fleet board's "Report ›" link
 * (`features/preflight`) or directly by id.
 *
 * **Layered per docs/plans/done/UI-ARCHITECTURE-PLAN.md**: every store/service call and derived read-model
 * lives in {@link ReadinessFacade}; this component holds only the route-bound `assetId` input and the
 * constructor `effect()` that forwards it to `facade.load()` (mirrors `AssetDetailPage`'s own pattern).
 *
 * **No role gate** — reading a drone's own readiness is not a management action, same openness as
 * the fleet board it drills in from. The probe/remediate *buttons* always render; authority is
 * enforced server-side (D8) and a denial surfaces as an inline error, never a hidden control
 * (no client-side ownership pre-check exists anywhere in this app, `asset-detail-facade.ts`'s own
 * precedent).
 *
 * **Advisory only (OQ3):** nothing on this page blocks on `NO_GO` — it renders prominently (danger
 * chip, blockers list) and that is the full extent of this wave's resolution, pending an operator's
 * own answer to docs/plans/active/DRONE-ONBOARDING-PLAN.md §10 OQ3.
 */
@Component({
  selector: 'vision-readiness',
  imports: [EmptyState, Notice, PageBar],
  templateUrl: './readiness.html',
  styleUrl: './readiness.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReadinessFacade],
})
export class ReadinessPage {
  readonly assetId = input.required<string>();
  protected readonly facade = inject(ReadinessFacade);

  constructor() {
    effect(() => {
      const id = this.assetId();
      void this.facade.load(id);
    });
  }

  protected assetCrumb(): PageBarCrumb {
    return { label: 'Asset', to: `/assets/${this.assetId()}` };
  }

  protected formatDate(iso: string | null): string {
    return iso ? new Date(iso).toLocaleString() : 'Never';
  }

  protected onRemediate(feature: FeatureKey): void {
    void this.facade.remediate(feature);
  }

  protected onProbe(): void {
    void this.facade.probe();
  }

  /** `vision-notice`'s `variant` (no `muted` option, unlike `outcomeTone`) for a remediation result's first — and, since this page only ever remediates one feature at a time, only — action outcome. */
  protected remediationVariant(result: RemediationResult): 'neutral' | 'warn' | 'danger' | 'ok' {
    const outcome = result.actions[0]?.outcome;
    if (!outcome) return 'neutral';
    const tone = this.facade.outcomeTone(outcome);
    return tone === 'muted' ? 'neutral' : tone;
  }
}
