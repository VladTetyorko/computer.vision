import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { EmptyState } from '../../shared/ui/empty-state';
import { AuditFacade } from './audit-facade';

/**
 * `/monitor/audit` — the manager's fleet-wide accountability surface (docs/plans/done/OPS-UX-PLAN.md §3 B1,
 * docs/conclusions/OPS-UX-REVIEW.md §U3: "the cheapest high-value item in the whole review: the
 * backend is done"). Reads `GET /api/audit` — built, audited across every context, and, before this
 * task, called from no page in this SPA at all.
 *
 * Route-guarded (`core/org/org-guard.ts`, the same ADMIN/MANAGER guard `/org` and `/manage/roster`
 * use) and `managerOnly` in the nav (`features/hubs/nav-entries.ts`) — both mirror the backend's own
 * `canManageOrg()` gate on `AuditController#list`, so the two doors agree. **Dumb by design**
 * (docs/plans/done/UI-ARCHITECTURE-PLAN.md): every read-model and command lives on `AuditFacade`.
 *
 * A genuine `<table>` (frontend-style §5's "scan → compare → act" form), not a card/list feed like
 * `/activity` — a security/accountability review is exactly the "compare many rows at once" job a
 * table serves and a day-grouped card list does not. Time leads the row (mirrors `/activity`'s own
 * "an audit log is read by 'when'" choice) rather than the generic entity-table's name-first column
 * order — this is a chronological log, not an inventory of named things.
 */
@Component({
  selector: 'vision-audit',
  imports: [FormsModule, PageBar, EmptyState],
  templateUrl: './audit.html',
  styleUrl: './audit.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AuditFacade],
})
export class AuditPage {
  protected readonly facade = inject(AuditFacade);
}
