import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import type { AssetSummary } from '../../core/api/models';
import type { PilotAssetAssignment } from '../../core/roster/roster-pivot-logic';

/**
 * `vision-pilot-assignments-panel` — `/manage/roster`'s "By pilot" detail pane
 * (docs/NAV-IA-REDESIGN-PLAN.md §2.4, docs/design/13-roster.md — task 3: "the detail pane does the
 * assign/unassign editing"). The pilot-scoped twin of `features/asset-detail/pilots-card.ts`'s
 * asset-scoped editor: same job (assign/unassign against `AssignmentController`), same "one
 * assignment service, two entry points" the task brief asks for — transposed to list *this pilot's*
 * assets instead of *this asset's* pilots.
 *
 * **Dumb, unlike `pilots-card.ts`.** That component self-fetches (it injects `VisionApi` directly,
 * a non-routed presentational child per `architecture.spec.ts`'s own carve-out) because the page that
 * hosts it (`AssetDetailPage`) has no reason to already hold one asset's pilot list itself. Here the
 * opposite is true: `RosterFacade` already loads every asset's `AssignedPilot[]` up front (it needs
 * the same map for the "By asset" pivot's own row badges), so re-fetching per pilot from inside this
 * component would just duplicate that work. This component instead takes the already-loaded
 * assignment list and the already-computed "assets this pilot could still be added to" list as plain
 * inputs, and reports the two mutations upward — `RosterFacade` is the one place that actually calls
 * `VisionApi.assignPilot`/`unassignPilot` for both pivots, so the two directions never drift into two
 * separate assignment implementations.
 */
@Component({
  selector: 'vision-pilot-assignments-panel',
  imports: [FormsModule, SectionHeader, EmptyState],
  templateUrl: './pilot-assignments-panel.html',
  styleUrl: './pilot-assignments-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PilotAssignmentsPanel {
  readonly displayName = input.required<string>();
  readonly assignments = input.required<readonly PilotAssetAssignment[]>();
  /** Assets not yet assigned to this pilot — the "assign" picker's own options. */
  readonly assignableAssets = input.required<readonly AssetSummary[]>();
  /** Disables both the picker and every row's Unassign button while a mutation is in flight. */
  readonly busy = input(false);

  readonly assign = output<string>();
  readonly unassign = output<string>();

  protected readonly pickAssetId = signal('');

  protected emitAssign(): void {
    const assetId = this.pickAssetId();
    if (!assetId || this.busy()) {
      return;
    }
    this.assign.emit(assetId);
    this.pickAssetId.set('');
  }
}
