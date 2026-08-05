import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { FlightBanner } from '../../core/telemetry/flight-state-logic';
import { Icon } from '../../shared/ui/icon';

/**
 * The Fly cockpit's failsafe/RTH/landing banner (docs/FC-INTEGRATIONS-PLAN.md F-d) — a full-width
 * strip that is a real CSS-grid row (`fly.css`'s `banner` area, above `main`), appearing/
 * disappearing purely from `flight-state-logic.ts#flightBanner(telemetry.latest())` — `fly.ts`
 * computes that once and passes it straight through; this component owns no state of its own, only
 * the presentation.
 *
 * **Poka-yoke**: `banner()?.text` already states what the *aircraft* is doing
 * ("FAILSAFE — RETURNING TO HOME"), never an instruction to the operator — see `flightBanner`'s own
 * doc comment for the exact rule.
 *
 * **Color — retuned to a calmer register (direct user request)**: this used to fill `failsafe`
 * solid with `--live` (`#ff3d78`, the saturated rose/magenta this app otherwise reserves for "a
 * camera is recording/pushing right now") — legible, but the loudest thing on screen in a way the
 * all-caps mono text didn't need help carrying. Both tiers now reuse this app's own established
 * *tinted-chip* formula instead of a solid fill — the same `*-soft` background + saturated token as
 * text/accent pairing `styles.css`'s `.chip.danger`/`.chip.warn` already use everywhere else (no new
 * hue invented). `failsafe` (a genuine emergency) stays one severity tier above `rth`/`landing`
 * (advisory) by staying on the `--danger` family while those two stay on `--warn` — the *hierarchy*
 * is preserved, only the loudness is turned down. A small `alert` icon plus the left accent bar
 * carry "this needs attention" without relying on a screaming fill (dataviz convention: status is
 * never color-alone).
 */
@Component({
  selector: 'vision-failsafe-banner',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (banner(); as b) {
      <div class="banner" [class]="'banner-' + b.kind" role="alert">
        <vision-icon name="alert" [size]="15" />
        <span>{{ b.text }}</span>
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
      gap: var(--space-8);
      padding: var(--space-8) var(--space-16);
      text-align: center;
      font-family: var(--mono);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      font-size: 0.95rem;
      border-left: 4px solid transparent;
    }

    /* A genuine failsafe — the most severe of the three, so it stays a notch above rth/landing's
       advisory amber on the --danger family, but as a muted, low-saturation tinted bar (identical
       pairing to styles.css's own .chip.danger) rather than a solid alarming fill. */
    .banner-failsafe {
      background: var(--color-danger-soft);
      color: var(--color-danger-text);
      border-left-color: var(--color-danger);
    }

    /* Advisory, not an emergency — this app's ordinary caution family (identical pairing to
       styles.css's own .chip.warn), same tinted-bar-plus-accent treatment as failsafe above.
       "-text", not the bare --color-warn this read until docs/VISUAL-REFRESH-PLAN.md W4's own
       sweep caught it — the same AA-contrast fix .chip.warn already got in Wave 0 (see that
       rule's own comment in styles.css): --color-warn is tuned to read as a fill/border, not as
       text on its own "-soft" tint, and in light theme specifically that pairing falls under 4.5:1.
       border-left-color stays on the bare --color-warn deliberately — a border reads fine at
       that same tuning, only text-on-fill was the AA problem. */
    .banner-rth,
    .banner-landing {
      background: var(--color-warn-soft);
      color: var(--color-warn-text);
      border-left-color: var(--color-warn);
    }
  `,
})
export class FailsafeBanner {
  readonly banner = input<FlightBanner | null>(null);
}
