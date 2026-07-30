import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { batterySeverity, telemetryAgeSeverity } from '../../core/telemetry/telemetry-logic';
import { gpsFixLabel, gpsSeverity } from '../../core/telemetry/flight-state-logic';
import { formatLatency, transportLabel } from '../../core/stream-info-logic';
import { DEFAULT_WIND_LIMIT_MPS } from '../../core/weather/weather-logic';
import { Icon } from '../../shared/ui/icon';
import { WeatherChip } from '../../shared/ui/weather-chip';
import { positionLabel } from './fly-logic';
import type { Transport } from '../../shared/player/player';

/**
 * The Fly cockpit's bottom OSD strip (docs/MVP3-PLAN.md §C-b, relayout per direct user request):
 * battery/armed, position, telemetry age/RSSI, altitude/heading/speed/mode/GPS, and wind/weather —
 * grouped into four labeled Power/Nav/Link/Env clusters (docs/UI-REDESIGN-PLAN.md Wave 2), each a
 * `.surface-hud` pill with a `<vision-icon>` per metric, rather than the ~11 same-looking flat chips
 * this bar used to render in one undifferentiated row. **Moved from a top-left overlay stack to a
 * full-width row along the bottom of the cockpit grid** (`fly.css`'s `telemetry` grid area, a real
 * row below `main` now, not a column beside it) so the video hero reads as the dominant, centered
 * element rather than sharing the frame with a stacked instrument column — the same four clusters
 * now lay out left-to-right instead of top-to-bottom, reading like an FPV/ground-station OSD strip.
 * Env (wind + the ambient weather advisory — the two least flight-critical metrics, and the two this
 * relayout's own "key characteristics" list omits) still sits behind a compact disclosure chevron,
 * reusing `diagnostics-card.ts`'s own expand/collapse idiom; Power/Nav/Link stay always visible.
 * Still read-only, still fed by the exact same `TelemetryStore`/pure severity functions as before —
 * this pass only reorganizes presentation (plus one new chip, Position — see below).
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
 * **Position chip (new, this relayout)** — `TelemetrySample.latitude`/`.longitude`, formatted with
 * `fly-logic.ts#positionLabel` (the exact same `lat, lon` to 4 decimal places already used by the
 * picker card's own "Position" fact, reused rather than re-derived) — omitted, not `—, —`, until
 * both coordinates exist on the latest sample. Lives in the Nav cluster, first, per the "key
 * characteristics" callout that asked for it alongside Signal and Battery.
 *
 * **Wind chip (docs/FC-INTEGRATIONS-PLAN.md F-e)** — `extra['windSpeedMps']`/
 * `extra['windDirectionDegrees']`, the same ArduPilot-only `WIND`-message extras
 * `core/telemetry/flight-state-logic.ts#deriveDiagnostics`'s own wind row reads (this chip is a
 * second, compact rendering of the identical data for at-a-glance HUD reading, not a duplicate
 * derivation — no severity tier, F-e defines none for wind). The arrow rotates to
 * `windDirectionDegrees` only when that key is present; the speed alone still renders without it.
 *
 * **Weather go/no-go chip (docs/OPS-CORE-PLAN.md §W)** — `<vision-weather-chip>`, always rendered
 * inside the Env group (outside the `hasTelemetry()` branch, since it's a pre-flight/ambient
 * forecast advisory independent of whether telemetry — or a flight at all — exists yet). It injects
 * `WeatherStore` from `FlyPage`'s own `providers` directly (DI resolves through the component tree
 * regardless of which template renders the consumer, the same "DI-shares the host's instance" idiom
 * this whole file already follows for `TelemetryStore`) — **not the same thing as this component's
 * own "Wind" chip two paragraphs up**, see `WeatherChip`'s own doc comment for the
 * telemetry-instrument-vs-ambient-forecast distinction.
 */
@Component({
  selector: 'vision-fly-osd',
  imports: [Icon, WeatherChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="osd-groups">
      <div class="osd-group surface-hud">
        <span class="osd-group-label">Power</span>
        <div class="osd-group-body">
          @if (store.hasTelemetry()) {
            <span class="osd-metric" [class]="'batt-' + batterySeverityTier()">
              <vision-icon name="battery" [size]="14" /><span class="v">{{ batteryLabel() }}</span>
            </span>
            @if (armed() !== undefined) {
              <span class="osd-metric" [class.armed]="armed()" [class.disarmed]="!armed()">
                <vision-icon name="power" [size]="14" /><span class="v">{{ armed() ? 'ARMED' : 'DISARMED' }}</span>
              </span>
            }
          } @else {
            <span class="osd-metric dim">No telemetry</span>
          }
        </div>
      </div>

      @if (store.hasTelemetry()) {
        <div class="osd-group surface-hud">
          <span class="osd-group-label">Nav</span>
          <div class="osd-group-body">
            @if (positionText(); as position) {
              <span class="osd-metric">
                <vision-icon name="map" [size]="14" /><span class="v">{{ position }}</span>
              </span>
            }
            <span class="osd-metric">
              <vision-icon name="ruler" [size]="14" /><span class="v">{{ altitudeLabel() }}</span>
            </span>
            <span class="osd-metric">
              <vision-icon name="compass" [size]="14" /><span class="v">{{ headingLabel() }}</span>
            </span>
            @if (groundSpeedLabel(); as speed) {
              <span class="osd-metric">
                <vision-icon name="gauge" [size]="14" /><span class="v">{{ speed }}</span>
              </span>
            }
            @if (modeLabel(); as mode) {
              <span class="osd-metric">
                <vision-icon name="list" [size]="14" /><span class="v">{{ mode }}</span>
              </span>
            }
            @if (gpsFixType() !== undefined) {
              <span class="osd-metric" [class]="'gps-' + gpsSeverityTier()">
                <vision-icon name="satellite" [size]="14" /><span class="v">{{ gpsLabel() }}</span>
              </span>
            }
          </div>
        </div>
      }

      <!-- Link — the one group that always renders (the transport/latency chip is meaningful with
           or without telemetry); the age/RSSI chips join it once a telemetry sample actually exists. -->
      <div class="osd-group surface-hud">
        <span class="osd-group-label">Link</span>
        <div class="osd-group-body">
          @if (store.hasTelemetry()) {
            <span class="osd-metric" [class]="'age-' + ageSeverityTier()">
              <vision-icon name="signal" [size]="14" /><span class="v">{{ ageLabel() }}</span>
            </span>
            @if (rssiPercent() !== undefined) {
              <span class="osd-metric">
                <vision-icon name="signal" [size]="14" /><span class="v">{{ rssiPercent() }}%</span>
              </span>
            }
          }
          <span class="osd-metric dim">
            <span class="v">{{ transportLabelText() }}</span>
          </span>
        </div>
      </div>

      <!-- Env — the least flight-critical cluster (ambient wind/weather advisory), behind a compact
           disclosure chevron (reuses diagnostics-card.ts's own expand/collapse idiom). Defaults
           open, same as that component's own precedent. -->
      <button type="button" class="osd-group-toggle" (click)="expanded.set(!expanded())" [attr.aria-expanded]="expanded()">
        <vision-icon [name]="expanded() ? 'chevron-up' : 'chevron-down'" [size]="14" />
        {{ expanded() ? 'Less' : 'Env' }}
      </button>

      @if (expanded()) {
        <div class="osd-group surface-hud">
          <span class="osd-group-label">Env</span>
          <div class="osd-group-body">
            @if (windSpeedLabel(); as wind) {
              <span class="osd-metric">
                <vision-icon name="wind" [size]="14" /><span class="v">{{ wind }}</span>
                @if (windDirectionDegrees(); as direction) {
                  <span class="wind-arrow" [style.transform]="'rotate(' + direction + 'deg)'" aria-hidden="true">➤</span>
                }
              </span>
            }
            <vision-weather-chip [limitMps]="windLimitMps()" />
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    /* A bottom bar now (fly.css's own telemetry grid row, below the video, not an overlay column
       beside it) — clusters lay out left-to-right and wrap/center as the row narrows, rather than
       stacking top-to-bottom down a fixed-width column. pointer-events: none on the row itself
       still matters: the row's own empty gaps between cluster pills must never intercept clicks
       meant for whatever renders below it (the controls row sits right after this one), only the
       pills/Env toggle/weather chip re-enable it. */
    .osd-groups {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: center;
      gap: var(--space-2) var(--space-3);
      pointer-events: none;
    }

    .osd-group {
      pointer-events: auto;
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
      padding: 0.3rem 0.6rem;
      border-radius: var(--radius-sm);
      min-width: 5.25rem;
    }

    .osd-group-label {
      font-size: 0.58rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.07em;
      color: rgb(231 236 243 / 58%);
    }

    .osd-group-body {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem 0.6rem;
    }

    .osd-metric {
      display: inline-flex;
      align-items: center;
      gap: 0.28rem;
      font-size: 0.78rem;
      color: #e7ecf3;
      white-space: nowrap;
    }

    .osd-metric .v {
      font-family: var(--mono);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    .osd-metric.dim {
      color: rgb(231 236 243 / 78%);
    }

    .osd-metric.batt-low .v,
    .osd-metric.age-amber .v {
      color: var(--warn);
    }

    .osd-metric.batt-critical .v,
    .osd-metric.age-red .v {
      color: #ff9a9a;
    }

    .osd-metric.gps-warn .v {
      color: var(--warn);
    }

    .osd-metric.gps-critical .v {
      color: #ff9a9a;
    }

    /* Armed/disarmed are both routine states, not a severity tier (a mild green-ish tint, not the
       saturated --ok fill, so it doesn't compete with --live/--danger for attention) — disarmed is
       dimmed rather than colored at all, a grounded drone being the normal, safe state. */
    .osd-metric.armed .v {
      color: #8ce7b4;
    }

    .osd-metric.disarmed {
      opacity: 0.7;
    }

    .wind-arrow {
      display: inline-block;
      font-size: 0.7rem;
      line-height: 1;
    }

    .osd-group-toggle {
      pointer-events: auto;
      align-self: center;
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.3rem 0.55rem;
      border-radius: var(--radius-pill);
      background: none;
      border: var(--hud-border);
      color: rgb(231 236 243 / 70%);
      font-size: 0.7rem;
      cursor: pointer;
    }

    .osd-group-toggle:hover {
      background: var(--hud-bg);
      color: #e7ecf3;
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

  /** The Env cluster's own disclosure state (docs/UI-REDESIGN-PLAN.md Wave 2) — defaults open,
   * mirroring `diagnostics-card.ts`'s own precedent. */
  protected readonly expanded = signal(true);

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

  /** `TelemetrySample.latitude`/`.longitude` — see this class's own doc comment for why this reuses
   * `fly-logic.ts#positionLabel` rather than re-deriving the same `lat, lon` format. */
  protected readonly positionText = computed(() => {
    const latest = this.store.latest();
    if (latest?.latitude === undefined || latest.longitude === undefined) {
      return undefined;
    }
    return positionLabel({ latitude: latest.latitude, longitude: latest.longitude });
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
