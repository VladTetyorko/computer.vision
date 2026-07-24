import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TelemetryStore } from '../core/telemetry-store';
import { batterySeverity, telemetryAgeSeverity } from '../core/telemetry-logic';
import { formatLatency, transportLabel } from '../core/stream-info-logic';
import type { Transport } from './player';

/**
 * The Fly cockpit's overlaid OSD chip bar (docs/MVP3-PLAN.md §C-b): battery %, telemetry age
 * (three-tier color escalation), altitude, heading, and transport+behind-live — one compact row
 * meant to sit over live video and be read at a glance, not a rail widget.
 *
 * **Why a new component, not a reuse of `pages/live/telemetry-osd.ts`**: that component renders a
 * full side-rail card (a rotating compass dial, a wide battery bar, a "Position" line) sized for
 * `/live`'s collapsible rail — the right shape for a panel the operator opens to inspect, wrong for
 * chrome that must stay legible in a thin strip over the picture at all times. Every *value* this
 * component shows still comes from the exact same `TelemetryStore` instance (DI-shared from the
 * host page's own `providers`, identical convention to `TelemetryOsd`/`DetectionsStrip`) and the
 * exact same pure severity/format functions `TelemetryOsd`/`ui/stream-info-panel.ts` already use
 * (`core/telemetry-logic.ts#batterySeverity`, new `telemetryAgeSeverity` alongside it;
 * `core/stream-info-logic.ts#formatLatency`/`transportLabel`) — no new telemetry derivation beyond
 * the age-severity tiers, which are pure and unit-tested. `latencySeconds`/`transport` are inputs
 * rather than a third injected store, mirroring `ui/stream-info-panel.ts`'s identical shape: neither
 * value is store-backed anywhere, both are `ui/player.ts`'s own measurements piped up through its
 * `latencyChanged`/`transportChanged` outputs so this bar never re-measures independently.
 *
 * **Speed is not shown — an honest gap, not an oversight.** No current telemetry sample carries a
 * ground-speed reading (`core/api/models.ts#TelemetrySample` has no such field; the only "speed" in
 * the wire contract is `TelemetryPlanRequest.speedMps`, a *target* for the simulator's own route,
 * never a live measurement). Per this codebase's own "implements what current APIs already serve"
 * convention (`ui/stream-info-panel.ts`'s own resolution/FPS gap sets the precedent), the chip is
 * omitted rather than fabricated — a future cycle that adds a real speed reading server-side is
 * where this bar would grow it.
 */
@Component({
  selector: 'vision-fly-osd',
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
      } @else {
        <span class="chip dim">No telemetry</span>
      }
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
    }

    .chip.dim {
      font-family: var(--mono);
      color: rgb(231 236 243 / 78%);
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
  `,
})
export class FlyOsd {
  protected readonly store = inject(TelemetryStore);

  /** `ui/player.ts`'s own measured seconds-behind-live, piped up via its `latencyChanged` output. */
  readonly latencySeconds = input<number | null>(null);
  /** `ui/player.ts`'s own live transport, piped up via its `transportChanged` output. */
  readonly transport = input<Transport>('hls');

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

  protected readonly transportLabelText = computed(
    () => `${transportLabel(this.transport())} · ${formatLatency(this.latencySeconds())}`,
  );
}
