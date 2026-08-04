import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { Stat } from '../../shared/ui/stat';
import { ReportsFacade } from './reports-facade';

/**
 * `/manage/reports` — docs/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Inventory reports": the read-only
 * stats dashboard (KPI tiles, a per-category breakdown bar list, and an attention punch list, all
 * from `FleetController`'s summary) is functional; exportable/generated reports are not built — no
 * export endpoint exists — stated plainly via `<vision-notice>`, never a fake "Export" button.
 *
 * No role gate — same openness as `/command`, which this page's own data source already powers.
 *
 * **Page bar (docs/NAV-IA-REDESIGN-PLAN.md §2.2, docs/design/15-reports.md, wave 2).** The old
 * description ("A read-only, live view of the fleet — counts, category mix, and what needs
 * attention") didn't carry instruction beyond what "Inventory reports" plus the section headers below
 * it already say, so it's deleted outright rather than kept as a `hint` — `vision-icon` moves from a
 * hand-rolled `<h1>` prefix into the bar's own `icon` input. The `N asset(s) flagged` placeholder
 * pluralisation is fixed via `ReportsFacade.attentionSubtitle` (`pluralize`, `shared/ui/page-bar`).
 * **The `7d/30d/90d` range selector, the flight-hours trend, per-row navigation, and CSV export are
 * out of this wave's scope** (docs/NAV-IA-REDESIGN-PLAN.md's own wave-2 brief) — this page's content
 * below the bar is otherwise unchanged.
 */
@Component({
  selector: 'vision-reports',
  imports: [SectionHeader, EmptyState, Notice, PageBar, Stat],
  templateUrl: './reports.html',
  styleUrl: './reports.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReportsFacade],
})
export class ReportsPage {
  protected readonly facade = inject(ReportsFacade);
}
