import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, inject, input, signal, viewChild } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { ConfirmDialog } from './confirm-dialog';
import { returnHomeToastFor } from './return-home-button-logic';

/**
 * "Bring home" (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's frozen contract) — a single command,
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
 *
 * **`Escape` and an outside click both cancel** (docs/plans/done/UI-STATE-PLAN.md §2.4) — added *here*, on this
 * component, not on `ConfirmDialog` itself. `ConfirmDialog`'s own doc comment is deliberate about
 * carrying **no** Escape/backdrop-dismiss of its own ("every dismissal is an explicit button click …
 * never an accidental key press/misclick either way") — a rule this component still honors for its
 * own confirm exactly as written: neither handler below can ever *confirm* the command, only ever
 * calls {@link cancel}, the exact same transition the dialog's own Cancel button already makes, so
 * the one thing `ConfirmDialog`'s rule actually guards against (an accidental confirm) still cannot
 * happen. What changes is only *which explicit gestures count as "the operator's decision"* — Escape
 * and a click on the scrim are now two more, matching the instinct §2.2's `GlobalOverlayStore` serves
 * for the shell's own overlays. This confirm is page-scoped (dies with whichever host page mounted
 * it), so per §2.4 it keeps this state as a local `confirmOpen` signal rather than moving into the
 * shell's store — only the *behavior* is mirrored, via the same "one document-level listener pair,
 * containment decides inside-vs-outside" idiom `core/ui/state/overlay.effects.ts` uses.
 * Both listeners no-op while {@link busy} is `true` (the request is already in flight — the dialog's
 * own Confirm/Cancel buttons are disabled for the same reason at that point, so an Escape/outside
 * click deserves the identical treatment, not a race with the pending request's own `finally`).
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

  /** The trigger button (`return-home-button.html`'s `#trigger`) — Escape returns focus to it, the
   *  same "give it back to whatever opened this" courtesy `GlobalOverlayStore`'s own Escape handler
   *  extends to the shell's overlays. */
  private readonly trigger = viewChild<ElementRef<HTMLElement>>('trigger');

  /**
   * `<vision-confirm-dialog>`'s own host element (`ConfirmDialog`, read via its component type rather
   * than a template `#ref` — the ref would resolve to the *component instance*, not its DOM node,
   * for a child component). Its `.dialog` card — not this whole host — is what "outside" is measured
   * against below: `ConfirmDialog`'s backdrop covers the entire viewport, so the card is the only
   * genuinely "inside" surface; every other pixel, including the now-covered trigger button, reads as
   * outside and is exactly what a scrim click is supposed to mean.
   */
  private readonly confirmDialogHost = viewChild(ConfirmDialog, { read: ElementRef<HTMLElement> });

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

  /**
   * `Escape` cancels the confirm — never confirms it, see this class's own doc comment. No-ops while
   * {@link busy} (the request is in flight; the dialog's own buttons are disabled for the same reason
   * right now). Returns focus to the trigger, `isConnected`-guarded the same defensive way
   * `GlobalOverlayStore.handleKeydown` guards its own refocus — this trigger can't actually be
   * detached mid-confirm today, but costs nothing to guard the same way regardless.
   */
  @HostListener('document:keydown.escape')
  protected onDocumentKeydown(): void {
    if (!this.confirmOpen() || this.busy()) {
      return;
    }
    this.cancel();
    const trigger = this.trigger()?.nativeElement;
    if (trigger?.isConnected) {
      trigger.focus();
    }
  }

  /**
   * A click that lands outside the confirm's own `.dialog` card — i.e. on the backdrop, the only
   * other reachable surface while the confirm is open — cancels it. See this class's own doc comment
   * for why this can only ever cancel, matching `page-bar.ts#onDocumentClick`'s identical containment
   * technique.
   *
   * **Must also treat a click on {@link trigger} itself as "inside".** The click that *opens* the
   * confirm (`requestReturnHome`, bound on the trigger) bubbles to this same `document` listener
   * within that one synchronous dispatch — before change detection has had a chance to actually
   * render `<vision-confirm-dialog>`, so `confirmDialogHost()` still resolves to nothing at that exact
   * instant. Without this guard, every real click on "Bring home" would open the confirm and this
   * handler would immediately read it as a click "outside" a dialog that simply hadn't rendered yet,
   * closing what it had just opened, in the same gesture, on every single click — caught by this
   * component's own `return-home-button.spec.ts`, not merely reasoned about.
   */
  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (!this.confirmOpen() || this.busy() || !(event.target instanceof Node)) {
      return;
    }
    const trigger = this.trigger()?.nativeElement;
    if (trigger?.contains(event.target)) {
      return;
    }
    const card = this.confirmDialogHost()?.nativeElement.querySelector('.dialog');
    if (card?.contains(event.target)) {
      return;
    }
    this.cancel();
  }
}
