import { Injectable, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { DEFAULT_BATTERY_THRESHOLDS } from './thresholds-logic';
import type { BatteryThresholds } from '../api/models';

const LOG_PREFIX = '[ops-thresholds]';

/**
 * The one served severity source for battery urgency (S3, docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * D6) — `GET /api/ops/thresholds` read once per SPA session (`@OpenByDesign`, any signed-in caller,
 * no role gate) and shared from here by both the Fly cockpit OSD (`features/fly/fly-osd.ts`) and
 * fleet attention-logic's consumers (`core/fleet/attention-logic.ts`), so a battery reading is never
 * classified two different ways on two different screens — the exact bug this wave fixes (OSD's old
 * hardcoded 20/45 vs. fleet's old hardcoded 20/10).
 *
 * **Fetch-once, not a `PollScheduler` poller** — unlike `SystemStatusStore`'s recurring 15s cadence,
 * `vision.ops.battery.*` is process-level config, not telemetry: it cannot change without a server
 * restart, so one fetch per SPA session is enough (mirrors `AuthStore`'s own one-shot `/api/me` read
 * far more than `FleetStore`'s recurring one).
 *
 * **Degrades honestly on a failed/slow fetch**: {@link battery} always returns a real, usable value
 * — `DEFAULT_BATTERY_THRESHOLDS` (the initial signal value, and again on a failed `refresh()`) —
 * never blocking a caller or rendering a fabricated severity (CLAUDE.md "degrade honestly"). {@link
 * loaded}/{@link error} exist for a caller that wants to say so explicitly; nothing in this cycle
 * currently does, mirroring `SystemStatusStore`'s identical unused-by-every-consumer-yet fields.
 */
@Injectable({ providedIn: 'root' })
export class ThresholdsStore {
  private readonly api = inject(VisionApi);

  private readonly batterySignal = signal<BatteryThresholds>(DEFAULT_BATTERY_THRESHOLDS);
  private readonly loadedSignal = signal(false);
  private readonly errorSignal = signal<string | undefined>(undefined);

  /** Always a real, usable value — see class doc's "degrades honestly" note. */
  readonly battery = this.batterySignal.asReadonly();
  /** `false` until the first fetch ever succeeds — {@link battery} is already the honest default in the meantime. */
  readonly loaded = this.loadedSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    try {
      const response = await this.api.opsThresholds();
      this.batterySignal.set(response.battery);
      this.loadedSignal.set(true);
      this.errorSignal.set(undefined);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not read /api/ops/thresholds — using defaults`, { error });
      this.errorSignal.set('Could not read severity thresholds — using defaults.');
    }
  }
}
