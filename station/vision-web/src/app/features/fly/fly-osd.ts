import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { batterySeverity, humanAge, telemetryAgeSeverity } from '../../core/telemetry/telemetry-logic';
import { ThresholdsFacade } from '../../core/ops/thresholds-facade';
import { gpsFixLabel, gpsSeverity } from '../../core/telemetry/flight-state-logic';
import { formatLatency, transportLabel } from '../../core/stream-info-logic';
import { DEFAULT_WIND_LIMIT_MPS } from '../../core/weather/weather-logic';
import { Icon } from '../../shared/ui/icon';
import { WeatherChip } from '../../shared/ui/weather-chip';
import { GeoChip } from './geo-chip';
import { positionFact } from './fly-logic';
import { armedOsdText, isStaleReading, osdGroupLabel } from './fly-osd-logic';
import type { Transport } from '../../shared/player/player';

/**
 * The Fly cockpit's bottom OSD strip (docs/plans/done/MVP3-PLAN.md §C-b, relayout per direct user request):
 * battery/armed, position, telemetry age/RSSI, altitude/heading/speed/mode/GPS, and wind/weather —
 * grouped into four labeled Power/Nav/Link/Env clusters (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2), each a
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
 * **Ground speed (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d) closes this component's own previously-documented
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
 * **Wind chip (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e)** — `extra['windSpeedMps']`/
 * `extra['windDirectionDegrees']`, the same ArduPilot-only `WIND`-message extras
 * `core/telemetry/flight-state-logic.ts#deriveDiagnostics`'s own wind row reads (this chip is a
 * second, compact rendering of the identical data for at-a-glance HUD reading, not a duplicate
 * derivation — no severity tier, F-e defines none for wind). The arrow rotates to
 * `windDirectionDegrees` only when that key is present; the speed alone still renders without it.
 *
 * **Weather go/no-go chip (docs/plans/done/OPS-CORE-PLAN.md §W)** — `<vision-weather-chip>`, always rendered
 * inside the Env group (outside the `hasTelemetry()` branch, since it's a pre-flight/ambient
 * forecast advisory independent of whether telemetry — or a flight at all — exists yet). It injects
 * `WeatherStore` from `FlyPage`'s own `providers` directly (DI resolves through the component tree
 * regardless of which template renders the consumer, the same "DI-shares the host's instance" idiom
 * this whole file already follows for `TelemetryStore`) — **not the same thing as this component's
 * own "Wind" chip two paragraphs up**, see `WeatherChip`'s own doc comment for the
 * telemetry-instrument-vs-ambient-forecast distinction.
 *
 * **Geo divergence chip (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.8, wave H6)** — `<vision-geo-chip>`,
 * in the always-rendered Link group (a vision-derived position, like the transport/latency chip
 * beside it, needs no flight-controller telemetry link at all). Same DI-sharing idiom as
 * `<vision-weather-chip>` above, injecting `GeoStore` from `CockpitPage`'s own `providers` — see
 * `geo-chip.ts`'s own doc comment for the chip label/tone rules and its detail popover.
 *
 * **Stale is not live (docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1)** — past
 * `fly-osd-logic.ts#isStaleReading`'s threshold (the exact same `TELEMETRY_AGE_RED_SECONDS` tier the
 * Link group's own age chip already colors red — one source, not a second invented "how stale is
 * too stale"), the Power and Nav groups dim (`.osd-group.stale`, `fly-osd.css`), their own group
 * label swaps from `'Power'`/`'Nav'` to `'LAST KNOWN · <humanAge>'` (`fly-osd-logic.ts#osdGroupLabel`),
 * and the armed chip reads `'ARMED?'` rather than a confident `'ARMED'`
 * (`fly-osd-logic.ts#armedOsdText`) — H1's own finding was a rover last heard from 4 days ago
 * reading `POWER 90% · ARMED` in full colour, with only the LINK chip's raw `353099s` hinting
 * anything was wrong. `ageLabel` itself is now `humanAge`-formatted everywhere (`12s`/`3m 10s`/
 * `4h 2m`/`4d 2h`), not conditioned on staleness — a raw second count is unreadable at any age.
 *
 * **This component was inline template/styles until wave H6 touched it** — split into
 * `fly-osd.html`/`fly-osd.css` (this repo's own three-file component convention) purely as a side
 * effect of adding the one `<vision-geo-chip />` line above; nothing about the markup/styles
 * themselves changed in that split.
 */
@Component({
  selector: 'vision-fly-osd',
  imports: [Icon, WeatherChip, GeoChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fly-osd.html',
  styleUrl: './fly-osd.css',
})
export class FlyOsd {
  protected readonly store = inject(TelemetryFacade);
  /** The one served severity source (S3, docs/plans/active/ASSET-FLOWS-PLAN.md §2 D6) —
   *  {@link batterySeverityTier} below now reads this instead of `batterySeverity`'s own old fixed
   *  45/20 pair, so the OSD's battery color and the fleet attention list's can never disagree again. */
  private readonly thresholds = inject(ThresholdsFacade);

  /** `shared/player/player.ts`'s own measured seconds-behind-live, piped up via its `latencyChanged` output. */
  readonly latencySeconds = input<number | null>(null);
  /** `shared/player/player.ts`'s own live transport, piped up via its `transportChanged` output. */
  readonly transport = input<Transport>('hls');
  /** `AssetDetails.attributes['windLimitMps']`, resolved by `FlyPage` (docs/plans/done/OPS-CORE-PLAN.md §W) — defaults to 10 m/s. */
  readonly windLimitMps = input<number>(DEFAULT_WIND_LIMIT_MPS);

  /** The Env cluster's own disclosure state (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2) — defaults open,
   * mirroring `diagnostics-card.ts`'s own precedent. */
  protected readonly expanded = signal(true);

  private readonly batteryPercent = computed(() => this.store.latest()?.batteryPercent);
  protected readonly batteryLabel = computed(() => {
    const percent = this.batteryPercent();
    return percent === undefined ? '—' : `${percent.toFixed(0)}%`;
  });
  protected readonly batterySeverityTier = computed(() =>
    batterySeverity(this.batteryPercent(), this.thresholds.battery()),
  );

  /** `humanAge`-formatted (`12s`/`3m 10s`/`4h 2m`/`4d 2h`), not a raw second count — H1's own
   * finding: `353099s` on this chip is a number nobody parses. */
  protected readonly ageLabel = computed(() => {
    const age = this.store.sampleAgeSeconds();
    return age === undefined ? '—' : humanAge(age);
  });
  /** `'fresh'` (rather than a fabricated red) until a first sample actually exists. Unchanged by
   * H1 — the Link group's own age chip keeps its existing red tier regardless of the whole-group
   * dimming {@link isStale} now drives elsewhere on this strip. */
  protected readonly ageSeverityTier = computed(() => {
    const age = this.store.sampleAgeSeconds();
    return age === undefined ? 'fresh' : telemetryAgeSeverity(age);
  });

  /** Whether the Power/Nav groups should read as "last known", not live — see this class's own doc
   * comment (H1). */
  protected readonly isStale = computed(() => isStaleReading(this.store.sampleAgeSeconds()));
  protected readonly powerGroupLabel = computed(() => osdGroupLabel('Power', this.store.sampleAgeSeconds()));
  protected readonly navGroupLabel = computed(() => osdGroupLabel('Nav', this.store.sampleAgeSeconds()));

  /** `TelemetrySample.latitude`/`.longitude` — see this class's own doc comment for why this reuses
   * `fly-logic.ts#positionLabel` rather than re-deriving the same `lat, lon` format. */
  protected readonly positionText = computed(() => {
    const latest = this.store.latest();
    if (latest?.latitude === undefined || latest.longitude === undefined) {
      return undefined;
    }
    return positionFact({ latitude: latest.latitude, longitude: latest.longitude })?.value;
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
  /** `'ARMED?'` rather than a confident `'ARMED'` once {@link isStale} — see this class's own doc
   * comment (H1). Only ever read from the template once `armed()` is defined. */
  protected readonly armedText = computed(() => armedOsdText(this.armed() ?? false, this.store.sampleAgeSeconds()));

  protected readonly rssiPercent = computed(() => this.flightState()?.rssiPercent);

  /** `TelemetrySample.extra['windSpeedMps']` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e) — `undefined` renders no chip at all. */
  protected readonly windSpeedLabel = computed(() => {
    const mps = this.store.latest()?.extra?.['windSpeedMps'];
    return mps === undefined ? undefined : `${mps.toFixed(1)}m/s`;
  });

  protected readonly windDirectionDegrees = computed(() => this.store.latest()?.extra?.['windDirectionDegrees']);

  protected readonly transportLabelText = computed(
    () => `${transportLabel(this.transport())} · ${formatLatency(this.latencySeconds())}`,
  );
}
