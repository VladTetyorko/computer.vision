import { ChangeDetectionStrategy, Component, computed, effect, inject, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { IconButton } from '../../shared/ui/icon-button';
import { SectionHeader } from '../../shared/ui/section-header';
import { RcInputService } from '../../core/rc/rc-input.service';
import { axisToPercent, defaultAxisLabel, defaultButtonLabel, isButtonOn } from '../../core/rc/rc-input-logic';
import { actionLabel, controlKey, controlLabel, positionOf } from '../../core/rc/control-action-logic';
import {
  MAX_RC_CHANNEL,
  actionAt,
  draftKey,
  draftLabel,
  kindsFor,
  movedControl,
  parameterKindOf,
  positionsOf,
  type ControlDraft,
} from '../../core/rc/controller-setup-logic';
import { ControllerSetupFacade } from './controller-setup-facade';
import type { ControlAction, SwitchPosition } from '../../core/api/models';

/** RC channels a control may be bound to — the domain's own `[1,16]`, as a list a picker can render. */
const RC_CHANNELS = Array.from({ length: MAX_RC_CHANNEL }, (_, i) => i + 1);

/**
 * `vision-all-controls` — the flat, all-at-once editor the "All controls ▾" toggle opens under the
 * wizard (docs/plans/active/CONTROLLER-UX-PLAN.md §2.3 item 6). This is the original `/manage/controller`
 * page's own editor card, moved here verbatim: same live transmitter inventory + Detect gesture, same
 * one-control-card-per-row layout, same rules. The wizard (`wizard-step.ts`) is the on-ramp for
 * setting a layout up the first time; this is the escape hatch for an operator who already knows
 * their transmitter and wants to see and change every row at once, or reach a control the wizard's
 * fixed step list does not visit (a second aux switch, an odd binding a previous session left).
 *
 * Injects `ControllerSetupFacade`/`RcInputService` directly rather than taking them as inputs — both
 * are provided on the routed page (`ControllerSetupPage`) and this component only ever renders inside
 * that page's template, so the same singletons resolve here via ordinary hierarchical DI.
 * `core/ui/architecture.spec.ts`'s injection guard only scans the routed page file itself, not its
 * children, so this does not weaken that guard — the page still owns navigation/HTTP-adjacent state,
 * this is its own view fragment.
 */
@Component({
  selector: 'vision-all-controls',
  imports: [FormsModule, EmptyState, Notice, IconButton, SectionHeader],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './all-controls.html',
  styleUrl: './all-controls.css',
})
export class AllControls {
  protected readonly facade = inject(ControllerSetupFacade);
  protected readonly rc = inject(RcInputService);

  /** A built-in layout's own "Make a copy" — same event the wizard's per-step U12 action emits, so
   * the page has one `copySelected()` handler for both. */
  readonly copyRequested = output<void>();

  protected readonly axisLabel = defaultAxisLabel;
  protected readonly buttonLabel = defaultButtonLabel;
  protected readonly axisToPercent = axisToPercent;
  protected readonly isOn = isButtonOn;
  protected readonly actionLabel = actionLabel;
  protected readonly controlLabel = controlLabel;
  protected readonly draftKey = draftKey;
  protected readonly draftLabel = draftLabel;
  protected readonly actionAt = actionAt;
  protected readonly channels = RC_CHANNELS;

  /** The "flick the control you mean" gesture: the readings when it started, or `undefined` when off. */
  private readonly learnBaseline = signal<{ axes: readonly number[]; buttons: readonly number[] } | undefined>(
    undefined,
  );
  protected readonly learning = computed(() => this.learnBaseline() !== undefined);

  constructor() {
    effect(() => {
      const axes = this.rc.axes();
      const buttons = this.rc.buttons();
      untracked(() => this.captureLearned(axes, buttons));
    });
  }

  // --- Live readouts ---------------------------------------------------------------------------

  /** One control's current position, quantized exactly as the dispatcher will quantize it. */
  protected positionNow(control: ControlDraft): SwitchPosition {
    const values = control.source === 'AXIS' ? this.rc.axes() : this.rc.buttons();
    return positionOf(control.source, control.kind, values[control.sourceIndex] ?? 0);
  }

  /** Whether a physical control already has a row, so the inventory can say so rather than duplicate it. */
  protected isBound(source: 'AXIS' | 'BUTTON', index: number): boolean {
    const key = controlKey(source, index);
    return (this.facade.draft()?.controls ?? []).some((c) => draftKey(c) === key);
  }

  // --- Editing ---------------------------------------------------------------------------------

  protected kinds(control: ControlDraft) {
    return kindsFor(this.facade.catalog(), control.source);
  }

  protected positions(control: ControlDraft) {
    return positionsOf(this.facade.catalog(), control.kind);
  }

  protected parameterKind(action: ControlAction | undefined) {
    return parameterKindOf(this.facade.catalog(), action);
  }

  /**
   * The aux-function menu, with the bound number prepended when it is not on it — a layout
   * configured against a different `vision.control.aux-functions` menu still shows what it is set
   * to, rather than silently reading as the first entry.
   */
  protected auxOptions(current: string | null | undefined) {
    const menu = this.facade.catalog()?.auxFunctions ?? [];
    if (!current || menu.some((f) => String(f.number) === current)) {
      return menu;
    }
    return [{ number: Number(current), label: `Function ${current}` }, ...menu];
  }

  protected onActionChange(key: string, position: SwitchPosition, value: string): void {
    this.facade.setPositionAction(key, position, value === '' ? undefined : (value as ControlAction));
  }

  // --- Detect ----------------------------------------------------------------------------------

  protected toggleLearn(): void {
    this.learnBaseline.set(
      this.learning() ? undefined : { axes: [...this.rc.axes()], buttons: [...this.rc.buttons()] },
    );
  }

  private captureLearned(axes: readonly number[], buttons: readonly number[]): void {
    const baseline = this.learnBaseline();
    if (!baseline) {
      return;
    }
    const moved = movedControl(axes, buttons, baseline);
    if (moved) {
      this.learnBaseline.set(undefined);
      this.facade.addControl(moved.source, moved.sourceIndex);
    }
  }
}
