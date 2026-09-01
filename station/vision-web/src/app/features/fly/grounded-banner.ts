import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from '../../shared/ui/icon';

/**
 * The Fly cockpit's grounded banner (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "S1 gate semantics",
 * wave WB1) — a full-width strip in the same CSS-grid `banner` area as `FailsafeBanner`
 * (`cockpit.css`'s `.grid-banner`, now a flex column so the two can stack when both apply at once:
 * a grounded vehicle can still be mid-flight and trip a failsafe). Renders purely from
 * `CockpitFacade#groundedReason` — a one-line string already formatted by
 * `core/readiness/readiness-logic.ts#groundedBannerText`; this component owns no state and does no
 * parsing of its own.
 *
 * Deliberately its own component rather than a second `@if` branch inside `FailsafeBanner`:
 * grounding is a *custody* fact (a manager's decision, independent of anything the aircraft's own
 * telemetry says), not a flight-state banner — a different severity family (`--color-danger` too,
 * but `shield`-iconed rather than `alert`-iconed, since this is "you may not fly this", not "this
 * aircraft is doing something dangerous right now").
 */
@Component({
  selector: 'vision-grounded-banner',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './grounded-banner.html',
  styleUrl: './grounded-banner.css',
})
export class GroundedBanner {
  /** `GroundingStore#groundedReason` — `undefined` renders nothing. */
  readonly reason = input<string | undefined>(undefined);
}
