import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { UiStore } from '../../core/ui/ui-store';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { SidePanel } from '../../shared/ui/side-panel';
import { ArmConfirmDialog } from './arm-confirm-dialog';
import type { FlightCapability } from '../../core/api/models';
import { commandOutcomeToast, disarmConfirmMessage, modeConfirmMessage } from './flight-command-panel-logic';
import type { FlightCommandToast } from './flight-command-panel-logic';

/** The panel's mutually-exclusive confirm dialogs (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — a typed id set so
 * the template can't ask about a dialog that doesn't exist. */
type CommandDialog = 'mode' | 'arm' | 'disarm';

/**
 * The Fly cockpit's Arm/Disarm/Mode panel (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2 — "UI — Wave C"),
 * extending Stage 1's `shared/ui/return-home-button.ts` with the two next command classes now that
 * capability-gating (`GET .../flight-capabilities`) is real. Migrated into the shared
 * `vision-side-panel` drawer shell (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-E): this used to be a single
 * `.command-cluster` frosted-pill island always visible in `fly.html`'s `.hud-header`; it now
 * renders as the `flight` tool-rail drawer's body, with `open`/`close` driven by `FlyPage`'s own
 * `PanelState` (`panels`) — same "own HTTP call + toast directly" shape as `ReturnHomeButton`
 * throughout, since there's no shared store a one-shot command like any of these three could
 * sensibly live behind.
 *
 * **Single consumer today** (only `FlyPage`) — lives under `features/fly/`, not `shared/ui/`, unlike
 * `ReturnHomeButton` (which already had two call sites, `FlyPage` + Command's `AssetPanel`, the day
 * it was written). Promote it to `shared/ui/` the day a second consumer needs it, per this codebase's
 * own established "second consumer moves it" precedent — not before.
 *
 * **Gating is the host's job, not this component's** (mirrors `ReturnHomeButton`'s own rule): the
 * whole panel is hidden unless `canCommand` is `true` (`FlyPage` computes it via
 * `flight-command-panel-logic.ts#canShowCommandPanel`); each individual control inside additionally
 * gates on `capabilities()`'s own per-capability flags (`armSupported`/`modeSelectSupported`/a
 * non-empty `selectableModes`) — a vehicle can be commandable at all yet support only a subset (the
 * plan's own capability matrix: INAV's mavlink is telemetry-only in practice despite reporting
 * `commandable=true`, so its commands are attempted and honestly `NO_ACK`, never hidden outright).
 *
 * **Busy-guards, one per control** (`modeBusy`/`armBusy`/`disarmBusy`, plain signals — mirrors
 * `ReturnHomeButton`'s own "single one-shot request, not a saga" reasoning, just three of them
 * instead of one since the three commands are independent actions a user could otherwise fire
 * concurrently) — each disables only its own control + its own confirm dialog's buttons, not the
 * whole panel, so (for example) a slow Arm request doesn't block reading the Mode picker.
 *
 * **No optimistic UI, ever** (the plan's own wording, identical to Stage 1): every confirm only ever
 * sends the command and reports what the server said (`ACCEPTED`/`NO_ACK`/an error via
 * `describeHttpError` — a `403` renders as "You do not have access to that.", a `409`'s message
 * verbatim, same existing switch Stage 1 already established, no new decoding needed for this trio).
 * The aircraft's actual armed state / active mode arrives later over ordinary telemetry
 * (`flightState.armed`/`flightState.mode`), which the OSD chip bar and `flightBanner`/
 * `<vision-failsafe-banner>` already render on their own — nothing here writes to `TelemetryStore`.
 */
@Component({
  selector: 'vision-flight-command-panel',
  imports: [ConfirmDialog, ArmConfirmDialog, SidePanel],
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
  /** Whether the `flight` drawer is open — driven by the host's `PanelState` (`fly.ts`'s `panels`),
   * not this component's own state (docs/plans/done/UI-REDESIGN-PLAN.md D-E). Distinct from {@link canCommand}:
   * that's the *host* gate (is there anything to show at all), this is purely "is the drawer open
   * right now" — both must hold for the command cluster to actually render (see the template). */
  readonly open = input<boolean>(false);
  /** Emitted when the drawer's own close control (`<vision-side-panel>`'s head button, or Esc) fires
   * — the host is the one that actually closes it (`panels.close()`). Confirm dialogs below are
   * deliberately siblings of `<vision-side-panel>` in the template, not projected inside it — see
   * this class's own doc comment above the arm section for why that placement matters. */
  readonly close = output<void>();

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  /**
   * The panel's three confirm dialogs (mode / arm / disarm) as **one mutually-exclusive overlay
   * group** (docs/plans/done/UI-ARCHITECTURE-PLAN.md) rather than three independent `signal(false)` flags:
   * opening any one closes whichever other was open, so the panel can never show two confirms at
   * once — a consistency guarantee by construction, not by discipline. Transient (no `storageKey`) —
   * a confirm must never survive a reload. `isDialogOpen` is the typed template accessor.
   */
  private readonly dialog = new UiStore();
  protected isDialogOpen(id: CommandDialog): boolean {
    return this.dialog.isOpen(id);
  }

  // --- Mode picker ---------------------------------------------------------------------------
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

  // --- Arm -------------------------------------------------------------------------------------
  /**
   * Gates `<vision-arm-confirm-dialog>` in the template — deliberately rendered as a **sibling** of
   * `<vision-side-panel>`, never projected inside it (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2 migration
   * note). `<vision-side-panel>`'s own `<aside>` catches `Esc` and emits `close` (which `fly.ts`
   * wires to `panels.close()`); if the arm/mode/disarm confirm dialogs lived *inside* that `<aside>`
   * as projected content, a stray `Esc` press while one is open would bubble up through it and close
   * the whole drawer — silently tearing down the confirm dialog along with it, defeating
   * `ConfirmDialog`/`ArmConfirmDialog`'s own explicit "no Escape dismissal" poka-yoke rule (both
   * components' own doc comments). Keeping the three confirm dialogs as template-level siblings
   * instead means an `Esc` press while one is open still bubbles to `fly.ts`'s own document-level
   * handler and may close the *drawer behind it* (harmless — the confirm dialog is a full
   * `position: fixed` scrim covering everything regardless of whether the drawer underneath is
   * "open"), but never dismisses the confirm dialog itself; only its own Cancel/Confirm buttons do.
   */
  protected readonly armBusy = signal(false);

  protected requestArm(): void {
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

  /**
   * A single combined busy flag, used to disable every trigger button (not just each one's own) —
   * these three commands all target the same physical vehicle, so firing two at once (e.g. Arm
   * while a mode-change request is still in flight) is worth ruling out entirely, not just
   * preventing each one's own double-fire. Each individual confirm dialog still only shows its own
   * `xBusy` for its own "Sending…"/label text.
   */
  protected readonly anyBusy = computed(() => this.modeBusy() || this.armBusy() || this.disarmBusy());

  private showOutcome(toast: FlightCommandToast): void {
    if (toast.kind === 'ok') {
      this.toasts.ok(toast.text);
    } else {
      this.toasts.warn(toast.text);
    }
  }
}
