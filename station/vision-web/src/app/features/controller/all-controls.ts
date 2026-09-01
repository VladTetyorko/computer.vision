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
import {
  AUTODETECT_OFF,
  beginAutodetect,
  stepAutodetect,
  type AutodetectState,
} from '../../core/rc/control-autodetect';
import {
  GUIDED_OFF,
  advanceGuided,
  beginGuided,
  currentStep,
  guidedSteps,
  isGuidedDone,
  skipGuided,
  type GuidedState,
} from '../../core/rc/guided-setup';
import { ControllerSetupFacade } from './controller-setup-facade';
import type { ControlAction, ControlFunction, SwitchPosition } from '../../core/api/models';

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

  /** The Autodetect sweep (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15) — one flick per
   * control, until the operator stops it. Distinct from `learning()` above: that single-shot Detect
   * names one control and stops; this keeps going, already declaring each one as what it saw. */
  private readonly autodetect = signal<AutodetectState>(AUTODETECT_OFF);
  protected readonly detecting = computed(() => this.autodetect().phase !== 'off');
  /** How many rows this run has claimed, so the button can say it is getting somewhere. */
  protected readonly detectedCount = signal(0);
  /** The control most recently named by either sweep, so the row it just claimed can be called out. */
  protected readonly lastDetected = signal<string | undefined>(undefined);

  /** The guided "tell me your sticks" run (wave C15) — function-first, the inverse of Autodetect. */
  private readonly guided = signal<GuidedState>(GUIDED_OFF);
  protected readonly guiding = computed(() => this.guided().phase !== 'off');
  protected readonly guidedStep = computed(() => currentStep(this.guided()));
  protected readonly guidedDone = computed(() => isGuidedDone(this.guided()));
  /** Which question of how many, so the run says how much is left rather than only what is next. */
  protected readonly guidedProgress = computed(() => {
    const state = this.guided();
    return { at: Math.min(state.index + 1, state.steps.length), of: state.steps.length };
  });
  protected readonly guidedBound = signal(0);

  constructor() {
    effect(() => {
      const axes = this.rc.axes();
      const buttons = this.rc.buttons();
      untracked(() => {
        this.captureLearned(axes, buttons);
        this.pumpGuided(axes, buttons);
        this.pumpAutodetect(axes, buttons);
      });
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
    if (!this.learning()) {
      this.autodetect.set(AUTODETECT_OFF);
      this.guided.set(GUIDED_OFF);
    }
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

  // --- Autodetect (wave C15) — a sweep, one flick per control ------------------------------------

  /**
   * Starts or stops a sweep. Mutually exclusive with the single-shot Detect above and with Guide me
   * below: all three watch the same stream for the same movement, and two things claiming one flick
   * is how an operator ends up with a row they did not ask for.
   */
  protected toggleAutodetect(): void {
    this.learnBaseline.set(undefined);
    this.guided.set(GUIDED_OFF);
    if (this.detecting()) {
      this.autodetect.set(AUTODETECT_OFF);
      return;
    }
    this.detectedCount.set(0);
    this.lastDetected.set(undefined);
    this.autodetect.set(beginAutodetect({ axes: this.rc.axes(), buttons: this.rc.buttons() }));
  }

  private pumpAutodetect(axes: readonly number[], buttons: readonly number[]): void {
    const state = this.autodetect();
    if (state.phase === 'off') {
      return;
    }
    const step = stepAutodetect(state, { axes, buttons });
    this.autodetect.set(step.state);
    if (step.learned) {
      const { source, sourceIndex, kind } = step.learned;
      this.lastDetected.set(controlKey(source, sourceIndex));
      if (this.facade.addControl(source, sourceIndex, kind)) {
        this.detectedCount.update((n) => n + 1);
      }
    }
  }

  // --- Guide me (wave C15) — function-first, the inverse of Autodetect ---------------------------

  /**
   * The questions to ask, taken from the built-in for the layout's own vehicle kind — never from a
   * list in this component, which would be a second opinion about what each vehicle flies with.
   */
  private stepsForDraft() {
    const kind = this.facade.draft()?.kind;
    const builtIn = this.facade.profiles().find((p) => p.source === 'BUILT_IN' && p.kind === kind);
    const labels = new Map<ControlFunction, string>(
      (this.facade.catalog()?.functions ?? []).map((f) => [f.name, f.label]),
    );
    return guidedSteps(builtIn, labels);
  }

  protected toggleGuided(): void {
    this.learnBaseline.set(undefined);
    this.autodetect.set(AUTODETECT_OFF);
    if (this.guiding()) {
      this.guided.set(GUIDED_OFF);
      return;
    }
    this.guidedBound.set(0);
    this.lastDetected.set(undefined);
    this.guided.set(beginGuided(this.stepsForDraft(), { axes: this.rc.axes(), buttons: this.rc.buttons() }));
  }

  protected skipGuidedStep(): void {
    this.guided.set(skipGuided(this.guided(), { axes: this.rc.axes(), buttons: this.rc.buttons() }));
  }

  private pumpGuided(axes: readonly number[], buttons: readonly number[]): void {
    const state = this.guided();
    if (state.phase === 'off') {
      return;
    }
    const result = advanceGuided(state, { axes, buttons });
    this.guided.set(result.state);
    if (result.learned) {
      const { source, sourceIndex, step } = result.learned;
      this.facade.assignFunction(source, sourceIndex, step.function, step.rcChannel, step.travel);
      this.lastDetected.set(controlKey(source, sourceIndex));
      this.guidedBound.update((n) => n + 1);
    }
  }
}
