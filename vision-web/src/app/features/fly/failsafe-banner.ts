import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { FlightBanner } from '../../core/telemetry/flight-state-logic';

/**
 * The Fly cockpit's failsafe/RTH/landing banner (docs/FC-INTEGRATIONS-PLAN.md F-d) — a full-width
 * strip laid over the video, above `fly.css`'s ordinary HUD layer (`z-index: 6`) but below its two
 * modal overlays (`hud-confirm`/`hud-shortcuts`, `z-index: 20`), appearing/disappearing purely from
 * `flight-state-logic.ts#flightBanner(telemetry.latest())` — `fly.ts` computes that once and passes
 * it straight through; this component owns no state of its own, only the presentation.
 *
 * **Poka-yoke**: `banner()?.text` already states what the *aircraft* is doing
 * ("FAILSAFE — RETURNING TO HOME"), never an instruction to the operator — see `flightBanner`'s own
 * doc comment for the exact rule. Color follows the same severity split as everywhere else in this
 * app: `failsafe` is the one thing `--live` red is reserved for; `rth`/`landing` are advisory, amber.
 */
@Component({
  selector: 'vision-failsafe-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (banner(); as b) {
      <div class="banner" [class]="'banner-' + b.kind" role="alert">
        {{ b.text }}
      </div>
    }
  `,
  styles: `
    .banner {
      width: 100%;
      min-height: 2.2rem;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0.5rem 1rem;
      text-align: center;
      font-family: var(--mono);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      font-size: 0.95rem;
    }

    /* --live is reserved for exactly this — a genuine failsafe, nothing else (see styles.css's
       own token doc comment). */
    .banner-failsafe {
      background: var(--live);
      color: #1a0009;
    }

    /* Advisory, not an emergency — amber, same as this app's other RTH/landing/degraded-fix signals. */
    .banner-rth,
    .banner-landing {
      background: var(--warn);
      color: #241a02;
    }
  `,
})
export class FailsafeBanner {
  readonly banner = input<FlightBanner | null>(null);
}
