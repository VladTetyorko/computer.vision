import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { UiStore } from '../../core/ui/ui-store';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { Icon } from '../../shared/ui/icon';
import { ArmConfirmDialog } from './arm-confirm-dialog';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import type { FlightCapability } from '../../core/api/models';
import { armDisableReason, commandOutcomeToast, disarmConfirmMessage } from './flight-command-panel-logic';
import type { FlightCommandToast } from './flight-command-panel-logic';

/** This panel's own two confirm dialogs (arm / disarm) as one mutually-exclusive overlay group
 * (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — a typed id set so the template can't ask about a
 * dialog that doesn't exist. */
type CommandDialog = 'arm' | 'disarm';

/**
 * The Fly cockpit's Arm/Disarm pills — the bottom-right zone of the on-video control HUD
 * (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3), extending
 * docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2's original capability-gated command trio.
 *
 * <h2>Arm/Disarm only — mode picking moved out</h2>
 * This used to be a combined Mode+Arm+Disarm panel living inside the Controller drawer
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C10). FLY-CONTROL-UX-PLAN §0 reverses
 * that placement for exactly these two commands: they are "the one live action" an operator reaches
 * for *while* holding the sticks (R2's QGC/DJI/Betaflight survey), so they now render as HUD pills
 * on the video itself, mounted by `fly-hud.ts`. **Mode picking stayed in the rail** — it's a
 * deliberate, infrequent choice made looking at a dropdown, not a live action — and now lives in its
 * own `mode-picker.ts`, still inside `<vision-rc-monitor>`.
 *
 * **Body-only** — this owns no `<vision-side-panel>`/`open`/`close` and no positioning of its own;
 * `fly-hud.css` anchors it to the HUD's bottom-right zone, mirroring how `<vision-cv-control-panel>`
 * stays body-only inside its own drawer.
 *
 * Same "own HTTP call + toast directly" shape as `ReturnHomeButton`/`ModePicker` throughout, since
 * there's no shared store a one-shot command like either of these sensibly lives behind.
 *
 * **Single consumer today** (`fly-hud.ts`) — lives under `features/fly/`, not `shared/ui/`. Promote
 * it to `shared/ui/` the day a second consumer needs it, per this codebase's own established "second
 * consumer moves it" precedent — not before.
 *
 * **Gating is the host's job, not this component's** (mirrors `ReturnHomeButton`'s own rule): the
 * whole panel is hidden unless `canCommand` is `true`; Arm additionally gates on
 * `capabilities()?.armSupported` — a vehicle can be commandable at all yet not support Arm (the
 * plan's own capability matrix).
 *
 * **Busy-guards, one per control** (`armBusy`/`disarmBusy`, plain signals — mirrors
 * `ReturnHomeButton`'s own "single one-shot request, not a saga" reasoning) — each disables only its
 * own control + its own confirm dialog's buttons. **The old cross-command `anyBusy` lock (blocking
 * Arm while a Mode-change request was still in flight) is deliberately dropped** now that Mode lives
 * in a different component entirely with no shared state to coordinate through — introducing one
 * just to preserve that lock would be new machinery this wave's scope doesn't otherwise justify.
 *
 * **No optimistic UI, ever** (identical rule to `ModePicker`/`ReturnHomeButton`): every confirm only
 * ever sends the command and reports what the server said. The aircraft's actual armed state arrives
 * later over ordinary telemetry, which the OSD chip bar and header status chips already render.
 */
@Component({
  selector: 'vision-flight-command-panel',
  imports: [Icon, ConfirmDialog, ArmConfirmDialog],
  templateUrl: './flight-command-panel.html',
  styleUrl: './flight-command-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlightCommandPanel {
  readonly assetId = input.required<string>();
  readonly assetDisplayName = input.required<string>();
  /** `undefined` while the capability fetch is in flight/failed — every control below stays hidden. */
  readonly capabilities = input<FlightCapability | undefined>(undefined);
  /** `flight-command-panel-logic.ts#canShowCommandPanel` — see this class's own doc comment. */
  readonly canCommand = input<boolean>(false);
  /** Latest telemetry's `flightState.armed` — gates Disarm's crash-warning copy, nothing else. */
  readonly armed = input<boolean | undefined>(undefined);
  /** The switch, if any, whose action map fires the same command as this row's own buttons
   * (`rc-monitor.ts#armAlsoOn`, docs/plans/active/CONTROLLER-UX-PLAN.md §2.2 decision U3) —
   * rendered as a faint "also on <switch>" line. `undefined` omits the hint entirely. */
  readonly armAlsoOn = input<string | undefined>(undefined);
  /**
   * `CockpitPage`'s own `GroundingStore.groundedReason`, forwarded through `fly-hud.ts`
   * (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "S1 gate semantics", wave WB1) — `undefined` unless
   * this asset carries an open `MAINTENANCE_GROUNDED:` blocker.
   */
  readonly groundedReason = input<string | undefined>(undefined);
  /**
   * `core/rc/neutral-gate-logic.ts#neutralGateReason`, forwarded from `fly-hud.ts`
   * (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2) — `undefined` when there's no engaging/engaged
   * session, or every bound axis reads neutral. Composed with {@link groundedReason} via
   * `flight-command-panel-logic.ts#armDisableReason` (grounding wins when both hold).
   */
  readonly sticksNotNeutralReason = input<string | undefined>(undefined);
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  /** Transient (no `storageKey`) — a confirm must never survive a reload. */
  private readonly dialog = new UiStore();
  protected isDialogOpen(id: CommandDialog): boolean {
    return this.dialog.isOpen(id);
  }

  // --- Arm -------------------------------------------------------------------------------------
  /**
   * Gates `<vision-arm-confirm-dialog>` in the template. Both this panel's confirms take focus on
   * open and stop `Esc` at their own backdrop (see `shared/ui/confirm-dialog.ts`) rather than
   * dismissing — an `Esc` that reached the HUD/drawer underneath could otherwise close a live
   * manual-control session out from under an open confirm.
   */
  protected readonly armBusy = signal(false);

  /** Composed reason (`flight-command-panel-logic.ts#armDisableReason` — grounding wins over
   * sticks-not-neutral). Poka-yoke: the trigger button is disabled from this exact condition
   * (never enabled-then-error), and {@link requestArm} re-checks it too, since a disabled DOM
   * button is still reachable by a stray keyboard Enter on some browsers. */
  protected readonly composedArmReason = computed(() => armDisableReason(this.groundedReason(), this.sticksNotNeutralReason()));
  protected readonly armDisabled = computed(() => this.composedArmReason() !== undefined);

  protected requestArm(): void {
    if (this.armDisabled()) {
      return;
    }
    this.dialog.open('arm');
  }

  protected cancelArm(): void {
    this.dialog.close('arm');
  }

  protected async confirmArm(): Promise<void> {
    this.armBusy.set(true);
    try {
      const response = await this.api.arm(this.assetId());
      this.showOutcome(commandOutcomeToast(response.result, 'arm'));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.armBusy.set(false);
      this.dialog.close('arm');
    }
  }

  // --- Disarm ----------------------------------------------------------------------------------
  protected readonly disarmBusy = signal(false);
  protected readonly disarmMessage = computed(() => disarmConfirmMessage(this.assetDisplayName(), this.armed()));

  protected requestDisarm(): void {
    this.dialog.open('disarm');
  }

  protected cancelDisarm(): void {
    this.dialog.close('disarm');
  }

  protected async confirmDisarm(): Promise<void> {
    this.disarmBusy.set(true);
    try {
      const response = await this.api.disarm(this.assetId());
      this.showOutcome(commandOutcomeToast(response.result, 'disarm'));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.disarmBusy.set(false);
      this.dialog.close('disarm');
    }
  }

  private showOutcome(toast: FlightCommandToast): void {
    if (toast.kind === 'ok') {
      this.toasts.ok(toast.text);
    } else {
      this.toasts.warn(toast.text);
    }
  }
}
