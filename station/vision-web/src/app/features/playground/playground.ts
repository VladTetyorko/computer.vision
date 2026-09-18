import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SectionHeader } from '../../shared/ui/section-header';
import { Notice } from '../../shared/ui/notice';
import { EmptyState } from '../../shared/ui/empty-state';
import { FlightPlanDialog } from '../../shared/map/fleet-plan-dialog/flight-plan-dialog';
import { PlaygroundFacade } from './playground-facade';
import type { PlaygroundMode } from './playground-logic';

/**
 * `/playground` (docs/plans/active/LINK-PAIRING-PLAN.md §3.7/§4 row L4, wave L4) — the one page a
 * simulated asset can be created from: three zero-hardware modes (a moving test drone with live
 * telemetry, or a server-side video file played `direct`ly or transmitted over `rtsp`), an optional
 * drawn flight plan, and a success card linking straight to the new asset. Everything the onboarding
 * wizard's now-removed "Use a test source" tile and per-row "Test source" tile used to offer for
 * these three modes lives here instead (`features/onboarding/source-step.ts`'s own class doc
 * comment has the full history) — every request-shaping rule is unchanged, reused verbatim from
 * `core/fleet/simulation-logic.ts` through {@link PlaygroundFacade}.
 *
 * A dumb OnPush shell, per this app's Component→Facade→Store→Service layering
 * (docs/plans/done/UI-ARCHITECTURE-PLAN.md): every signal/command lives on `PlaygroundFacade`
 * (provided below); this component owns only the route-entry `effect()` that kicks off
 * `facade.load()`.
 *
 * **Degrades honestly** — `facade.load()` reads `vision.simulation.enabled` off
 * `GET /api/system/network`'s own `simulationEnabled` field (assumed for L2, not yet a real field —
 * see that field's own doc comment in `core/api/models.ts`); while it's `false`, the whole create
 * form is replaced by a plain `vision-empty`, never a blocked page or a form that fails on submit.
 * The nav entry itself (`features/hubs/nav-entries.ts`, `fleet` group) is **not** conditionally
 * hidden — every other deploy-time toggle in this app (`vision.training.enabled`, the closest
 * precedent — see `features/labeling/datasets.html`'s own `facade.training.disabled()` block) keeps
 * its nav entry always visible and degrades on the page instead, since `NAV_MODES` is static data
 * with no runtime-signal-based visibility mechanism (`nav-entries.ts`'s own class doc: "every entry
 * … renders, flat, always"); this page follows that same precedent rather than inventing a new one.
 */
@Component({
  selector: 'vision-playground',
  imports: [FormsModule, RouterLink, SectionHeader, Notice, EmptyState, FlightPlanDialog],
  templateUrl: './playground.html',
  styleUrl: './playground.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [PlaygroundFacade],
})
export class PlaygroundPage {
  protected readonly facade = inject(PlaygroundFacade);

  constructor() {
    effect(() => void this.facade.load());
  }

  protected setMode(mode: PlaygroundMode): void {
    this.facade.setMode(mode);
  }

  protected submit(): void {
    void this.facade.submit();
  }
}
