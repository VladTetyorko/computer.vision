import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * A generic, mandatory confirm modal (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's own poka-yoke
 * requirement — "ALWAYS a confirm dialog … never auto-triggered, no keyboard shortcut") — a true
 * `position: fixed` modal (mirrors `shared/map/fleet-plan-dialog/flight-plan-dialog.ts`'s /
 * `features/command/geofence-zone-dialog.ts`'s own backdrop convention, `z-index: 150`, same as
 * those two) rather than a page-local `.hud-confirm` scrim (`features/fly/fly.html`'s own
 * Stop-stream confirm, `.inline-confirm-card`) — this one needs to render correctly from more than
 * one host layout (Fly's full-bleed cockpit *and* Command's docked side panel,
 * `shared/ui/return-home-button.ts`'s own two call sites), so it can't lean on either page's own
 * positioning scheme the way `.hud-confirm` leans on `.cockpit`'s.
 *
 * First reusable instance of this shape in `shared/ui/` — every dialog before this one was
 * single-consumer/page-specific (`flight-plan-dialog.ts`, `geofence-zone-dialog.ts`) or an inline
 * page-local card (`.inline-confirm-card`); this generalizes the "are you sure" half of that
 * pattern for whoever's own destructive/consequential action needs it next.
 *
 * Deliberately no backdrop-click-to-dismiss and no `Escape` handling — every dismissal is an
 * explicit button click (Confirm or Cancel), never an accidental key press/misclick either way.
 */
@Component({
  selector: 'vision-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.css',
})
export class ConfirmDialog {
  readonly message = input.required<string>();
  readonly confirmLabel = input<string>('Confirm');
  readonly cancelLabel = input<string>('Cancel');
  /** Disables both buttons while the caller's own request is in flight — no double-fire. */
  readonly busy = input<boolean>(false);

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
