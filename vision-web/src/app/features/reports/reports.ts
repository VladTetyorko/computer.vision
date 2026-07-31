import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { Stat } from '../../shared/ui/stat';
import { ReportsFacade } from './reports-facade';

/**
 * `/manage/reports` — docs/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Inventory reports": the read-only
 * stats dashboard (KPI tiles, a per-category breakdown bar list, and an attention punch list, all
 * from `FleetController`'s summary) is functional; exportable/generated reports are not built — no
 * export endpoint exists — stated plainly via `<vision-notice>`, never a fake "Export" button.
 *
 * No role gate — same openness as `/command`, which this page's own data source already powers.
 */
@Component({
  selector: 'vision-reports',
  imports: [Icon, SectionHeader, EmptyState, Notice, Stat],
  templateUrl: './reports.html',
  styleUrl: './reports.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReportsFacade],
})
export class ReportsPage {
  protected readonly facade = inject(ReportsFacade);
}
