import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { armFinalConfirmLabel, armWarningMessage } from './flight-command-panel-logic';

export type ArmConfirmStage = 'warn' | 'final';

/**
 * The Arm command's own confirm modal (docs/DRONE-INFRA-PLAN.md I-e Stage 2 — "arming spins
 * propellers, it is the highest-danger action in the app… materially higher-friction than RTL's").
 * A **separate component from `shared/ui/confirm-dialog.ts`**, not a variant/input flag on it — Arm
 * is the one action in this app that needs to feel categorically more dangerous than everything
 * `<vision-confirm-dialog>` already guards (Stop stream, Bring home, Mode, Disarm all share that one
 * plain modal), and folding a two-stage flow into the generic single-purpose dialog would complicate
 * its one job for every other caller.
 *
 * **Two ways this is higher-friction than `<vision-confirm-dialog>`, both deliberate:**
 * 1. **A second explicit stage, not one click.** Opens on `'warn'` (`{{ armWarningMessage }}` +
 *    "Continue", a plain neutral button — not yet the dangerous action) and only advances to
 *    `'final'` (the actual `armFinalConfirmLabel` button, filled `--live`) on that click; `Cancel` is
 *    reachable from both stages. A misclick/reflexive double-click on open lands on "Continue", not
 *    "Arm" — the dangerous button never occupies the position the plain confirm's own single button
 *    would.
 * 2. **Reserves the app's full, undiluted `--live` red** (docs/DRONE-INFRA-PLAN.md I-e Stage 2's own
 *    styling instruction) — the same hue `features/fly/failsafe-banner.ts`'s genuine-failsafe strip
 *    uses, and nothing else in this app's button chrome does; every other danger-adjacent control
 *    (`btn.danger` — Stop stream, Bring home, Mode/Disarm's own `<vision-confirm-dialog>`) stays the
 *    calmer, transparent `--danger` outline. Arm is now the one *other* place `--live` appears at
 *    full strength, by design — a step up from `--danger`, not a repaint of it.
 *
 * `assetDisplayName` is the only input needed — this dialog carries no asset-specific data beyond
 * the name embedded in its own copy (`flight-command-panel-logic.ts#armWarningMessage`/
 * `armFinalConfirmLabel`). `busy` disables every button in both stages (covers "the request is in
 * flight"); mirrors `confirm-dialog.ts`'s own no-backdrop-click/no-`Escape` poka-yoke rule — every
 * dismissal here is an explicit `Cancel` click too.
 */
@Component({
  selector: 'vision-arm-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './arm-confirm-dialog.html',
  styleUrl: './arm-confirm-dialog.css',
})
export class ArmConfirmDialog {
  readonly assetDisplayName = input.required<string>();
  /** Disables every button in both stages — see this class's own doc comment. */
  readonly busy = input<boolean>(false);

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  protected readonly stage = signal<ArmConfirmStage>('warn');

  protected readonly warningMessage = computed(() => armWarningMessage(this.assetDisplayName()));
  protected readonly finalLabel = computed(() => armFinalConfirmLabel(this.assetDisplayName()));

  protected continueToFinalStage(): void {
    this.stage.set('final');
  }
}
