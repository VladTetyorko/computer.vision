import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { WeatherFacade } from '../../core/weather/weather-facade';
import { DEFAULT_WIND_LIMIT_MPS, windAdvisory } from '../../core/weather/weather-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * The weather go/no-go chip (docs/plans/done/OPS-CORE-PLAN.md §W) — reused by Command's header and Fly's OSD,
 * the same "second consumer → a shared, dumb, DI-sharing component" precedent `TelemetryOsd`/
 * `shared/player/detections-strip.ts` already established: this component takes **no data inputs**
 * beyond the optional wind limit — it injects whichever `WeatherFacade` instance its host page
 * provided (`providers: [WeatherFacade]`, one instance per host, see that class's own doc comment
 * for why it is page-provided rather than root) and renders nothing at all whenever
 * `store.reading()` is `undefined` — "offline/failed → chip hidden entirely, never a stale fake".
 *
 * **Not the same chip as `features/fly/fly-osd.ts`'s existing "Wind" chip** — that one is the
 * drone's own onboard MAVLink `WIND` reading (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e,
 * `TelemetrySample.extra['windSpeedMps']`), a live *in-flight* instrument value with no severity
 * tier. This chip is a pre-flight/ambient **forecast** advisory from Open-Meteo, independent of
 * whether anything is even flying yet — the two intentionally coexist without colliding: this one
 * always reads "GO"/"CAUTION"/"NO-GO", the telemetry one never does.
 *
 * Advisory only, never blocking anything (docs/plans/done/OPS-CORE-PLAN.md §W) — the `title` tooltip always
 * names the data source and the reading's own age, so a manager/pilot can judge for themselves how
 * much to trust a chip that hasn't refreshed in a while, rather than the chip silently going stale
 * with no indication.
 */
@Component({
  selector: 'vision-weather-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (advisory(); as advisory) {
      <span class="chip" [class.ok]="advisory.severity === 'ok'" [class.warn]="advisory.severity === 'warn'" [class.danger]="advisory.severity === 'no-go'" [title]="tooltip()">
        {{ advisory.label }}
      </span>
    }
  `,
})
export class WeatherChip {
  private readonly weather = inject(WeatherFacade);

  /** The go/no-go limit — the host resolves it (asset attribute vs. a fleet-wide default), see `WeatherFacade`'s own class doc. */
  readonly limitMps = input<number>(DEFAULT_WIND_LIMIT_MPS);

  protected readonly advisory = computed(() => {
    const reading = this.weather.reading();
    return reading ? windAdvisory(reading.speedMps, reading.gustsMps, this.limitMps()) : undefined;
  });

  protected readonly tooltip = computed(() => {
    const reading = this.weather.reading();
    if (!reading) {
      return '';
    }
    // `humanAge`, not `stream-info-logic.ts#formatDuration` — this is a reading's *age*, this
    // app's one age vocabulary (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4, §2 N4), not a
    // ticking session duration; found via this cycle's own W5 "grep `ago` for any remaining
    // formatDuration-based age" sweep.
    const ageSeconds = Math.max(0, (Date.now() - reading.fetchedAtMs) / 1000);
    return `Open-Meteo · updated ${humanAge(ageSeconds)} ago · precipitation ${reading.precipitationMm.toFixed(1)}mm`;
  });
}
