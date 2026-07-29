import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { ConfirmDialog } from './confirm-dialog';
import { returnHomeToastFor } from './return-home-button-logic';

/**
 * "Bring home" (docs/DRONE-INFRA-PLAN.md I-e Stage 1's frozen contract) — a single command,
 * `MAV_CMD_DO_SET_MODE → RTL`, reused verbatim by both call sites the plan names (Fly's cockpit HUD
 * and Command's `AssetPanel`), the same "second consumer → a shared component" precedent
 * `shared/ui/weather-chip.ts`/`shared/player/detections-strip.ts` already established.
 *
 * Unlike `weather-chip.ts` (which injects a host-provided *store* and issues no HTTP of its own),
 * this component owns its own HTTP call and toast directly: there is no page-scoped store for a
 * one-shot command like this to share, and both call sites want byte-identical behavior (gate →
 * confirm → call → toast), so centralizing all of it here is what keeps "one component, two call
 * sites" actually true rather than splitting the interesting half back out to each host.
 * `VisionApi`/`ToastService` are both `providedIn: 'root'` already, so this needs no host-provided
 * `providers` entry the way a page-scoped store (`TelemetryStore` et al.) would.
 *
 * **Gating is the host's job, not this component's**: `canCommand` is computed by each host from
 * `core/telemetry/flight-state-logic.ts#canCommandReturnHome` over whatever telemetry it already has
 * in hand (Fly's own `TelemetryStore.latest()`, Command's `AssetPanel`'s `marker()`) — this
 * component only *renders* that verdict (hidden entirely when `false`), it never re-derives it, so
 * it needs no telemetry access of its own.
 *
 * **No optimistic UI, ever** (the plan's own wording): a click here only ever sends the command and
 * reports what the server said (`ACCEPTED`/`NO_ACK`/an error) — the aircraft's actual mode change
 * arrives later over ordinary telemetry (`flightState.mode` → an RTL-family value), which
 * `core/telemetry/flight-state-logic.ts#flightBanner` already turns into the existing "Return to
 * home active" banner (`features/fly/failsafe-banner.ts`) on its own, with no wiring needed here —
 * this button's job ends the moment the request settles.
 *
 * **Poka-yoke, per the frozen contract**: always a confirm dialog (`shared/ui/confirm-dialog.ts`, a
 * true modal — see that component's own doc comment for why not `.hud-confirm`), never
 * auto-triggered, no keyboard shortcut anywhere near this button. `busy` disables the button itself
 * the instant it's clicked (covering both "the dialog is open" and "the request is in flight") so a
 * double-click/double-Enter can never fire the command twice; a plain signal, not a state machine —
 * this is a single one-shot request, not a saga with retries/reducible transitions the way
 * `shared/player/player-recovery.ts`'s reconnect logic is.
 */
@Component({
  selector: 'vision-return-home-button',
  imports: [ConfirmDialog],
  templateUrl: './return-home-button.html',
  styleUrl: './return-home-button.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReturnHomeButton {
  readonly assetId = input.required<string>();
  readonly assetDisplayName = input.required<string>();
  /** `core/telemetry/flight-state-logic.ts#canCommandReturnHome` — see this class's own doc comment. */
  readonly canCommand = input<boolean>(false);
  /** Command's `AssetPanel` renders this alongside small `.btn.secondary.small` actions; Fly's HUD doesn't. */
  readonly compact = input<boolean>(false);

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  protected readonly confirmOpen = signal(false);
  protected readonly busy = signal(false);

  protected readonly confirmMessage = computed(() => `Command ${this.assetDisplayName()} to return home?`);

  protected requestReturnHome(): void {
    this.confirmOpen.set(true);
  }

  protected cancel(): void {
    this.confirmOpen.set(false);
  }

  protected async confirm(): Promise<void> {
    this.busy.set(true);
    try {
      const response = await this.api.returnHome(this.assetId());
      const toast = returnHomeToastFor(response.result);
      if (toast.kind === 'ok') {
        this.toasts.ok(toast.text);
      } else {
        this.toasts.warn(toast.text);
      }
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busy.set(false);
      this.confirmOpen.set(false);
    }
  }
}
