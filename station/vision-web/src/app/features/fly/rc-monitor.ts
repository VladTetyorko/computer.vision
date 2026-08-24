import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, input, output } from '@angular/core';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import { RcInputService } from '../../core/rc/rc-input.service';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import { RcSource, type RcSourceKind } from '../../core/rc/rc-source.service';
import { ManualControlClient } from '../../core/rc/manual-control-client';
import { ControlActionDispatcher } from '../../core/rc/control-action-dispatcher';
import { ControlProfileStore } from '../../core/rc/control-profile-store';
import { actionLabel, activeProfileFor, controlLabel } from '../../core/rc/control-action-logic';
import { VirtualControlSurface } from './virtual-control-surface';
import { FlightCommandPanel } from './flight-command-panel';
import {
  axisToPercent,
  barLeftPercent,
  barWidthPercent,
  defaultAxisLabel,
  defaultButtonLabel,
  isButtonOn,
} from '../../core/rc/rc-input-logic';
import { channelBindingLabel, engageDisabledReason, latencyLabel } from './rc-monitor-logic';
import type { FlightCapability } from '../../core/api/models';

/**
 * `vision-rc-monitor` — the cockpit's RC transmitter drawer. Phase 0 (docs/plans/active/RC-CONTROL-PLAN.md) added
 * the read-only monitor at the top: a plugged-in RadioMaster's live sticks (axes) and switches
 * (buttons), so an operator can confirm the platform sees the controller and check its update rate.
 * R5 (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md) adds **Take control** below it — the SITL relay engage/
 * release gesture, additive, the monitor above is unchanged.
 *
 * Provides the whole RC stack — `RcInputService`, `VirtualRcInputService`, `RcSource` and
 * `ManualControlClient` — and drives `RcInputService`'s lifecycle (reading starts when this drawer
 * mounts, per `ngOnInit`); `ManualControlClient` needs no explicit start: its own constructor wires
 * the deadman triggers, and its own `DestroyRef` teardown (this component unmounting, i.e. the RC
 * panel closing) is one of them. The cockpit mounts this component via `@if (isPanelOpen('rc'))`,
 * so both the Gamepad rAF loop and any live relay session only exist while the drawer is open.
 *
 * <h2>A transmitter is no longer required</h2>
 * Control can come from a plugged-in gamepad or from the on-screen surface
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10) — `RcSource` picks, and prefers a
 * connected gamepad. The surface is shaped by the engaged `channelMap`, so a rover gets one
 * steer/drive pad and an aircraft two, without this component knowing the difference.
 *
 * <h2>One drawer, not two</h2>
 * Mode and arm/disarm live at the top of this drawer as of
 * docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C10 — `<vision-flight-command-panel>` is
 * body-only now and this is its shell. They were a separate `flight` tool-rail drawer, which meant
 * closing the controller to arm and reopening it to fly; they are the same job. This component
 * only passes `capabilities`/`armed` through — it owns none of that panel's commands or confirms.
 */
@Component({
  selector: 'vision-rc-monitor',
  imports: [SidePanel, Notice, VirtualControlSurface, FlightCommandPanel],
  providers: [RcInputService, VirtualRcInputService, RcSource, ManualControlClient, ControlActionDispatcher],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './rc-monitor.html',
  styleUrl: './rc-monitor.css',
})
export class RcMonitor implements OnInit {
  protected readonly rc = inject(RcInputService);
  protected readonly source = inject(RcSource);
  protected readonly client = inject(ManualControlClient);
  protected readonly dispatcher = inject(ControlActionDispatcher);
  private readonly virtual = inject(VirtualRcInputService);
  private readonly profiles = inject(ControlProfileStore);

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
  /** Passed straight through to `<vision-flight-command-panel>` — `undefined` while the capability
   * fetch is in flight or failed, which hides the mode/arm section and nothing else. */
  readonly capabilities = input<FlightCapability | undefined>(undefined);
  /** Latest telemetry's `flightState.armed` — the flight section's disarm copy reads it. */
  readonly armed = input<boolean | undefined>(undefined);
  readonly close = output<void>();

  protected readonly axisToPercent = axisToPercent;
  protected readonly barLeftPercent = barLeftPercent;
  protected readonly barWidthPercent = barWidthPercent;
  protected readonly axisLabel = defaultAxisLabel;
  protected readonly buttonLabel = defaultButtonLabel;
  protected readonly isOn = isButtonOn;
  protected readonly latencyLabel = latencyLabel;
  protected readonly channelBindingLabel = channelBindingLabel;
  protected readonly actionLabel = actionLabel;
  protected readonly controlLabel = controlLabel;

  /**
   * The layout this operator's switches currently fire through — resolved in the browser from the
   * vehicle kind the capability read already carries, which is what lets a bound switch work
   * *before* taking stick control (decision C3). The backend resolves the same thing again at
   * engage time; these agree because both call the same rule.
   */
  protected readonly activeProfile = computed(() =>
    activeProfileFor(this.profiles.profiles(), this.capabilities()?.vehicleKind),
  );
  /** Only the action-bound controls — the channel-bound ones are the sticks, listed separately. */
  protected readonly actionBindings = computed(() => this.activeProfile()?.actionMap ?? []);

  protected readonly disabledReason = computed(() =>
    engageDisabledReason({
      hasAsset: this.assetId().length > 0,
      canCommand: this.canCommand(),
      sourceKind: this.source.kind(),
      gamepadConnected: this.rc.connected(),
      engageState: this.client.state(),
    }),
  );
  protected readonly engageDisabled = computed(() => this.disabledReason() !== undefined);
  /** The input choice is frozen for the life of a session — swapping sticks mid-flight is not a
   * gesture this platform offers, and the surface below is shaped by the engaged map anyway. */
  protected readonly sourceLocked = computed(() => this.client.state() === 'engaging' || this.client.state() === 'engaged');

  constructor() {
    // Bound switches fire over the ordinary command endpoints, not the stick socket — so they are
    // wired to the asset itself, not to a session, and stay live whether or not one is engaged.
    effect(() => {
      this.dispatcher.bind(this.assetId(), this.activeProfile(), this.profiles.rules(), this.canCommand());
      this.dispatcher.setArmed(this.armed());
    });

    // The on-screen surface is shaped by whatever the server said this vehicle is; it exists only
    // for the life of a session, so it is bound on `engaged` and cleared the moment the map goes.
    effect(() => {
      const map = this.client.channelMap();
      if (map) {
        this.virtual.bindTo(map);
      } else {
        this.virtual.clear();
      }
    });
  }

  ngOnInit(): void {
    this.rc.start();
    // Silent on failure: without a layout nothing is bound, which the drawer already renders as
    // "no switches are bound" rather than as an error the operator can act on.
    void this.profiles.load().catch(() => undefined);
  }

  protected useSource(kind: RcSourceKind): void {
    if (!this.sourceLocked()) {
      this.source.use(kind);
    }
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
