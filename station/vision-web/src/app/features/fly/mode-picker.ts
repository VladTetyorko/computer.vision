import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { UiStore } from '../../core/ui/ui-store';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import type { FlightCapability } from '../../core/api/models';
import { commandOutcomeToast, modeConfirmMessage } from './flight-command-panel-logic';
import type { FlightCommandToast } from './flight-command-panel-logic';

/**
 * `vision-mode-picker` — the flight-mode row (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3), split out
 * of what used to be `flight-command-panel.ts`'s combined mode/arm/disarm panel. §3's own contract
 * moves Arm/Disarm onto the video HUD (they are the one live action, R2's own industry-consensus
 * framing) but keeps mode picking in the informational rail — setting a mode is a deliberate,
 * infrequent choice made while looking at the picker's own dropdown, not a control an operator
 * reaches for mid-stick the way Arm/Disarm are, so it stays where the rest of "what am I set up to
 * do" already lives (transmitter picture, mapping rows, hints).
 *
 * **Everything else about this row is unchanged from the old combined panel** — same
 * `canCommand`/`capabilities` gating, same confirm-then-send shape, same "also on &lt;switch&gt;"
 * hint, same no-optimistic-UI rule (`flight-command-panel.ts`'s own doc comment covers the reasoning
 * in full; this class only restates what's specific to the mode row itself).
 *
 * **Single consumer today** (`rc-monitor.ts`) — lives beside it under `features/fly/`, mirroring
 * `flight-command-panel.ts`'s own "promote to `shared/ui/` on a second consumer" precedent.
 */
@Component({
  selector: 'vision-mode-picker',
  imports: [ConfirmDialog],
  templateUrl: './mode-picker.html',
  styleUrl: './mode-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ModePicker {
  readonly assetId = input.required<string>();
  readonly assetDisplayName = input.required<string>();
  /** `undefined` while the capability fetch is in flight/failed — this row stays hidden. */
  readonly capabilities = input<FlightCapability | undefined>(undefined);
  /** `flight-command-panel-logic.ts#canShowCommandPanel` — the same gate the Arm/Disarm HUD pills use. */
  readonly canCommand = input<boolean>(false);
  /** The switch, if any, whose action map fires the same `SET_MODE` command
   * (`rc-monitor.ts#modeAlsoOn`, docs/plans/active/CONTROLLER-UX-PLAN.md §2.2 decision U3). */
  readonly modeAlsoOn = input<string | undefined>(undefined);

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  /** Transient (no `storageKey`) — a confirm must never survive a reload. Single member today
   * (`'mode'`) but kept as a `UiStore`, not a bare `signal(false)`, per this codebase's own "any
   * exclusive show/hide state goes through UiStore" convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md). */
  private readonly dialog = new UiStore();
  protected isDialogOpen(): boolean {
    return this.dialog.isOpen('mode');
  }

  /** `undefined` = "use the first selectable mode" — mirrors `fly.ts#primaryDeviceIdOverride`'s own
   * "no effect-based defaulting, fallback computed fresh every read" idiom. */
  private readonly selectedModeOverride = signal<string | undefined>(undefined);
  protected readonly selectedMode = computed(() => {
    const modes = this.capabilities()?.selectableModes ?? [];
    const override = this.selectedModeOverride();
    return override !== undefined && modes.includes(override) ? override : modes[0];
  });
  protected readonly modeBusy = signal(false);
  protected readonly modeMessage = computed(() => modeConfirmMessage(this.assetDisplayName(), this.selectedMode() ?? ''));

  protected onModeChange(value: string): void {
    this.selectedModeOverride.set(value);
  }

  protected requestSetMode(): void {
    if (this.selectedMode() !== undefined) {
      this.dialog.open('mode');
    }
  }

  protected cancelMode(): void {
    this.dialog.close('mode');
  }

  protected async confirmMode(): Promise<void> {
    const mode = this.selectedMode();
    if (mode === undefined) {
      this.dialog.close('mode');
      return;
    }
    this.modeBusy.set(true);
    try {
      const response = await this.api.setMode(this.assetId(), mode);
      this.showOutcome(commandOutcomeToast(response.result, 'mode'));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.modeBusy.set(false);
      this.dialog.close('mode');
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
