import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { AssetSummary, DiscoveryCandidate } from '../../core/api/models';
import { discoveryMethodLabel } from '../../core/discovery/discovery-inbox-logic';

/**
 * The Found-devices "Attach to existing asset" dialog (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * §11, wave Z2d) — a plain picker over the fleet's existing assets. `DiscoveryInboxStore#attach`
 * does the actual two-call work (`registerDevice` from the candidate's own suggested stream, then
 * the *existing* `assignDevice`/`POST /api/assets/{id}/devices`); this component only collects
 * which asset. No category/protocol/URI field here — every connection fact is already known from
 * the candidate, never retyped (the whole point of the inbox over the manual wizard).
 *
 * Same `.backdrop`/`.dialog.card` modal shape as `add-candidate-dialog.ts`/`shared/ui/confirm-dialog.ts`.
 */
@Component({
  selector: 'vision-attach-candidate-dialog',
  imports: [FormsModule],
  templateUrl: './attach-candidate-dialog.html',
  styleUrl: './attach-candidate-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttachCandidateDialog {
  readonly candidate = input.required<DiscoveryCandidate>();
  readonly assets = input<readonly AssetSummary[]>([]);
  readonly busy = input(false);

  readonly attached = output<string>();
  readonly cancelled = output<void>();

  protected readonly methodLabel = discoveryMethodLabel;

  protected readonly selectedAssetId = signal('');
  protected readonly canSubmit = computed(() => this.selectedAssetId().length > 0);

  protected submit(): void {
    const assetId = this.selectedAssetId();
    if (assetId) {
      this.attached.emit(assetId);
    }
  }
}
