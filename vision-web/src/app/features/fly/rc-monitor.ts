import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output } from '@angular/core';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import { RcInputService } from '../../core/rc/rc-input.service';
import { ManualControlClient } from '../../core/rc/manual-control-client';
import {
  axisToPercent,
  barLeftPercent,
  barWidthPercent,
  defaultAxisLabel,
  defaultButtonLabel,
  isButtonOn,
} from '../../core/rc/rc-input-logic';
import { channelBindingLabel, engageDisabledReason, latencyLabel } from './rc-monitor-logic';

/**
 * `vision-rc-monitor` — the cockpit's RC transmitter drawer. Phase 0 (docs/plans/active/RC-CONTROL-PLAN.md) added
 * the read-only monitor at the top: a plugged-in RadioMaster's live sticks (axes) and switches
 * (buttons), so an operator can confirm the platform sees the controller and check its update rate.
 * R5 (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md) adds **Take control** below it — the SITL relay engage/
 * release gesture, additive, the monitor above is unchanged.
 *
 * Provides its own `RcInputService` **and** `ManualControlClient` and drives `RcInputService`'s
 * lifecycle (reading starts when this drawer mounts, per `ngOnInit`); `ManualControlClient` needs
 * no explicit start — its own constructor wires the deadman triggers, and its own `DestroyRef`
 * teardown (this component unmounting, i.e. the RC panel closing) is one of them. The cockpit
 * mounts this component via `@if (isPanelOpen('rc'))`, so both the Gamepad rAF loop and any live
 * relay session only exist while the drawer is open.
 */
@Component({
  selector: 'vision-rc-monitor',
  imports: [SidePanel, Notice],
  providers: [RcInputService, ManualControlClient],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './rc-monitor.html',
  styleUrl: './rc-monitor.css',
})
export class RcMonitor implements OnInit {
  protected readonly rc = inject(RcInputService);
  protected readonly client = inject(ManualControlClient);

  /** The currently-flown asset — mirrors `flight-command-panel.ts`'s own `assetId`/
   * `assetDisplayName` inputs (`fly.html` renders both components inside the same
   * `@else if (facade.asset(); as a)` branch, so `a.assetId`/`a.displayName` are always defined
   * wherever either is actually mounted). */
  readonly assetId = input.required<string>();
  readonly assetDisplayName = input.required<string>();
  /** `FlyFacade.canShowCommands()` — the same capability gate `flight-command-panel` uses (docs/
   * RC-CONTROL-PHASE1-PLAN.md R5: "reuse the same gate the flight panel uses"). Not the *exact* same
   * server-side capability the relay itself checks (`ManualControlPort.supports` + reachability,
   * decided only when the `engage` WS frame is actually sent) — this is the closest existing signal
   * this UI has to avoid offering the control on a vehicle that plainly isn't being heard at all; a
   * server `denied` still handles the cases this front-line gate can't see. */
  readonly canCommand = input<boolean>(false);
  readonly close = output<void>();

  protected readonly axisToPercent = axisToPercent;
  protected readonly barLeftPercent = barLeftPercent;
  protected readonly barWidthPercent = barWidthPercent;
  protected readonly axisLabel = defaultAxisLabel;
  protected readonly buttonLabel = defaultButtonLabel;
  protected readonly isOn = isButtonOn;
  protected readonly latencyLabel = latencyLabel;
  protected readonly channelBindingLabel = channelBindingLabel;

  protected readonly disabledReason = computed(() =>
    engageDisabledReason({
      hasAsset: this.assetId().length > 0,
      canCommand: this.canCommand(),
      gamepadSupported: this.rc.supported(),
      gamepadConnected: this.rc.connected(),
      engageState: this.client.state(),
    }),
  );
  protected readonly engageDisabled = computed(() => this.disabledReason() !== undefined);

  ngOnInit(): void {
    this.rc.start();
  }

  protected engage(): void {
    if (this.engageDisabled()) {
      return;
    }
    this.client.engage(this.assetId());
  }

  protected release(): void {
    this.client.release();
  }
}
