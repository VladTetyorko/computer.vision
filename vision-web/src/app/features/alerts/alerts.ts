import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { Notice } from '../../shared/ui/notice';
import { EventsRail } from '../../shared/ui/events-rail';
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
 */
@Component({
  selector: 'vision-alerts',
  imports: [Icon, Notice, EventsRail],
  templateUrl: './alerts.html',
  styleUrl: './alerts.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AlertsFacade],
})
export class AlertsPage {
  protected readonly facade = inject(AlertsFacade);
}
