import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { batterySeverity, telemetryAgeSeverity } from '../../core/telemetry/telemetry-logic';
import { gpsFixLabel, gpsSeverity } from '../../core/telemetry/flight-state-logic';
import { formatLatency, transportLabel } from '../../core/stream-info-logic';
import { DEFAULT_WIND_LIMIT_MPS } from '../../core/weather/weather-logic';
import { WeatherChip } from '../../shared/ui/weather-chip';
import type { Transport } from '../../shared/player/player';

/**
 * The Fly cockpit's overlaid OSD chip bar (docs/MVP3-PLAN.md §C-b): battery %, telemetry age
 * (three-tier color escalation), altitude, heading, and transport+behind-live — one compact row
 * meant to sit over live video and be read at a glance, not a rail widget.
 *
 * **Why a new component, not a reuse of `features/live/telemetry-osd.ts`**: that component renders a
 * full side-rail card (a rotating compass dial, a wide battery bar, a "Position" line) sized for
 * `/live`'s collapsible rail — the right shape for a panel the operator opens to inspect, wrong for
 * chrome that must stay legible in a thin strip over the picture at all times. Every *value* this
 * component shows still comes from the exact same `TelemetryStore` instance (DI-shared from the
 * host page's own `providers`, identical convention to `TelemetryOsd`/`DetectionsStrip`) and the
 * exact same pure severity/format functions `TelemetryOsd`/`shared/player/stream-info-panel.ts` already use
 * (`core/telemetry/telemetry-logic.ts#batterySeverity`, new `telemetryAgeSeverity` alongside it;
 * `core/stream-info-logic.ts#formatLatency`/`transportLabel`) — no new telemetry derivation beyond
 * the age-severity tiers, which are pure and unit-tested. `latencySeconds`/`transport` are inputs
 * rather than a third injected store, mirroring `shared/player/stream-info-panel.ts`'s identical shape: neither
 * value is store-backed anywhere, both are `shared/player/player.ts`'s own measurements piped up through its
 * `latencyChanged`/`transportChanged` outputs so this bar never re-measures independently.
 *
 * **Ground speed (docs/FC-INTEGRATIONS-PLAN.md F-d) closes this component's own previously-documented
 * gap** — this doc comment used to record "no current telemetry sample carries a ground-speed
 * reading" as an honest omission; `TelemetrySample.extra['groundspeedMps']` (the frozen wire
 * contract's own `Telemetry.extra` map, now surfaced) is that reading, decoded from MAVLink
 * `VFR_HUD`/`GLOBAL_POSITION_INT` server-side. Rendered as `X.Xm/s`, one decimal, omitted (not
 * `0.0m/s`) when the key is absent — a device with no flight-controller MAVLink link (the sim's own
 * older samples, a bare GPS-only source) still renders no speed chip at all, never a fabricated `0`.
 * **Mode/GPS/armed/RSSI chips** are this same cycle's other additions, all sourced from the latest
 * sample's `flightState` (`core/telemetry/flight-state-logic.ts#gpsFixLabel`/`gpsSeverity` decode
 * the GPS one) — each independently omitted when its own field is absent, same poka-yoke rule as
 * every other chip here.
 *
 * **Wind chip (docs/FC-INTEGRATIONS-PLAN.md F-e)** — `extra['windSpeedMps']`/
 * `extra['windDirectionDegrees']`, the same ArduPilot-only `WIND`-message extras
 * `core/telemetry/flight-state-logic.ts#deriveDiagnostics`'s own wind row reads (this chip is a
 * second, compact rendering of the identical data for at-a-glance HUD reading, not a duplicate
 * derivation — no severity tier, F-e defines none for wind). The arrow rotates to
 * `windDirectionDegrees` only when that key is present; the speed alone still renders without it.
 *
 * **Weather go/no-go chip (docs/OPS-CORE-PLAN.md §W)** — `<vision-weather-chip>`, always rendered
 * (outside the `hasTelemetry()` branch, alongside the transport chip, since it's a pre-flight/
 * ambient forecast advisory independent of whether telemetry — or a flight at all — exists yet). It
 * injects `WeatherStore` from `FlyPage`'s own `providers` directly (DI resolves through the
 * component tree regardless of which template renders the consumer, the same "DI-shares the host's
 * instance" idiom this whole file already follows for `TelemetryStore`) — **not the same thing as
 * this component's own "Wind" chip two paragraphs up**, see `WeatherChip`'s own doc comment for the
 * telemetry-instrument-vs-ambient-forecast distinction.
 */
@Component({
  selector: 'vision-fly-osd',
  imports: [WeatherChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="osd-bar">
      @if (store.hasTelemetry()) {
        <span class="chip" [class]="'batt-' + batterySeverityTier()">
          <span class="k">Batt</span><span class="v">{{ batteryLabel() }}</span>
        </span>
        <span class="chip" [class]="'age-' + ageSeverityTier()">
          <span class="k">Telem</span><span class="v">{{ ageLabel() }}</span>
        </span>
        <span class="chip">
          <span class="k">Alt</span><span class="v">{{ altitudeLabel() }}</span>
        </span>
        <span class="chip">
          <span class="k">Hdg</span><span class="v">{{ headingLabel() }}</span>
        </span>
        @if (groundSpeedLabel(); as speed) {
          <span class="chip">
            <span class="k">GS</span><span class="v">{{ speed }}</span>
          </span>
        }
        @if (modeLabel(); as mode) {
          <span class="chip">
            <span class="k">Mode</span><span class="v">{{ mode }}</span>
          </span>
        }
        @if (gpsFixType() !== undefined) {
          <span class="chip" [class]="'gps-' + gpsSeverityTier()">
            <span class="k">GPS</span><span class="v">{{ gpsLabel() }}</span>
          </span>
        }
        @if (armed() !== undefined) {
          <span class="chip" [class.armed-chip]="armed()" [class.disarmed-chip]="!armed()">
            <span class="v">{{ armed() ? 'ARMED' : 'DISARMED' }}</span>
          </span>
        }
        @if (rssiPercent() !== undefined) {
          <span class="chip">
            <span class="k">RSSI</span><span class="v">{{ rssiPercent() }}%</span>
          </span>
        }
        @if (windSpeedLabel(); as wind) {
          <span class="chip">
            <span class="k">Wind</span><span class="v">{{ wind }}</span>
            @if (windDirectionDegrees(); as direction) {
              <span class="wind-arrow" [style.transform]="'rotate(' + direction + 'deg)'" aria-hidden="true">➤</span>
            }
          </span>
        }
      } @else {
        <span class="chip dim">No telemetry</span>
      }
      <vision-weather-chip [limitMps]="windLimitMps()" />
      <span class="chip dim transport-chip">{{ transportLabelText() }}</span>
    </div>
  `,
  styles: `
    .osd-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      pointer-events: none;
    }

    .chip {
      display: flex;
      align-items: baseline;
      gap: 0.32rem;
      padding: 0.3rem 0.65rem;
      border-radius: var(--radius-pill);
      background: rgb(6 9 14 / 62%);
      backdrop-filter: blur(6px);
      border: 1px solid rgb(255 255 255 / 9%);
      font-size: 0.78rem;
      color: #e7ecf3;
      white-space: nowrap;
    }

    .k {
      font-size: 0.6rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: rgb(231 236 243 / 58%);
    }

    .v {
      font-family: var(--mono);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    .chip.dim {
      font-family: var(--mono);
      color: rgb(231 236 243 / 78%);
      font-variant-numeric: tabular-nums;
    }

    .batt-low .v,
    .age-amber .v {
      color: var(--warn);
    }

    .batt-critical,
    .age-red {
      border-color: rgb(255 93 93 / 45%);
    }

    .batt-critical .v,
    .age-red .v {
      color: #ff9a9a;
    }

    .gps-warn .v {
      color: var(--warn);
    }

    .gps-critical {
      border-color: rgb(255 93 93 / 45%);
    }

    .gps-critical .v {
      color: #ff9a9a;
    }

    /* Armed/disarmed are both routine states, not a severity tier (.chip's plain default is the
       usual "nothing wrong" color here) — armed reads a mild green-ish (the --ok token's own hue,
       softened, not the saturated .chip.ok fill) so it doesn't compete with --live/--danger for
       attention; disarmed is dimmed rather than colored at all — a grounded drone is the normal,
       safe state, not something to flag. */
    .armed-chip .v {
      color: #8ce7b4;
    }

    .disarmed-chip {
      opacity: 0.65;
    }

    /* Wind chip (docs/FC-INTEGRATIONS-PLAN.md F-e) — the arrow glyph rotates in place via its own
       inline transform (the direction degrees), no severity color (F-e defines no threshold). */
    .wind-arrow {
      display: inline-block;
      font-size: 0.7rem;
      line-height: 1;
    }
  `,
})
export class FlyOsd {
  protected readonly store = inject(TelemetryStore);

  /** `shared/player/player.ts`'s own measured seconds-behind-live, piped up via its `latencyChanged` output. */
  readonly latencySeconds = input<number | null>(null);
  /** `shared/player/player.ts`'s own live transport, piped up via its `transportChanged` output. */
  readonly transport = input<Transport>('hls');
  /** `AssetDetails.attributes['windLimitMps']`, resolved by `FlyPage` (docs/OPS-CORE-PLAN.md §W) — defaults to 10 m/s. */
  readonly windLimitMps = input<number>(DEFAULT_WIND_LIMIT_MPS);

  private readonly batteryPercent = computed(() => this.store.latest()?.batteryPercent);
  protected readonly batteryLabel = computed(() => {
    const percent = this.batteryPercent();
    return percent === undefined ? '—' : `${percent.toFixed(0)}%`;
  });
  protected readonly batterySeverityTier = computed(() => batterySeverity(this.batteryPercent()));

  protected readonly ageLabel = computed(() => {
    const age = this.store.sampleAgeSeconds();
    return age === undefined ? '—' : `${age.toFixed(0)}s`;
  });
  /** `'fresh'` (rather than a fabricated red) until a first sample actually exists. */
  protected readonly ageSeverityTier = computed(() => {
    const age = this.store.sampleAgeSeconds();
    return age === undefined ? 'fresh' : telemetryAgeSeverity(age);
  });

  protected readonly altitudeLabel = computed(() => {
    const meters = this.store.latest()?.altitudeMeters;
    return meters === undefined ? '—' : `${meters.toFixed(0)}m`;
  });

  protected readonly headingLabel = computed(() => {
    const heading = this.store.latest()?.headingDegrees;
    return heading === undefined ? '—' : `${heading.toFixed(0)}°`;
  });

  /** `Telemetry.extra['groundspeedMps']` — see this class's own doc comment for the gap this closes. */
  protected readonly groundSpeedLabel = computed(() => {
    const mps = this.store.latest()?.extra?.['groundspeedMps'];
    return mps === undefined ? undefined : `${mps.toFixed(1)}m/s`;
  });

  private readonly flightState = computed(() => this.store.latest()?.flightState);

  /** The FC's own human mode name (`"RTL"`, `"Loiter"`, …) — no chip at all until one is reported. */
  protected readonly modeLabel = computed(() => this.flightState()?.mode);

  protected readonly gpsFixType = computed(() => this.flightState()?.gpsFixType);
  protected readonly gpsSeverityTier = computed(() => gpsSeverity(this.gpsFixType()));
  protected readonly gpsLabel = computed(() => {
    const fixType = this.gpsFixType();
    const satellites = this.flightState()?.satellites;
    const label = gpsFixLabel(fixType);
    return satellites === undefined ? label : `${label} · ${satellites} sat`;
  });

  /** `true`/`false` each render their own chip; `undefined` (no flightState yet) renders none at all. */
  protected readonly armed = computed(() => this.flightState()?.armed);

  protected readonly rssiPercent = computed(() => this.flightState()?.rssiPercent);

  /** `TelemetrySample.extra['windSpeedMps']` (docs/FC-INTEGRATIONS-PLAN.md F-e) — `undefined` renders no chip at all. */
  protected readonly windSpeedLabel = computed(() => {
    const mps = this.store.latest()?.extra?.['windSpeedMps'];
    return mps === undefined ? undefined : `${mps.toFixed(1)}m/s`;
  });

  protected readonly windDirectionDegrees = computed(() => this.store.latest()?.extra?.['windDirectionDegrees']);

  protected readonly transportLabelText = computed(
    () => `${transportLabel(this.transport())} · ${formatLatency(this.latencySeconds())}`,
  );
}
