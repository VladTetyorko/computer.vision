import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Notice } from '../../shared/ui/notice';
import { SwitchGauge } from '../../shared/ui/switch-gauge/switch-gauge';
import { TransmitterView } from '../../shared/ui/transmitter-view/transmitter-view';
import { actionLabel, controlKey, controlLabel, positionOf, rulesFrom } from '../../core/rc/control-action-logic';
import { REST_VALUE, displayPercentFor } from '../../core/rc/control-surface-logic';
import { axisToPercent } from '../../core/rc/rc-input-logic';
import {
  actionStepDefaults,
  actionStepKindOf,
  actionsForStep,
  applyActionStep,
  applyChannelStep,
  channelStepDefaults,
  detectedControl,
  observeInputs,
  questionsFor,
  reviewChecks,
  type ChannelStepAnswers,
  type DetectedControl,
  type DetectSample,
  type DirectionTile,
  type RestsTile,
  type WizardStep as WizardStepModel,
} from '../../core/rc/controller-wizard-logic';
import {
  MAX_RC_CHANNEL,
  draftKey,
  microsFor,
  parameterKindOf,
  positionsOf,
  toUpdateRequest,
  type ControlDraft,
  type ProfileDraft,
} from '../../core/rc/controller-setup-logic';
import type {
  ControlAction,
  ControlCatalog,
  ControlInputKind,
  ControlSource,
  ControlTravel,
  PositionAction,
  SwitchPosition,
} from '../../core/api/models';

/** RC channels a channel step's Advanced disclosure may reassign to — the domain's own `[1,16]`. */
const RC_CHANNELS = Array.from({ length: MAX_RC_CHANNEL }, (_, i) => i + 1);

/**
 * A manual-entry fallback range for the "no transmitter plugged in" picker (decision-adjacent to
 * CONTROLLER-UX-PLAN.md §2.3 item 7) — not a protocol limit like {@link RC_CHANNELS}, just enough of
 * a range to name any plausible input by number when there is no connected device to enumerate its
 * own: 8 analog channels covers every transmitter this app's own catalogue has been built against
 * (EdgeTX's USB-joystick mode reports at most 8), 16 switches comfortably covers a full switch bank.
 */
const MANUAL_AXES = Array.from({ length: 8 }, (_, i) => i);
const MANUAL_BUTTONS = Array.from({ length: 16 }, (_, i) => i);

interface PositionChoice {
  readonly action: ControlAction | undefined;
  readonly parameter: string | null;
}

interface PositionRow {
  readonly position: SwitchPosition;
  readonly action: ControlAction | undefined;
  readonly parameter: string | null;
}

/**
 * `vision-wizard-step` — one step of the `/manage/controller` setup wizard
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.3, wave X4): the two-card body the step rail opens
 * into. Left card is instruction + live detection; right card is the choices for a `CHANNEL` step
 * (direction/rests tiles), the per-position action editor for `ARM`/`MODE`/`EXTRAS`, or the review
 * checklist + `vision-transmitter-view` for `REVIEW`.
 *
 * <h2>Detection, not a form</h2>
 * While a step is open this component accumulates every axis/button reading it is handed
 * (`controller-wizard-logic.ts#observeInputs`) and names the control that travelled the furthest
 * (`detectedControl`) as what the operator means — decision U9, "Detect is on while a step is open;
 * no separate Detect button". Samples reset whenever `step()`'s identity changes or the operator
 * clicks "Move another" — never mid-step, so switching steps can't cross-contaminate what a control
 * is being bound to.
 *
 * <h2>The draft is computed here, applied by the page</h2>
 * `applyDraft` emits a whole new {@link ProfileDraft} — built with the same pure
 * `applyChannelStep`/`applyActionStep` functions `core/rc/controller-wizard-logic.ts` exports — for
 * the page to hand to `ControllerSetupFacade#replaceDraft`. This component never mutates the draft
 * itself and never calls the facade: it stays a plain input/output component, testable with plain
 * data the same way `TransmitterView` is.
 *
 * <h2>No gamepad, no problem</h2>
 * `connected() === false` (item 7) swaps the detection block for a manual picker — a small
 * source+index select the operator fills in from what they already know about their hardware — so
 * the wizard stays usable without a plugged-in transmitter, same honesty rule as the old page's own
 * "you can still edit this layout without one".
 */
@Component({
  selector: 'vision-wizard-step',
  imports: [FormsModule, Notice, SwitchGauge, TransmitterView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wizard-step.html',
  styleUrl: './wizard-step.css',
})
export class WizardStep {
  readonly step = input.required<WizardStepModel>();
  readonly stepNumber = input.required<number>();
  readonly stepCount = input.required<number>();
  readonly draft = input.required<ProfileDraft>();
  readonly catalog = input<ControlCatalog | undefined>(undefined);
  readonly axes = input<readonly number[]>([]);
  readonly buttons = input<readonly number[]>([]);
  /** Whether a transmitter/gamepad is reporting input at all — item 7's manual-picker gate. */
  readonly connected = input<boolean>(false);
  /** `false` for a built-in layout (decision U12) — every choice control is replaced by a single
   * "Make a copy to edit" action; step navigation still works. */
  readonly editable = input<boolean>(true);
  /** `REVIEW` only — `ControllerSetupFacade#issues`, `#canSave`, `#saving`; owned by the page's
   * facade, so this component stays a plain input/output component rather than injecting it. */
  readonly issues = input<readonly string[]>([]);
  readonly canSave = input<boolean>(false);
  readonly saving = input<boolean>(false);

  /** A new draft to hand `ControllerSetupFacade#replaceDraft` — see this class's own doc comment. */
  readonly applyDraft = output<ProfileDraft>();
  /** Move to the next step (Next commits first if `editable()`; Skip does not). */
  readonly advance = output<void>();
  readonly back = output<void>();
  /** U12 — "Make a copy to edit", on a built-in step. */
  readonly copyRequested = output<void>();
  readonly save = output<void>();
  readonly saveAndActivate = output<void>();

  protected readonly draftKey = draftKey;
  protected readonly controlLabel = controlLabel;
  protected readonly actionLabel = actionLabel;
  protected readonly rcChannels = RC_CHANNELS;
  protected readonly manualAxes = MANUAL_AXES;
  protected readonly manualButtons = MANUAL_BUTTONS;

  private readonly samples = signal<ReadonlyMap<string, DetectSample>>(new Map());
  private readonly manualPick = signal<{ readonly source: ControlSource; readonly sourceIndex: number } | undefined>(
    undefined,
  );
  protected readonly manualIsThreePos = signal(false);
  protected readonly manualSource = signal<ControlSource>('AXIS');
  protected readonly manualIndex = signal(0);

  private readonly reversedChoice = signal<boolean | undefined>(undefined);
  private readonly travelChoice = signal<ControlTravel | undefined>(undefined);
  private readonly channelChoice = signal<number | undefined>(undefined);
  private readonly positionChoices = signal<ReadonlyMap<SwitchPosition, PositionChoice>>(new Map());

  /** No catalogue/capability today carries known ArduPilot mode names (see this class's own doc
   * comment) — always empty, so the Mode step's `<datalist>` is present but inert until one does;
   * free text keeps working exactly as it does today either way. */
  protected readonly modeNameSuggestions = computed<readonly string[]>(() => []);

  constructor() {
    // A step's detection state belongs to that step alone — opening a different one starts clean.
    effect(() => {
      this.step().id;
      untracked(() => this.resetDetection());
    });
    // Accumulated unconditionally (even read-only/REVIEW): it is only ever observation, and REVIEW's
    // own checklist needs the same running samples every other step keeps.
    effect(() => {
      const axes = this.axes();
      const buttons = this.buttons();
      untracked(() => this.samples.update((s) => observeInputs(s, axes, buttons)));
    });
  }

  private resetDetection(): void {
    this.samples.set(new Map());
    this.manualPick.set(undefined);
    this.manualIsThreePos.set(false);
    this.reversedChoice.set(undefined);
    this.travelChoice.set(undefined);
    this.channelChoice.set(undefined);
    this.positionChoices.set(new Map());
  }

  protected readonly detectPurpose = computed<'CHANNEL' | 'ACTION'>(() =>
    this.step().kind === 'CHANNEL' ? 'CHANNEL' : 'ACTION',
  );

  private readonly autoDetected = computed(() => detectedControl(this.samples(), this.detectPurpose()));

  protected readonly detected = computed<DetectedControl | undefined>(() => {
    const manual = this.manualPick();
    if (!manual) {
      return this.autoDetected();
    }
    const inputKind: ControlInputKind =
      manual.source === 'BUTTON'
        ? 'BUTTON'
        : this.detectPurpose() === 'CHANNEL'
          ? 'AXIS'
          : this.manualIsThreePos()
            ? 'SWITCH_3'
            : 'SWITCH_2';
    return { source: manual.source, sourceIndex: manual.sourceIndex, inputKind, travelled: 1 };
  });

  // --- CHANNEL --------------------------------------------------------------------------------

  protected readonly questions = computed(() => (this.step().function ? questionsFor(this.step().function!) : undefined));

  private readonly baseAnswers = computed(() => channelStepDefaults(this.step(), this.detected(), this.samples()));

  protected readonly effectiveAnswers = computed<ChannelStepAnswers>(() => {
    const base = this.baseAnswers();
    return {
      reversed: this.reversedChoice() ?? base.reversed,
      travel: this.travelChoice() ?? base.travel,
      rcChannel: this.channelChoice() ?? base.rcChannel,
    };
  });

  protected readonly derivedMicros = computed(() => microsFor(this.effectiveAnswers().travel));

  protected isDirectionChosen(tile: DirectionTile): boolean {
    return tile.reversed === this.effectiveAnswers().reversed;
  }

  protected isRestChosen(tile: RestsTile): boolean {
    return tile.travel === this.effectiveAnswers().travel;
  }

  protected chooseDirection(tile: DirectionTile): void {
    this.reversedChoice.set(tile.reversed);
  }

  protected chooseRest(tile: RestsTile): void {
    this.travelChoice.set(tile.travel);
  }

  protected chooseChannel(value: string): void {
    this.channelChoice.set(Number(value));
  }

  protected liveValue(d: DetectedControl): number {
    const arr = d.source === 'AXIS' ? this.axes() : this.buttons();
    const v = arr[d.sourceIndex];
    return typeof v === 'number' && Number.isFinite(v) ? v : REST_VALUE;
  }

  protected fillPercent(d: DetectedControl): number {
    return displayPercentFor(this.effectiveAnswers().travel, this.liveValue(d));
  }

  protected readonly restPercent = computed(() => displayPercentFor(this.effectiveAnswers().travel, REST_VALUE));

  protected travelCaption(d: DetectedControl): string {
    const sample = this.samples().get(controlKey(d.source, d.sourceIndex));
    if (!sample) {
      return `${controlLabel(d.source, d.sourceIndex)} · waiting…`;
    }
    const lo = axisToPercent(sample.min);
    const hi = axisToPercent(sample.max);
    return `${controlLabel(d.source, d.sourceIndex)} · travelled ${Math.min(lo, hi)} → ${Math.max(lo, hi)}`;
  }

  // --- ARM / MODE / EXTRAS ---------------------------------------------------------------------

  protected readonly stepActions = computed(() => actionsForStep(this.step(), this.catalog()));

  protected readonly positionsForDetected = computed<readonly SwitchPosition[]>(() => {
    const d = this.detected();
    return d ? positionsOf(this.catalog(), d.inputKind) : [];
  });

  private readonly defaultPositionActions = computed<ReadonlyMap<SwitchPosition, PositionAction>>(() => {
    const d = this.detected();
    return d ? new Map(actionStepDefaults(this.step(), d.inputKind, this.catalog()).map((pa) => [pa.position, pa])) : new Map();
  });

  protected readonly positionRows = computed<readonly PositionRow[]>(() =>
    this.positionsForDetected().map((position): PositionRow => {
      const chosen = this.positionChoices().get(position);
      if (chosen) {
        return { position, action: chosen.action, parameter: chosen.parameter };
      }
      const fallback = this.defaultPositionActions().get(position);
      return { position, action: fallback?.action, parameter: fallback?.parameter ?? null };
    }),
  );

  protected readonly dangerousActions = computed(() => rulesFrom(this.catalog()).dangerous);

  protected parameterKind(action: ControlAction | undefined) {
    return parameterKindOf(this.catalog(), action);
  }

  protected auxOptions(current: string | null) {
    const menu = this.catalog()?.auxFunctions ?? [];
    if (!current || menu.some((f) => String(f.number) === current)) {
      return menu;
    }
    return [{ number: Number(current), label: `Function ${current}` }, ...menu];
  }

  protected onPositionAction(position: SwitchPosition, value: string): void {
    const action = value === '' ? undefined : (value as ControlAction);
    this.positionChoices.update((m) => {
      const next = new Map(m);
      next.set(position, { action, parameter: action ? (m.get(position)?.parameter ?? null) : null });
      return next;
    });
  }

  protected onPositionParameter(position: SwitchPosition, parameter: string): void {
    this.positionChoices.update((m) => {
      const current = m.get(position) ?? this.defaultPositionActions().get(position);
      if (!current?.action) {
        return m;
      }
      const next = new Map(m);
      next.set(position, { action: current.action, parameter });
      return next;
    });
  }

  protected livePosition(d: DetectedControl): SwitchPosition {
    return positionOf(d.source, d.inputKind, this.liveValue(d));
  }

  protected kindLabel(kind: ControlInputKind): string {
    switch (kind) {
      case 'BUTTON':
        return 'button';
      case 'SWITCH_3':
        return '3-position switch';
      case 'SWITCH_2':
        return '2-position switch';
      default:
        return 'axis';
    }
  }

  /** EXTRAS only — the rows earlier "Add another" cycles (or a previous session) already bound,
   * shown so the operator can see what is already there and remove one, rather than only ever
   * adding. */
  protected readonly existingExtras = computed(() =>
    this.draft().controls.filter((c) => actionStepKindOf(c) === 'EXTRAS'),
  );

  protected extraSummary(control: ControlDraft): string {
    return control.positions
      .map((p) => `${p.position.toLowerCase()} → ${actionLabel(p.action, p.parameter)}`)
      .join(', ');
  }

  protected removeExisting(key: string): void {
    const next: ProfileDraft = { ...this.draft(), controls: this.draft().controls.filter((c) => draftKey(c) !== key) };
    this.applyDraft.emit(next);
  }

  // --- Manual picker (item 7) ------------------------------------------------------------------

  protected useManualPick(): void {
    this.manualPick.set({ source: this.manualSource(), sourceIndex: this.manualIndex() });
  }

  // --- REVIEW -----------------------------------------------------------------------------------

  private readonly reviewRequest = computed(() => toUpdateRequest(this.draft()));
  protected readonly reviewChannelMap = computed(() => this.reviewRequest().channelMap);
  protected readonly reviewActionMap = computed(() => this.reviewRequest().actionMap);
  protected readonly checks = computed(() => reviewChecks(this.draft(), this.draft().kind, this.samples()));

  // --- Navigation --------------------------------------------------------------------------------

  private commit(): ProfileDraft {
    const step = this.step();
    if (step.kind === 'CHANNEL') {
      return applyChannelStep(this.draft(), step, this.detected(), this.effectiveAnswers());
    }
    const d = this.detected();
    if (!d) {
      return this.draft();
    }
    const positions: PositionAction[] = this.positionRows()
      .filter((r): r is PositionRow & { action: ControlAction } => r.action !== undefined)
      .map((r) => ({ position: r.position, action: r.action, parameter: r.parameter }));
    if (positions.length === 0) {
      return this.draft();
    }
    return applyActionStep(this.draft(), { source: d.source, sourceIndex: d.sourceIndex }, d.inputKind, positions);
  }

  protected onNext(): void {
    if (this.editable()) {
      const next = this.commit();
      if (next !== this.draft()) {
        this.applyDraft.emit(next);
      }
    }
    this.advance.emit();
  }

  protected onSkip(): void {
    this.advance.emit();
  }

  protected onAddAnother(): void {
    if (!this.editable()) {
      return;
    }
    const next = this.commit();
    if (next !== this.draft()) {
      this.applyDraft.emit(next);
    }
    this.resetDetection();
  }

  protected moveAnother(): void {
    this.resetDetection();
  }
}
