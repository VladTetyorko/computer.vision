import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { WeatherStore } from '../../core/weather/weather-store';
import { DEFAULT_WIND_LIMIT_MPS, windAdvisory } from '../../core/weather/weather-logic';
import { formatDuration } from '../../core/stream-info-logic';

/**
 * The weather go/no-go chip (docs/OPS-CORE-PLAN.md §W) — reused by Command's header and Fly's OSD,
 * the same "second consumer → a shared, dumb, DI-sharing component" precedent `TelemetryOsd`/
 * `shared/player/detections-strip.ts` already established: this component takes **no data inputs**
 * beyond the optional wind limit — it injects whichever `WeatherStore` instance its host page
 * provided (`providers: [WeatherStore]`, one instance per host, see that class's own doc comment
 * for why it is page-provided rather than root) and renders nothing at all whenever
 * `store.reading()` is `undefined` — "offline/failed → chip hidden entirely, never a stale fake".
 *
 * **Not the same chip as `features/fly/fly-osd.ts`'s existing "Wind" chip** — that one is the
 * drone's own onboard MAVLink `WIND` reading (docs/FC-INTEGRATIONS-PLAN.md F-e,
 * `TelemetrySample.extra['windSpeedMps']`), a live *in-flight* instrument value with no severity
 * tier. This chip is a pre-flight/ambient **forecast** advisory from Open-Meteo, independent of
 * whether anything is even flying yet — the two intentionally coexist without colliding: this one
 * always reads "GO"/"CAUTION"/"NO-GO", the telemetry one never does.
 *
 * Advisory only, never blocking anything (docs/OPS-CORE-PLAN.md §W) — the `title` tooltip always
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
  private readonly weather = inject(WeatherStore);

  /** The go/no-go limit — the host resolves it (asset attribute vs. a fleet-wide default), see `WeatherStore`'s own class doc. */
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
    const ageSeconds = Math.max(0, (Date.now() - reading.fetchedAtMs) / 1000);
    return `Open-Meteo · updated ${formatDuration(ageSeconds)} ago · precipitation ${reading.precipitationMm.toFixed(1)}mm`;
  });
}
