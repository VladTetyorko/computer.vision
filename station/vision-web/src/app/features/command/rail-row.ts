import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import type { RailRow } from './command-logic';

/**
 * One Command rail row (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3) — split out of
 * `command.html` so the identical row markup renders under both of `command.html`'s groups ("Your
 * vehicles" / "Simulated") without duplicating it, mirroring `features/fly/drone-picker-card.ts`'s
 * own precedent (a plain `[row]` input keeps this a three-file component with no wiring risk, rather
 * than an `NgTemplateOutlet` context — see that component's own doc comment for why this codebase
 * prefers the dumb-child-component shape here).
 *
 * Deliberately dumb: `CommandFacade#railGroups` already computed every field this renders (including
 * `RailRow.offlineAge`, the honest `Offline · 3d` text — see that interface's own doc comment); this
 * component issues no HTTP, holds no store, and only echoes a click as `(select)`.
 */
@Component({
  selector: 'vision-command-rail-row',
  imports: [],
  templateUrl: './rail-row.html',
  styleUrl: './rail-row.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommandRailRow {
  readonly row = input.required<RailRow>();
  readonly selected = input(false);
  readonly select = output<void>();
}
