import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Notice } from '../../shared/ui/notice';
import { EventsRail } from '../../shared/ui/events-rail';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { AlertsFacade } from './alerts-facade';

/**
 * `/monitor/alerts` — docs/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Alerts center": the live
 * detection-events feed (`EventController`/`core/events/events-store.ts`, reused verbatim via
 * `<vision-events-rail>` — the identical component `Wall`/the header bell already render) is
 * functional; saved threshold rules and acknowledge are not built (named follow-up: `EventRule`
 * persistence + acknowledge state — `eventRule` is only a stream-start param today, not a stored
 * rule) — stated plainly via `<vision-notice>`, never a fake toggle/control standing in for it.
 *
 * No role gate — any signed-in user reads the same feed Wall/the bell already show them.
 *
 * **`page-head` → `vision-page-bar`** (docs/NAV-IA-REDESIGN-PLAN.md §2.2, docs/design/08-alerts.md):
 * the subtitle is deleted outright ("Alerts center" is self-explanatory now the sidebar marks the
 * active page). The count chip reads `AlertsFacade.events` (the shared `EventsStore`) directly for
 * the page's own total; the label/asset filters and the "Events / N" header docs/design/08-alerts.md
 * shows living in the bar are `<vision-events-rail>`'s own internal state
 * (`shared/ui/events-rail.{ts,html}`) — out of this task's file scope (that component is shared with
 * `Wall`/the header bell) — so they stay inside the rail's own card for this wave; only this page's
 * own chrome (the title row, the notice) moved.
 */
@Component({
  selector: 'vision-alerts',
  imports: [PageBar, Notice, EventsRail],
  templateUrl: './alerts.html',
  styleUrl: './alerts.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AlertsFacade],
})
export class AlertsPage {
  protected readonly facade = inject(AlertsFacade);
}
