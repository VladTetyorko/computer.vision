import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import { TransmitterView } from '../../shared/ui/transmitter-view/transmitter-view';
import { RcInputService } from '../../core/rc/rc-input.service';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import { KeyboardRcInputService } from '../../core/rc/keyboard-rc-input.service';
import { RcSource, type RcSourceKind } from '../../core/rc/rc-source.service';
import { ManualControlClient } from '../../core/rc/manual-control-client';
import { ControlActionDispatcher } from '../../core/rc/control-action-dispatcher';
import { ControlProfileStore } from '../../core/rc/control-profile-store';
import { activeProfileFor } from '../../core/rc/control-action-logic';
import { VisionApi } from '../../core/api/vision-api';
import { FlightCommandPanel } from './flight-command-panel';
import {
  armedChip,
  armAlsoOnHint,
  engageBlock,
  engageDisabledReason,
  keyLegendLines,
  latencyLabel,
  modeAlsoOnHint,
  resolveSessionAffordance,
  sampleIsStale,
  type EngageGateInput,
} from './rc-monitor-logic';
import { normalizeChannelMap, type ChannelMapLike } from '../../core/rc/transmitter-view-logic';
import type { FlightCapability, ReadinessReport, VehicleKind } from '../../core/api/models';

/**
 * `vision-rc-monitor` — the Fly cockpit's Controller drawer, rebuilt around `vision-transmitter-view`
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.2, wave X2 — superseding the raw axis-bar/switch-pill
 * monitor Phase 0 (docs/plans/active/RC-CONTROL-PLAN.md) and R5 (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md)
 * originally shipped). Anatomy, top to bottom: a chips-only state strip, the transmitter picture
 * (before *and* during a session — decision U1), `<vision-flight-command-panel>`'s mode/arm/disarm
 * rows with "also on <switch>" hints (U3), and a sticky footer carrying the input-source picker and
 * engage/release (U6, via `SidePanel`'s existing `[footer]` slot).
 *
 * Provides the whole RC stack — `RcInputService`, `VirtualRcInputService`, `RcSource` and
 * `ManualControlClient` — and drives `RcInputService`'s lifecycle (reading starts when this drawer
 * mounts, per `ngOnInit`); `ManualControlClient` needs no explicit start: its own constructor wires
 * the deadman triggers, and its own `DestroyRef` teardown (this component unmounting, i.e. the RC
 * panel closing) is one of them. The cockpit mounts this component via `@if (isPanelOpen('rc'))`,
 * so both the Gamepad rAF loop and any live relay session only exist while the drawer is open.
 *
 * <h2>One picture, mirror or interactive</h2>
 * `vision-transmitter-view` draws whatever `channelMap`/`actionMap` this operator is bound to, fed
 * live `axes`/`buttons` from whichever `RcSource` is selected — a plugged transmitter or the
 * on-screen surface. It renders **before** engage so the operator sees their own sticks/switches
 * move immediately (decision U1); once engaged it becomes interactive only for the on-screen source
 * (decision U2) — a mirrored transmitter is never draggable, dragging a mirror of hardware sticks
 * would be meaningless. `transmitterChannelMap` prefers the server's own `engaged.channelMap` once a
 * session exists ("it's what the server is really applying"), falling back to this operator's own
 * resolved layout for the vehicle kind before/without one.
 *
 * <h2>A transmitter is no longer required</h2>
 * Control can come from a plugged-in gamepad or from the on-screen surface
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10) — `RcSource` picks, and prefers a
 * connected gamepad. The surface is shaped by the engaged `channelMap`, so a rover gets one
 * steer/drive pad and an aircraft two, without this component knowing the difference.
 *
 * <h2>One drawer, not two</h2>
 * Mode and arm/disarm live inside this drawer as of docs/plans/active/CONTROLLER-SETUP-CONTEXT.md
 * decision C10 — `<vision-flight-command-panel>` is body-only now and this is its shell. This
 * component only passes `capabilities`/`armed`/the "also on" hints through — it owns none of that
 * panel's commands or confirms.
 */
@Component({
  selector: 'vision-rc-monitor',
  imports: [SidePanel, Notice, TransmitterView, FlightCommandPanel, RouterLink, NgTemplateOutlet],
  providers: [
    RcInputService,
    VirtualRcInputService,
    KeyboardRcInputService,
    RcSource,
    ManualControlClient,
    ControlActionDispatcher,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './rc-monitor.html',
  styleUrl: './rc-monitor.css',
})
export class RcMonitor implements OnInit {
  protected readonly rc = inject(RcInputService);
  protected readonly source = inject(RcSource);
  protected readonly client = inject(ManualControlClient);
  protected readonly dispatcher = inject(ControlActionDispatcher);
  protected readonly virtual = inject(VirtualRcInputService);
  protected readonly keyboard = inject(KeyboardRcInputService);
  protected readonly profiles = inject(ControlProfileStore);
  private readonly api = inject(VisionApi);

  /** The currently-flown asset — mirrors `flight-command-panel.ts`'s own `assetId`/
   * `assetDisplayName` inputs (`cockpit.html` renders both components inside the same
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
  /** Latest telemetry's `flightState.armed` — the state strip's armed chip and the flight
   * section's disarm copy both read it. */
  readonly armed = input<boolean | undefined>(undefined);
  /** Latest telemetry's `flightState.mode` — the state strip's mode chip. `undefined` omits the
   * chip entirely (CLAUDE.md's "degrade honestly": no signal, no fabricated reading) rather than
   * guessing from `capabilities().selectableModes`, which names what the vehicle *could* be set to,
   * not what it is actually in right now. */
  readonly mode = input<string | undefined>(undefined);
  /** `TelemetryStore.sampleAgeSeconds()` — the same age the OSD's own Link/Power groups grade
   * staleness on (docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1), threaded through so the state
   * strip's armed chip drops its confident tone/text and the mode chip fades once the reading
   * backing both is stale. `cockpit.html` wires this from `facade.telemetry.sampleAgeSeconds()`,
   * the same signal source `armed`/`mode` above already read alongside. */
  readonly sampleAgeSeconds = input<number | undefined>(undefined);
  readonly close = output<void>();

  // --- Asset session (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4) -----------------
  // A different verb pair from `engage()`/`release()` below, which is this drawer's own RC
  // take-control gesture (`ManualControlClient`) — `AssetSessionController#engage`/`#disengage`
  // instead open/close the `AssetUsage` itself, no stick input involved. See
  // `resolveSessionAffordance`'s own doc comment (`rc-monitor-logic.ts`) for the full picture.

  /** `CockpitFacade.hasTelemetryDevice` — whether an "Engage link"/"End session" affordance could
   * ever apply to this asset at all. */
  readonly hasTelemetryDevice = input<boolean>(false);
  /** `CockpitFacade.live` — a running video stream already opens the same kind of usage. */
  readonly live = input<boolean>(false);
  /** `CockpitFacade.operatorEngaged` — read honestly off the polled `recentUsages`, never a local
   * "I clicked it" flag (see that computed's own doc comment). */
  readonly operatorEngaged = input<boolean>(false);
  /** `CockpitFacade.sessionBusy` — disables the button for the life of the in-flight request, the
   * same "no double-submit" posture `flight-command-panel.ts` already applies to its own commands. */
  readonly sessionBusy = input<boolean>(false);
  readonly engageAssetLink = output<void>();
  readonly endAssetSession = output<void>();

  protected readonly sessionAffordance = computed(() =>
    resolveSessionAffordance(this.hasTelemetryDevice(), this.live(), this.operatorEngaged()),
  );

  protected readonly latencyLabel = latencyLabel;
  /** Drives the mode chip's faint styling alongside the armed chip's own tone drop — see
   * {@link sampleAgeSeconds}'s own doc comment. */
  protected readonly staleSample = computed(() => sampleIsStale(this.sampleAgeSeconds()));

  /**
   * The layout this operator's switches currently fire through — resolved in the browser from the
   * vehicle kind the capability read already carries, which is what lets a bound switch work
   * *before* taking stick control (decision C3). The backend resolves the same thing again at
   * engage time; these agree because both call the same rule.
   */
  protected readonly activeProfile = computed(() =>
    activeProfileFor(this.profiles.profiles(), this.capabilities()?.vehicleKind),
  );
  /** Only the action-bound controls — passed to the transmitter view's switch-gauge rows. */
  protected readonly actionBindings = computed(() => this.activeProfile()?.actionMap ?? []);
  protected readonly modeAlsoOn = computed(() => modeAlsoOnHint(this.actionBindings()));
  protected readonly armAlsoOn = computed(() => armAlsoOnHint(this.actionBindings()));

  protected readonly armedChipView = computed(() => armedChip(this.armed(), this.sampleAgeSeconds()));

  /** Whichever `ChannelMapLike` the transmitter view draws (docs/plans/active/CONTROLLER-UX-PLAN.md
   * §2.2's own instruction: prefer the engaged frame's own map once one exists, "it's what the
   * server is really applying"); falls back to this operator's own resolved layout so the picture
   * is live before/without a session too (decision U1). */
  protected readonly transmitterChannelMap = computed<ChannelMapLike>(
    () => this.client.channelMap() ?? this.activeProfile()?.channelMap ?? [],
  );
  /** {@link transmitterChannelMap} in the one shape `padsFrom`/the keyboard source both read —
   * computed once and shared by both, rather than each normalizing its own copy. */
  protected readonly normalizedChannelMap = computed(() =>
    normalizeChannelMap(this.transmitterChannelMap(), this.profiles.catalog()),
  );
  /** The keyboard footer legend (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave K) — one quiet line
   * per pad this layout actually has, built from the same map the transmitter picture already
   * draws. */
  protected readonly keyLegend = computed(() => keyLegendLines(this.normalizedChannelMap()));
  protected readonly vehicleKind = computed<VehicleKind>(
    () => this.client.vehicleKind() ?? this.capabilities()?.vehicleKind ?? 'UNKNOWN',
  );
  /** Engaged + the on-screen surface selected → the same picture becomes draggable (decision U2). A
   * plugged transmitter always stays a mirror, engaged or not; the keyboard source is a mirror too —
   * there is nothing to drag, the picture just reflects what the held keys are already driving. */
  protected readonly interactive = computed(
    () => this.client.state() === 'engaged' && this.source.kind() === 'virtual',
  );

  private readonly engageGate = computed<EngageGateInput>(() => ({
    hasAsset: this.assetId().length > 0,
    canCommand: this.canCommand(),
    sourceKind: this.source.kind(),
    gamepadConnected: this.rc.connected(),
    engageState: this.client.state(),
  }));
  protected readonly disabledReason = computed(() => engageDisabledReason(this.engageGate()));
  protected readonly engageDisabled = computed(() => this.disabledReason() !== undefined);
  /** The input choice is frozen for the life of a session — swapping sticks mid-flight is not a
   * gesture this platform offers, and the surface below is shaped by the engaged map anyway. */
  protected readonly sourceLocked = computed(() => this.client.state() === 'engaging' || this.client.state() === 'engaged');

  /** This asset's own `rc-relay` readiness row (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave R) —
   * read directly here rather than plumbed through `CockpitFacade`/`cockpit.html`, since nothing
   * else in the cockpit needs it today; `undefined` while loading or on a failed read (CLAUDE.md
   * "degrade honestly" — the footer below renders nothing for that, never a fabricated warning). */
  private readonly _readiness = signal<ReadinessReport | undefined>(undefined);
  /** What renders under the Take-control button: `engageDisabledReason`'s own text while a more
   * fundamental gate blocks, else the RC-relay readiness row(s) once one is actually available —
   * advisory only, never affecting {@link engageDisabled} itself. */
  protected readonly block = computed(() => engageBlock(this.engageGate(), this._readiness()));

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

    // Unlike the on-screen surface, the keyboard has no session of its own to wait for — a tap
    // before engage should already move the transmitter picture (decision U1 extends to this third
    // source too), so it is kept bound to the same live map continuously, not just from `engaged`.
    effect(() => this.keyboard.bind(this.normalizedChannelMap()));

    // Re-read on every asset change; a failed read degrades to `undefined` (CLAUDE.md), which the
    // footer already renders as nothing rather than an error the operator can't act on from here —
    // the full picture, with a retry, lives at `/operate/preflight`.
    effect(() => {
      const assetId = this.assetId();
      this._readiness.set(undefined);
      void this.api
        .assetReadiness(assetId)
        .then((report) => this._readiness.set(report))
        .catch(() => this._readiness.set(undefined));
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

  /** `vision-transmitter-view`'s `valuesChange` — absorbs `virtual-control-surface.ts`'s own
   * pointer/keyboard math verbatim (it now lives inside the shared component), forwarding straight
   * to the service that owns the on-screen surface's actual values. */
  protected onValuesChange(event: { axisIndex: number; value: number }): void {
    this.virtual.set(event.axisIndex, event.value);
  }
}
