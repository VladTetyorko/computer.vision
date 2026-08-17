import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import type { SetupChecklistRow } from '../../core/command/setup-checklist-logic';

/**
 * `/command`'s "Set up this station" checklist (docs/plans/active/OPS-UX-PLAN.md §3 B2) — a dumb,
 * `OnPush` presentational component: every row (which ones exist, which are done, where each one
 * links) is `CommandFacade.setupChecklist`'s job (`core/command/setup-checklist-logic.ts#buildSetupChecklist`);
 * this component only renders whatever it's handed. `CommandPage` mounts it once, gated on
 * `facade.showSetupChecklist()` — see that computed's own doc comment for the ADMIN + freshness gate.
 *
 * **No dismiss button** (the plan's own explicit rule: "never dismissable-and-forgotten") — the only
 * way this stops showing is `showSetupChecklist` itself going false, which only happens once the
 * station has genuinely moved on (real users onboarded *and* at least one asset exists). A done row
 * renders as a plain tick + label, not a link — there is nothing left to click through to.
 */
@Component({
  selector: 'vision-setup-checklist',
  imports: [RouterLink, Icon],
  templateUrl: './setup-checklist.html',
  styleUrl: './setup-checklist.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SetupChecklist {
  readonly rows = input.required<readonly SetupChecklistRow[]>();
}
