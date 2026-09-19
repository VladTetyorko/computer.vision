import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { batterySeverity } from '../../core/telemetry/telemetry-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * The live telemetry HUD strip for `/live/:deviceId` (docs/main/CYCLES-PLAN.md §2, UX-DESIGN §5.2).
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
      gap: var(--space-16);
      padding: var(--space-8) var(--space-16);
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      font-size: 0.82rem;
    }

    .osd.stale {
      border-color: var(--color-danger);
    }

    .item {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      min-width: 0;
    }

    .label {
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
    }

    .value {
      /* docs/plans/done/UX-REWORK-PLAN.md §U-b item 3 — every numeric readout on this HUD (altitude, heading,
         battery, sample age), not just the "Position" line, which already carried its own explicit
         ".mono" class in the template alongside this one. */
      font-family: var(--mono);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    .stale-text {
      color: var(--color-danger);
    }

    .compass-item {
      flex-direction: row;
      align-items: center;
      gap: var(--space-8);
    }

    .compass {
      position: relative;
      width: 34px;
      height: 34px;
      border-radius: 50%;
      border: 1px solid var(--border-strong);
      /* Bespoke 25% black wash for the dial face — much lighter than either --scrim (55%) or
         --scrim-strong (70%), and not a video/map compositing overlay, so neither canonical
         scrim step reproduces this look without visibly darkening the dial
         (docs/plans/done/STYLE-TOKENS-PLAN.md §Geometry note's "bespoke opacity... genuinely needed"
         exception). Left literal; flagged. */
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
      border-bottom: 11px solid var(--color-info);
      transform: translateX(-50%);
    }

    .battery-item {
      flex-direction: row;
      align-items: center;
      gap: var(--space-8);
      min-width: 130px;
    }

    .battery-bar {
      width: 60px;
      height: 10px;
      border-radius: 4px;
      background: var(--hairline);
      overflow: hidden;
      flex: none;
    }

    .battery-fill {
      height: 100%;
      transition: width 0.4s ease;
    }

    .battery-fill.ok {
      background: var(--color-success);
    }

    .battery-fill.low {
      background: var(--color-warn);
    }

    .battery-fill.critical {
      background: var(--color-danger);
    }

    .battery-fill.unknown {
      background: var(--text-faint);
    }
  `,
})
export class TelemetryOsd {
  protected readonly store = inject(TelemetryFacade);

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

  protected readonly batteryClass = computed(() => batterySeverity(this.batteryPercent()));

  protected readonly ageLabel = computed(() => {
    const age = this.store.sampleAgeSeconds();
    return age === undefined ? '—' : `${humanAge(age)} ago`;
  });
}
