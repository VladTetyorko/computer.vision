import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TelemetryStore } from '../../core/telemetry-store';

/** Battery thresholds for the bar's color, roughly matching common flight-controller OSDs. */
const BATTERY_LOW_PERCENT = 45;
const BATTERY_CRITICAL_PERCENT = 20;

/**
 * The live telemetry HUD strip for `/live/:deviceId` (docs/CYCLES-PLAN.md §2, UX-DESIGN §5.2).
 *
 * Purely presentational: every value is a `computed` over the `TelemetryStore` injected from
 * `LivePage`'s DI (that component lists `TelemetryStore` in its own `providers`, so this child
 * resolves the same instance rather than polling independently). No inputs, no state of its own
 * — `OnPush` + signals do the rest.
 *
 * Sample age is the one value that must visibly change even between polls (a 6s-old reading
 * should stop looking "fresh" without waiting for the next 2s poll), which is why
 * `TelemetryStore.sampleAgeSeconds` ticks its own 1s clock independent of the poll interval.
 */
@Component({
  selector: 'vision-telemetry-osd',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="osd" [class.stale]="store.stale()">
      <div class="item">
        <span class="label">Position</span>
        <span class="value mono">{{ coords() }}</span>
      </div>

      <div class="item">
        <span class="label">Altitude</span>
        <span class="value">{{ altitudeLabel() }}</span>
      </div>

      <div class="item compass-item">
        <span class="label">Heading</span>
        <div class="compass" [attr.aria-label]="'Heading ' + headingLabel()">
          <span class="tick n">N</span>
          <span class="tick e">E</span>
          <span class="tick s">S</span>
          <span class="tick w">W</span>
          <div class="needle" [style.transform]="needleTransform()"></div>
        </div>
        <span class="value">{{ headingLabel() }}</span>
      </div>

      <div class="item battery-item">
        <span class="label">Battery</span>
        <div class="battery-bar">
          <div class="battery-fill" [class]="batteryClass()" [style.width.%]="batteryFillPercent()"></div>
        </div>
        <span class="value">{{ batteryLabel() }}</span>
      </div>

      <div class="item">
        <span class="label">Sample</span>
        <span class="value" [class.stale-text]="store.stale()">{{ ageLabel() }}</span>
      </div>
    </div>
  `,
  styles: `
    .osd {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 1.1rem;
      padding: 0.55rem 0.85rem;
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      font-size: 0.82rem;
    }

    .osd.stale {
      border-color: var(--danger);
    }

    .item {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      min-width: 0;
    }

    .label {
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
    }

    .value {
      font-weight: 600;
    }

    .stale-text {
      color: var(--danger);
    }

    .compass-item {
      flex-direction: row;
      align-items: center;
      gap: 0.5rem;
    }

    .compass {
      position: relative;
      width: 34px;
      height: 34px;
      border-radius: 50%;
      border: 1px solid var(--border-strong);
      background: rgb(0 0 0 / 25%);
      flex: none;
    }

    .tick {
      position: absolute;
      font-size: 0.5rem;
      color: var(--text-faint);
      transform: translate(-50%, -50%);
    }

    .tick.n {
      top: 3px;
      left: 50%;
    }

    .tick.e {
      top: 50%;
      right: -1px;
      left: auto;
      transform: translate(0, -50%);
    }

    .tick.s {
      bottom: 3px;
      left: 50%;
      top: auto;
    }

    .tick.w {
      top: 50%;
      left: 1px;
      transform: translate(0, -50%);
    }

    .needle {
      position: absolute;
      inset: 0;
      transform-origin: 50% 50%;
      transition: transform 0.4s ease;
    }

    .needle::before {
      content: '';
      position: absolute;
      top: 3px;
      left: 50%;
      width: 0;
      height: 0;
      border-left: 4px solid transparent;
      border-right: 4px solid transparent;
      border-bottom: 11px solid var(--accent);
      transform: translateX(-50%);
    }

    .battery-item {
      flex-direction: row;
      align-items: center;
      gap: 0.5rem;
      min-width: 130px;
    }

    .battery-bar {
      width: 60px;
      height: 10px;
      border-radius: 4px;
      background: rgb(255 255 255 / 12%);
      overflow: hidden;
      flex: none;
    }

    .battery-fill {
      height: 100%;
      transition: width 0.4s ease;
    }

    .battery-fill.ok {
      background: var(--ok);
    }

    .battery-fill.low {
      background: var(--warn);
    }

    .battery-fill.critical {
      background: var(--danger);
    }

    .battery-fill.unknown {
      background: var(--text-faint);
    }
  `,
})
export class TelemetryOsd {
  protected readonly store = inject(TelemetryStore);

  protected readonly coords = computed(() => {
    const latest = this.store.latest();
    if (latest?.latitude === undefined || latest.longitude === undefined) {
      return '—';
    }
    return `${latest.latitude.toFixed(5)}, ${latest.longitude.toFixed(5)}`;
  });

  protected readonly altitudeLabel = computed(() => {
    const meters = this.store.latest()?.altitudeMeters;
    return meters === undefined ? '—' : `${meters.toFixed(0)} m`;
  });

  private readonly headingDegrees = computed(() => this.store.latest()?.headingDegrees);

  protected readonly headingLabel = computed(() => {
    const heading = this.headingDegrees();
    return heading === undefined ? '—' : `${heading.toFixed(0)}°`;
  });

  protected readonly needleTransform = computed(() => `rotate(${this.headingDegrees() ?? 0}deg)`);

  private readonly batteryPercent = computed(() => this.store.latest()?.batteryPercent);

  protected readonly batteryFillPercent = computed(() => this.batteryPercent() ?? 0);

  protected readonly batteryLabel = computed(() => {
    const percent = this.batteryPercent();
    return percent === undefined ? '—' : `${percent.toFixed(0)}%`;
  });

  protected readonly batteryClass = computed(() => {
    const percent = this.batteryPercent();
    if (percent === undefined) {
      return 'unknown';
    }
    if (percent <= BATTERY_CRITICAL_PERCENT) {
      return 'critical';
    }
    return percent <= BATTERY_LOW_PERCENT ? 'low' : 'ok';
  });

  protected readonly ageLabel = computed(() => {
    const age = this.store.sampleAgeSeconds();
    return age === undefined ? '—' : `${age.toFixed(0)}s ago`;
  });
}
