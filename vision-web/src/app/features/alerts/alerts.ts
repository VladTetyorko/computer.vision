import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Notice } from '../../shared/ui/notice';
import { EmptyState } from '../../shared/ui/empty-state';
import { EventRow } from '../../shared/ui/event-row';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import { AlertDetailPanel } from './alert-detail-panel';
import { AlertsFacade } from './alerts-facade';

/**
 * `/monitor/alerts` — docs/NAV-IA-REDESIGN-PLAN.md Wave 3 (§2.4, docs/design/08-alerts.md): a
 * two-pane triage view over the live detection-events feed. Saved threshold rules + acknowledge are
 * still not built (named follow-up, unchanged from Wave 4's original split — `eventRule` is only a
 * stream-start param today, not a stored rule), stated plainly via `<vision-notice>`.
 *
 * No role gate — any signed-in user reads the same feed Wall/the bell already show them.
 *
 * **Two-pane, not the embedded rail (docs/design/08-alerts.md's own acceptance criteria).** Rows are
 * now `vision-event-row[dense]` — single-line, ~32px tall, severity dot · label · confidence · asset
 * · relative time, no per-row `Details ›` link (the row itself selects via `?sel=`, it never
 * navigates — Wave 2's own note on this file flagged this exact rework as the next task's job). The
 * `CLOSED` chip is gone (an `OPEN` badge is signal; a universal `CLOSED` one was noise) —
 * `vision-event-row`'s own doc comment covers that for every host, not just this page. The detail
 * pane (`vision-alert-detail-panel`) renders the selected event's full metadata plus `Open cockpit`/
 * `Jump to replay` — see that component's own doc comment for why no frame/box render exists (the
 * wire contract has nothing to render).
 */
@Component({
  selector: 'vision-alerts',
  imports: [FormsModule, PageBar, Notice, EmptyState, EventRow, TwoPane, AlertDetailPanel],
  templateUrl: './alerts.html',
  styleUrl: './alerts.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AlertsFacade],
})
export class AlertsPage {
  protected readonly facade = inject(AlertsFacade);
}
