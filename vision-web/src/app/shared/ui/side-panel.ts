import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { Icon } from './icon';
import { IconButton } from './icon-button';
import type { IconName } from './icon-registry';

/**
 * `vision-side-panel` — the shared drawer shell (docs/plans/done/UI-REDESIGN-PLAN.md Frozen contract F3),
 * generalizing `features/command/asset-panel.html`'s own panel-shell markup (unchanged by this wave
 * — out of scope: `src/app/features/**`) into a component every later wave's drawers can share:
 * Fly's flight/CV/detections/marks/help tool-rail drawers (Wave 2), and asset-detail's full-
 * telemetry/usage-history/hardware/attributes/pilots drill-ins (Wave 3).
 *
 * **Mounting is the host's job, not this component's**: a host page renders `@if
 * (panels.isOpen('flight')) { <vision-side-panel …> }` (or similar) around this component, so it is
 * only ever in the DOM while open — `close` just tells the host "the user asked to dismiss this",
 * the host's own `PanelState.close()` (or `toggle(id)`) call is what actually removes it. That is
 * also why focus restoration to "the invoking tool-rail button" (F3's own layout note) is the host's
 * responsibility: this component only ever sees its own lifecycle, never the button that opened it.
 *
 * **Focus-on-open**: `afterNextRender` (this codebase's established idiom for "run once the DOM this
 * component just rendered actually exists" — `shared/map/live-map.ts`/`flight-plan-dialog.ts` use the
 * identical pattern for Leaflet's own init) moves focus to the head, which carries `tabindex="-1"` in
 * the template precisely so it's a valid, if inert, focus target. **Esc**: bound directly on the
 * `<aside>` root in the template (`(keydown.escape)`) rather than a document-level listener — every
 * keydown inside this drawer bubbles up through it regardless of which inner control currently holds
 * focus, so this alone covers "Esc closes the drawer from anywhere inside it" without a
 * `DestroyRef`-managed global listener to clean up.
 */
@Component({
  selector: 'vision-side-panel',
  imports: [Icon, IconButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './side-panel.html',
  styleUrl: './side-panel.css',
})
export class SidePanel {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  /** Optional leading icon in the head — omitted entirely (no icon slot rendered) when unset. */
  readonly icon = input<IconName>();

  readonly close = output<void>();

  private readonly head = viewChild.required<ElementRef<HTMLElement>>('head');

  constructor() {
    afterNextRender(() => this.head().nativeElement.focus());
  }
}
