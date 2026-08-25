import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { IconButton } from '../../shared/ui/icon-button';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { UiStore } from '../../core/ui/ui-store';
import { RcInputService } from '../../core/rc/rc-input.service';
import { defaultAxisLabel, defaultButtonLabel } from '../../core/rc/rc-input-logic';
import { actionLabel, controlKey, controlLabel, positionOf } from '../../core/rc/control-action-logic';
import {
  actionAt,
  asStickMode,
  bindingSummary,
  channelOptions,
  draftKey,
  draftLabel,
  groupByKind,
  kindsFor,
  parameterKindOf,
  positionsOf,
  type ControlDraft,
  type ProfileGroup,
} from '../../core/rc/controller-setup-logic';
import {
  DEFAULT_STICK_MODE,
  STICK_MODES,
  type StickMode,
} from '../../core/rc/controller-diagram-logic';
import { channelOutputs, type ChannelOutput } from '../../core/rc/channel-output-logic';
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
import {
  AUTODETECT_OFF,
  beginAutodetect,
  stepAutodetect,
  type AutodetectState,
} from '../../core/rc/control-autodetect';
import { ControllerSetupFacade } from './controller-setup-facade';
import { ControllerDiagram, type DiagramControl, type DiagramPick } from './controller-diagram';
import type { ControlAction, ControlFunction, SwitchPosition, VehicleKind } from '../../core/api/models';

/** DOM id of one control's row, so a highlight and a scroll have something to aim at. */
function cardId(key: string): string {
  return 'control-' + key.replace(':', '-');
}

/** DOM id of the editor panel — where a diagram pick sends a narrow screen. */
const EDITOR_ID = 'control-editor';

/**
 * `/manage/controller` (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C11) — where an operator says
 * what each stick, switch and button on their transmitter does.
 *
 * <h2>The page is the transmitter, not a form</h2>
 * Every row reads live: flick a switch and its own row lights up with the position it is in, using
 * the same quantizer the command that fires from it will use (`control-action-logic.ts#positionOf`).
 * That is the whole reason this is a page and not a settings dialog — "which one is Sw 5" is a
 * question no dropdown can answer, and **Detect** answers it by watching for the control that
 * actually moved.
 *
 * <h2>Nothing here commands anything</h2>
 * Editing writes to a local draft; `PUT` happens on Save, and even then a saved layout does nothing
 * until it is activated. A control this page is in the middle of describing is a control the
 * operator has not finished thinking about, and a live-saving editor would hand it to a session
 * anyway.
 *
 * Every picker is filled from `GET /api/control-profiles/catalog` (decision C8) — this component
 * hardcodes no action, no function and no switch level, so it cannot offer something the server
 * would then refuse.
 */
@Component({
  selector: 'vision-controller-setup-page',
  imports: [FormsModule, SectionHeader, EmptyState, Notice, IconButton, ConfirmDialog, ControllerDiagram],
  templateUrl: './controller-setup.html',
  styleUrl: './controller-setup.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ControllerSetupFacade, RcInputService],
})
export class ControllerSetupPage implements OnInit {
  protected readonly facade = inject(ControllerSetupFacade);
  protected readonly rc = inject(RcInputService);
  private readonly injector = inject(Injector);

  protected readonly axisLabel = defaultAxisLabel;
  protected readonly buttonLabel = defaultButtonLabel;
  protected readonly actionLabel = actionLabel;
  protected readonly controlLabel = controlLabel;
  protected readonly draftKey = draftKey;
  protected readonly draftLabel = draftLabel;
  protected readonly actionAt = actionAt;
  protected readonly cardId = cardId;
  protected readonly editorId = EDITOR_ID;
  /** The channels worth offering — served, not assumed; see `controller-setup-logic.ts#relayedChannels`. */
  protected readonly channels = computed(() => channelOptions(this.facade.catalog()));

  /** The delete confirm — one overlay group, per this app's own `UiStore` rule. */
  private readonly dialog = new UiStore();
  protected isConfirming(id: string): boolean {
    return this.dialog.isOpen(id);
  }

  /** The Autodetect run: one flick per control, until the operator stops it. */
  private readonly autodetect = signal<AutodetectState>(AUTODETECT_OFF);
  protected readonly detecting = computed(() => this.autodetect().phase !== 'off');
  /** How many rows this run has claimed, so the button can say it is getting somewhere. */
  protected readonly detectedCount = signal(0);
  /** The control most recently named, called out on the diagram until the next one moves. */
  protected readonly lastDetected = signal<string | undefined>(undefined);

  /** The operator's layouts, one group per vehicle kind — see `groupByKind` for why not a flat list. */
  protected readonly groups = computed(() => groupByKind(this.facade.profiles(), this.facade.catalog()));

  /**
   * What is in force for one vehicle kind, in words.
   *
   * The group is already named after the kind, and so is its built-in — printing the active
   * layout's name beside the heading said "Multirotor — Multirotor". What the operator wants to
   * know from a heading is whether their own layout or the fallback is the one a session engages.
   */
  /** The catalogue's word for a vehicle kind — never the wire enum, which reads as `ROVER`. */
  protected kindLabel(kind: VehicleKind): string {
    return this.facade.catalog()?.vehicleKinds.find((k) => k.name === kind)?.label ?? kind;
  }

  protected activeSummary(group: ProfileGroup): string {
    const active = group.active;
    if (!active) {
      return 'nothing active';
    }
    return active.source === 'BUILT_IN' ? 'using the built-in' : `using ${active.name}`;
  }

  /**
   * The one control being edited.
   *
   * <h2>One editor, not a wall of them</h2>
   * Every mapped control used to render its own full editor — four selects and a checkbox each,
   * stacked down a page two screens long, of which the operator was reading exactly one. The
   * diagram already answers "what is bound where"; the editor answers "what does *this* one do",
   * and that question is only ever asked about one control at a time. A layout being *viewed*
   * (a built-in, which cannot be edited) still lists everything, because there is nothing to
   * concentrate on and hiding it would just be hiding it.
   */
  protected readonly selectedControl = signal<string | undefined>(undefined);

  protected readonly editing = computed<ControlDraft | undefined>(() => {
    const key = this.selectedControl();
    return key === undefined ? undefined : this.facade.draft()?.controls.find((c) => draftKey(c) === key);
  });

  protected select(key: string): void {
    this.selectedControl.set(key);
  }

  /**
   * What the station would put on the wire right now, per channel.
   *
   * The rest of this page shows the *input*; this is the only place that shows the *output*, which
   * is what makes `reversed` and `travel` checkable on the bench rather than on the first flight.
   */
  protected readonly outputs = computed<readonly ChannelOutput[]>(() =>
    channelOutputs(this.facade.draft(), this.rc.axes(), this.rc.buttons(), this.facade.catalog()),
  );

  /** The draft as the diagram needs it: where each control is, and what it does today. */
  protected readonly diagramControls = computed<readonly DiagramControl[]>(() => {
    const catalog = this.facade.catalog();
    return (this.facade.draft()?.controls ?? []).map((control) => ({
      source: control.source,
      sourceIndex: control.sourceIndex,
      caption: bindingSummary(control, catalog),
      function: control.role === 'CHANNEL' ? control.function : undefined,
    }));
  });

  // --- How the transmitter is drawn ------------------------------------------------------------

  protected readonly stickModes = STICK_MODES;

  /**
   * Which stick holds which function, and which end of a vertical axis is up.
   *
   * Neither changes a microsecond on the wire — what the vehicle does is decided entirely by axis →
   * function → channel. Both are nonetheless **saved with the layout**, not kept in this browser:
   * they describe the radio in the operator's hands, and a browser-local answer meant setting a
   * layout up on a laptop and flying it from the ground-station box asked the same question twice.
   * Profiles are per-operator (decision C6), so there is no one else's picture to disturb.
   *
   * A built-in reports the platform default and cannot be edited; the way to change its drawing is
   * the same as the way to change anything else about it — make a copy.
   */
  protected readonly stickMode = computed<StickMode>(() => this.facade.draft()?.stickMode ?? DEFAULT_STICK_MODE);
  protected readonly positiveIsUp = computed(() => this.facade.draft()?.forwardIsUp ?? true);

  protected setStickMode(mode: string): void {
    this.facade.setStickMode(asStickMode(Number(mode)));
  }

  protected setPositiveIsUp(up: boolean): void {
    this.facade.setForwardIsUp(up);
  }

  // --- New-layout form -------------------------------------------------------------------------
  protected readonly newKind = signal<VehicleKind>('ROVER');
  protected readonly newName = signal('');

  constructor() {
    effect(() => {
      const axes = this.rc.axes();
      const buttons = this.rc.buttons();
      untracked(() => {
        this.pumpGuided(axes, buttons);
        this.pumpAutodetect(axes, buttons);
      });
    });
  }

  ngOnInit(): void {
    this.rc.start();
    void this.facade.load();
  }

  // --- Live readouts ---------------------------------------------------------------------------

  /** One control's current position, quantized exactly as the dispatcher will quantize it. */
  protected positionNow(control: ControlDraft): SwitchPosition {
    const values = control.source === 'AXIS' ? this.rc.axes() : this.rc.buttons();
    return positionOf(control.source, control.kind, values[control.sourceIndex] ?? 0);
  }


  // --- Editing ---------------------------------------------------------------------------------

  /** The one line under a control in the mapped list — the same words the diagram writes. */
  protected summary(control: ControlDraft): string {
    return bindingSummary(control, this.facade.catalog());
  }

  /** Opens the control driving a channel, so the output strip is a way in and not only a readout. */
  protected selectChannel(output: ChannelOutput): void {
    if (output.controlKey !== undefined) {
      this.select(output.controlKey);
    }
  }

  /** Unmaps a control, and stops editing it if it was the one on screen. */
  protected removeControl(key: string): void {
    this.facade.removeControl(key);
    if (this.selectedControl() === key) {
      this.selectedControl.set(undefined);
    }
  }

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

  /**
   * Whatever the operator pointed at on the diagram becomes the control being edited.
   *
   * The editor sits beside the diagram, so on a wide screen pointing at a stick fills the panel
   * next to it and nothing moves. Narrow enough and the panel stacks underneath, where it would be
   * below the fold and look like nothing happened — hence the scroll, which is a no-op once the two
   * are already side by side.
   */
  protected onDiagramPick(pick: DiagramPick): void {
    const key = controlKey(pick.source, pick.sourceIndex);
    this.lastDetected.set(key);
    this.facade.addControl(pick.source, pick.sourceIndex);
    this.select(key);
    this.revealEditor();
  }

  /** Brings the editor panel into view once the draft change has actually rendered. */
  private revealEditor(): void {
    afterNextRender(
      () => document.getElementById(EDITOR_ID)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }),
      { injector: this.injector },
    );
  }

  // --- Guide me --------------------------------------------------------------------------------

  /**
   * The guided run: the page names a function it needs, the operator moves the control they use for
   * it, and the binding is written with the channel and travel the built-in already uses.
   *
   * Autodetect answers *what a control is* and leaves every function, channel and travel to be
   * filled in by hand, once per control. This asks the operator about their own transmitter
   * instead, which is the only part of the layout they actually know.
   */
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

  /**
   * The questions to ask, taken from the built-in for the layout's vehicle kind — never from a list
   * in this component, which would be a second opinion about what each vehicle flies with.
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
      this.select(controlKey(source, sourceIndex));
      this.guidedBound.update((n) => n + 1);
    }
  }

  // --- Autodetect ------------------------------------------------------------------------------

  /**
   * Starts or stops a sweep. Mutually exclusive with the single-shot Detect above: both watch the
   * same stream for the same movement, and two things claiming one flick is how an operator ends up
   * with a row they did not ask for.
   */
  protected toggleAutodetect(): void {
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

  // --- Profile actions -------------------------------------------------------------------------

  protected async create(): Promise<void> {
    const name = this.newName().trim();
    if (name.length === 0) {
      return;
    }
    await this.facade.createFrom(this.newKind(), name);
    this.newName.set('');
  }

  protected async copySelected(): Promise<void> {
    const profile = this.facade.selected();
    if (profile) {
      await this.facade.createFrom(profile.kind, `${profile.name} copy`);
    }
  }

  protected requestDelete(id: string): void {
    this.dialog.open(id);
  }

  protected cancelDelete(): void {
    this.dialog.close();
  }

  protected async confirmDelete(id: string): Promise<void> {
    this.dialog.close();
    await this.facade.remove(id);
  }
}
