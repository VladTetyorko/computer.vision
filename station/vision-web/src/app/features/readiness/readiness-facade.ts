import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import {
  featureLabel,
  featureStatusLabel,
  featureStatusTone,
  isProbeDisabledError,
  isRemediable,
  outcomeLabel,
  outcomeTone,
  remedyLabel,
  verdictLabel,
  verdictTone,
} from '../../core/readiness/readiness-logic';
import type { FeatureKey, ReadinessReport, RemediationResult } from '../../core/api/models';

/**
 * `ReadinessPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/assets/:assetId/readiness`,
 * the per-asset readiness report (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1, wave O6). The fleet
 * board (`features/preflight`) is a compact rollup; this page is the full answer for one asset —
 * every evaluated feature's status/detail/remedy, sourced from `GET /api/assets/{assetId}/readiness`,
 * plus the two authority actions the plan gives an operator here: probe the vehicle again
 * (`POST .../probe`) and remediate one feature (`POST .../remediate`).
 *
 * Injects `VisionApi` directly rather than growing a shared store — page-local, single-consumer
 * state, the same `ModelsFacade`/`PreflightFacade` shape.
 *
 * **Display name.** `ReadinessReportResponse` carries only `assetId` (verified against source, no
 * `displayName`) — this facade also reads `GET /api/assets/{assetId}` (`VisionApi#getAsset`, the
 * same call `AssetDetailFacade` makes) purely for the page title. A failure on *that* call still
 * degrades to the raw id rather than blocking the readiness report itself — the two reads fail
 * independently, `Promise.allSettled` not `Promise.all`.
 *
 * **Probe/remediate are authority actions (D8, audited server-side).** No client-side ownership
 * pre-check exists — same "no client-side `canManage(ownership)` gate" pattern as every other
 * asset-scoped write in this app (`asset-detail-facade.ts`'s own doc comment): the buttons always
 * show, a caller without authority gets a 403 back, surfaced via {@link probeError}/
 * {@link remediateError} same as any other failure.
 *
 * **The probe-disabled 409** (`vision.onboarding.probe.enabled=false`, the default, D17) is folded
 * into {@link probeDisabled} as a first-class state, not a toast — mirrors `OnboardingStore#verify`'s
 * identical handling of the same disabled body. `GET .../readiness` itself is never gated by that
 * flag (verified against `ReadinessController` — no flag check in the read path), so {@link load}
 * carries no disabled state of its own, only a generic {@link error}.
 */
@Injectable()
export class ReadinessFacade {
  private readonly api = inject(VisionApi);

  private readonly assetIdSignal = signal('');
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly displayName = signal<string | null>(null);
  readonly report = signal<ReadinessReport | null>(null);

  readonly probing = signal(false);
  readonly probeDisabled = signal(false);
  readonly probeError = signal<string | null>(null);

  private readonly remediatingFeatureSignal = signal<FeatureKey | null>(null);
  readonly remediatingFeature = this.remediatingFeatureSignal.asReadonly();
  readonly remediateError = signal<string | null>(null);
  readonly lastRemediation = signal<RemediationResult | null>(null);

  /** Feature keys named in {@link ReadinessReport.blockers} (raw keys, no label on the wire — verified against `DefaultReadinessService#evaluate`), mapped to their seeded label for display. */
  readonly blockerLabels = computed(() => (this.report()?.blockers ?? []).map((key) => featureLabel(key)));

  readonly verdictLabel = verdictLabel;
  readonly verdictTone = verdictTone;
  readonly featureLabel = featureLabel;
  readonly featureStatusLabel = featureStatusLabel;
  readonly featureStatusTone = featureStatusTone;
  readonly remedyLabel = remedyLabel;
  readonly isRemediable = isRemediable;
  readonly outcomeLabel = outcomeLabel;
  readonly outcomeTone = outcomeTone;

  /** Re-fetches for a new route param — mirrors `AssetDetailFacade#load`'s per-navigation reset. */
  async load(assetId: string): Promise<void> {
    this.assetIdSignal.set(assetId);
    this.loading.set(true);
    this.error.set(null);
    this.probeDisabled.set(false);
    this.probeError.set(null);
    this.remediateError.set(null);
    this.lastRemediation.set(null);
    const [nameResult, reportResult] = await Promise.allSettled([this.api.getAsset(assetId), this.api.assetReadiness(assetId)]);
    this.displayName.set(nameResult.status === 'fulfilled' ? nameResult.value.displayName : null);
    if (reportResult.status === 'fulfilled') {
      this.report.set(reportResult.value);
    } else {
      this.report.set(null);
      this.error.set(describeHttpError(reportResult.reason));
    }
    this.loading.set(false);
  }

  /** Re-fetches just the report (no display-name re-read) — used after a probe/remediate that doesn't itself return a fresh report. */
  private async reload(): Promise<void> {
    const assetId = this.assetIdSignal();
    try {
      this.report.set(await this.api.assetReadiness(assetId));
      this.error.set(null);
    } catch (err) {
      this.error.set(describeHttpError(err));
    }
  }

  /** "Probe now" — observes the vehicle's current link again, then re-reads the report so the page reflects the fresh profile. */
  async probe(): Promise<void> {
    const assetId = this.assetIdSignal();
    this.probing.set(true);
    this.probeError.set(null);
    this.probeDisabled.set(false);
    try {
      await this.api.probeAsset(assetId);
      await this.reload();
    } catch (err) {
      if (isProbeDisabledError(err)) {
        this.probeDisabled.set(true);
      } else {
        this.probeError.set(describeHttpError(err));
      }
    } finally {
      this.probing.set(false);
    }
  }

  /** Remediate one feature — only ever called for a row {@link isRemediable} lets through (`MESSAGE_INTERVAL`). `result.reprobe` (when present) replaces the report directly, no extra round trip. */
  async remediate(feature: FeatureKey): Promise<void> {
    const assetId = this.assetIdSignal();
    this.remediatingFeatureSignal.set(feature);
    this.remediateError.set(null);
    try {
      const result = await this.api.remediateAsset(assetId, { features: [feature], actions: ['MESSAGE_INTERVAL'] });
      this.lastRemediation.set(result);
      if (result.reprobe) {
        this.report.set(result.reprobe);
      }
    } catch (err) {
      if (isProbeDisabledError(err)) {
        this.probeDisabled.set(true);
      } else {
        this.remediateError.set(describeHttpError(err));
      }
    } finally {
      this.remediatingFeatureSignal.set(null);
    }
  }
}
