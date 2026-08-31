import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, input, linkedSignal, output, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { Category, DiscoveryCandidate } from '../../core/api/models';
import { canSubmitRegisterDraft, defaultRegisterDraft, discoveryMethodLabel, type RegisterDraft } from '../../core/discovery/discovery-inbox-logic';

/**
 * The Found-devices "Add" confirm dialog (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11,
 * wave Z2d) — deliberately **not** the full onboarding wizard: one prefilled name field, one
 * category picker, Confirm. Everything the candidate already knows (name, category guess) is
 * filled in before the operator ever sees this; the only thing left to decide is whether either
 * default needs changing. A candidate carrying no `suggestedCategory` opens with the picker blank,
 * forcing an explicit pick rather than silently guessing the first category in the list.
 *
 * Same `.backdrop`/`.dialog.card` modal shape as `shared/ui/confirm-dialog.ts`
 * (`role="dialog"`, focus-trap-free but Escape-swallowed so it doesn't reach a host drawer/panel
 * underneath) — that component itself couldn't be reused verbatim since it has no form fields, only
 * a message.
 */
@Component({
  selector: 'vision-add-candidate-dialog',
  imports: [FormsModule],
  templateUrl: './add-candidate-dialog.html',
  styleUrl: './add-candidate-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddCandidateDialog {
  readonly candidate = input.required<DiscoveryCandidate>();
  readonly categories = input<readonly Category[]>([]);
  readonly busy = input(false);

  readonly submitted = output<RegisterDraft>();
  readonly cancelled = output<void>();

  protected readonly methodLabel = discoveryMethodLabel;

  /** Re-seeds only if this dialog instance were ever rebound to a different candidate — in
   *  practice it never is: `FoundDevices` destroys/recreates this dialog per open (`@if`), so this
   *  is really just "the form's initial value", written with `linkedSignal` per this app's own
   *  `MODULE.md` Gotchas convention for a draft seeded from an input. */
  protected readonly draft = linkedSignal<RegisterDraft>(() => defaultRegisterDraft(this.candidate()));

  protected readonly canSubmit = computed(() => canSubmitRegisterDraft(this.draft()));

  private readonly nameField = viewChild<ElementRef<HTMLInputElement>>('nameField');

  constructor() {
    afterNextRender(() => this.nameField()?.nativeElement.focus());
  }

  protected setDisplayName(value: string): void {
    this.draft.update((draft) => ({ ...draft, displayName: value }));
  }

  protected setCategory(value: string): void {
    this.draft.update((draft) => ({ ...draft, category: value }));
  }

  protected submit(): void {
    if (canSubmitRegisterDraft(this.draft())) {
      this.submitted.emit(this.draft());
    }
  }
}
