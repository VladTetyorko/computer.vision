import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { IconButton } from '../../shared/ui/icon-button';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { UiStore } from '../../core/ui/ui-store';
import { RcInputService } from '../../core/rc/rc-input.service';
import { axisToPercent, defaultAxisLabel, defaultButtonLabel, isButtonOn } from '../../core/rc/rc-input-logic';
import { actionLabel, controlKey, controlLabel, positionOf } from '../../core/rc/control-action-logic';
import {
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
import type { ControlAction, SwitchPosition, VehicleKind } from '../../core/api/models';

/** RC channels a control may be bound to — the domain's own `[1,18]`, as a list a picker can render. */
const RC_CHANNELS = Array.from({ length: 18 }, (_, i) => i + 1);

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
  imports: [FormsModule, SectionHeader, EmptyState, Notice, IconButton, ConfirmDialog],
  templateUrl: './controller-setup.html',
  styleUrl: './controller-setup.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ControllerSetupFacade, RcInputService],
})
export class ControllerSetupPage implements OnInit {
  protected readonly facade = inject(ControllerSetupFacade);
  protected readonly rc = inject(RcInputService);

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

  /** The delete confirm — one overlay group, per this app's own `UiStore` rule. */
  private readonly dialog = new UiStore();
  protected isConfirming(id: string): boolean {
    return this.dialog.isOpen(id);
  }

  /** The "flick the control you mean" gesture: the readings when it started, or `undefined` when off. */
  private readonly learnBaseline = signal<{ axes: readonly number[]; buttons: readonly number[] } | undefined>(
    undefined,
  );
  protected readonly learning = computed(() => this.learnBaseline() !== undefined);

  // --- New-layout form -------------------------------------------------------------------------
  protected readonly newKind = signal<VehicleKind>('ROVER');
  protected readonly newName = signal('');

  constructor() {
    effect(() => {
      const axes = this.rc.axes();
      const buttons = this.rc.buttons();
      untracked(() => this.captureLearned(axes, buttons));
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
