import { Injectable, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { DEFAULT_BATTERY_THRESHOLDS, DEFAULT_RC_THRESHOLDS } from './thresholds-logic';
import type { BatteryThresholds, RcThresholds } from '../api/models';

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
 * restart, so one fetch per SPA session is enough (mirrors `AuthFacade`'s own one-shot `/api/me` read
 * far more than `FleetStore`'s recurring one).
 *
 * **Degrades honestly on a failed/slow fetch**: {@link battery} always returns a real, usable value
 * — `DEFAULT_BATTERY_THRESHOLDS` (the initial signal value, and again on a failed `refresh()`) —
 * never blocking a caller or rendering a fabricated severity (CLAUDE.md "degrade honestly"). {@link
 * loaded}/{@link error} exist for a caller that wants to say so explicitly; nothing in this cycle
 * currently does, mirroring `SystemStatusStore`'s identical unused-by-every-consumer-yet fields.
 *
 * <h2>`rc` — the neutral-stick arm gate's tolerance (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2)</h2>
 * Same store, same fetch, no second network round trip — the plan's own instruction: "the one
 * fetch-once store gains the matching signal … no second store." {@link rc} degrades to
 * `DEFAULT_RC_THRESHOLDS` on the exact same two occasions {@link battery} degrades to its own
 * default: a failed/slow fetch, **and**, uniquely to this field, a server that answered but simply
 * omits `rc` — BK1 (the wave that adds it server-side) ships in parallel with this one, so the field
 * being absent from an otherwise-successful response is an expected, not a faulted, shape.
 */
@Injectable({ providedIn: 'root' })
export class ThresholdsStore {
  private readonly api = inject(VisionApi);

  private readonly batterySignal = signal<BatteryThresholds>(DEFAULT_BATTERY_THRESHOLDS);
  private readonly rcSignal = signal<RcThresholds>(DEFAULT_RC_THRESHOLDS);
  private readonly loadedSignal = signal(false);
  private readonly errorSignal = signal<string | undefined>(undefined);

  /** Always a real, usable value — see class doc's "degrades honestly" note. */
  readonly battery = this.batterySignal.asReadonly();
  /** Always a real, usable value — degrades to `DEFAULT_RC_THRESHOLDS` on a failed fetch *or* a
   * response that simply doesn't carry `rc` yet (see class doc's own "rc" section). */
  readonly rc = this.rcSignal.asReadonly();
  /** `false` until the first fetch ever succeeds — {@link battery}/{@link rc} are already the honest defaults in the meantime. */
  readonly loaded = this.loadedSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    try {
      const response = await this.api.opsThresholds();
      this.batterySignal.set(response.battery);
      this.rcSignal.set(response.rc ?? DEFAULT_RC_THRESHOLDS);
      this.loadedSignal.set(true);
      this.errorSignal.set(undefined);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not read /api/ops/thresholds — using defaults`, { error });
      this.errorSignal.set('Could not read severity thresholds — using defaults.');
    }
  }
}
