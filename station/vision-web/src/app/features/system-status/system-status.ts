import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { SystemEventRow } from '../../shared/ui/system-event-row';
import { healthLabel, healthSeverity } from '../../core/system-status/system-status-logic';
import type { SubsystemStatus } from '../../core/api/models';
import type { SystemEventRow as SystemEventRowModel } from '../../core/system-events/system-events-logic';
import { SystemStatusFacade } from './system-status-facade';

/**
 * `/manage/system` (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1, wave S3) — makes the running system
 * legible to its operator: an overall verdict, the per-subsystem breakdown `GET /api/system/status`
 * reports, which live transport is actually carrying updates right now, and a durable log of the
 * generic system events (S1's own store/row component, reused wholesale here).
 *
 * **Not `managerOnly`** — deliberately breaking the pattern of every other page in the `diagnostics`
 * nav group it sits beside (`features/hubs/nav-entries.ts`): an operator whose CV pipeline just died
 * needs to see *why*, not be told to find a manager. `route.ts`'s own doc comment carries the same note.
 *
 * **Degrades honestly, never fabricates**: before the very first `GET /api/system/status` succeeds,
 * every subsystem/overall field reads "Checking…"/`—`, never a guessed "OK"; a later poll failure
 * keeps the last-known table on screen with a small inline note, never blanks the page (`vision-notice`
 * with `facade.statusError()`) — see `SystemStatusStore`'s own class doc for where that rule lives.
 * The subsystem list itself is never hard-coded here — an empty `subsystems` array (a backend with no
 * providers wired) renders `<vision-empty>`, not four assumed rows.
 */
@Component({
  selector: 'vision-system-status',
  imports: [EmptyState, Notice, PageBar, SystemEventRow],
  templateUrl: './system-status.html',
  styleUrl: './system-status.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [SystemStatusFacade],
})
export class SystemStatusPage {
  protected readonly facade = inject(SystemStatusFacade);

  /** Pure lookups, called directly from the template — not store/service state, see
   *  `core/ui/architecture.spec.ts`'s own "injects only its facade" rule (this isn't an injection). */
  protected readonly healthLabel = healthLabel;
  protected readonly healthSeverity = healthSeverity;

  protected trackSubsystem(_index: number, row: SubsystemStatus): string {
    return row.id;
  }

  protected trackEvent(_index: number, row: SystemEventRowModel): string {
    return row.id;
  }
}
