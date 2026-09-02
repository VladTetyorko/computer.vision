import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SidePanel } from '../../shared/ui/side-panel';
import { Notice } from '../../shared/ui/notice';
import { TransmitterView, type ActionKeyRow } from '../../shared/ui/transmitter-view/transmitter-view';
import { RcInputService } from '../../core/rc/rc-input.service';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import { KeyboardRcInputService } from '../../core/rc/keyboard-rc-input.service';
import { RcSource } from '../../core/rc/rc-source.service';
import { ManualControlClient } from '../../core/rc/manual-control-client';
import { ControlActionDispatcher } from '../../core/rc/control-action-dispatcher';
import { ControlProfileStore } from '../../core/rc/control-profile-store';
import { activeProfileFor } from '../../core/rc/control-action-logic';
import { VisionApi } from '../../core/api/vision-api';
import { ModePicker } from './mode-picker';
import {
  actionKeyRows,
  armedChip,
  engageBlock,
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
 * `vision-rc-monitor` — the Fly cockpit's Controller drawer, now **purely informational**
 * (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3): the transmitter picture, per-axis mapping/mode
 * picking, device/rate/latency chips, hints, and diagnostics. Anatomy, top to bottom: a chips-only
 * state strip, the transmitter picture, `<vision-mode-picker>`, the asset session link, and a
 * diagnostics block spelling out — in full sentences — whatever the HUD's own Take-control
 * pill/badge abbreviated to an icon, a word, or a `title`.
 *
 * <h2>§0's reversal — Take-control/Arm/Disarm/the input-source picker all moved to `fly-hud.ts`</h2>
 * §0's own framing: "chips, bars and pills move onto the video; every *sentence* leaves it." This
 * drawer used to own the sticky Take-control/Release footer (decision U6) and the mode/arm/disarm
 * panel (decision C10) — both are now on the video itself, the live control surface an operator
 * reaches for *while* holding the sticks. What's left here is exactly what R2's own GCS survey says
 * stays off video: mapping, setup, diagnostics. **Opening this drawer is never required to take
 * control or arm** — both work from the always-visible HUD whether or not this panel is open.
 *
 * <h2>No providers of its own — inherits the RC stack from `fly-hud.ts`</h2>
 * `RcInputService`/`VirtualRcInputService`/`KeyboardRcInputService`/`RcSource`/`ManualControlClient`/
 * `ControlActionDispatcher` are now provided by `fly-hud.ts`, this component's own host — hierarchical
 * DI resolves every `inject()` call below to that single shared instance set, so a bound switch fires,
 * the transmitter picture animates, and a live session survives exactly the same whether or not this
 * drawer happens to be open. `fly-hud.ts` mounts unconditionally (the HUD's "at rest" badge must
 * always be visible), so — unlike before this wave — the Gamepad rAF loop now runs for the life of
 * the cockpit route, not just while this drawer is open; see `fly-hud.ts`'s own doc comment.
 *
 * <h2>One picture, mirror or interactive</h2>
 * `vision-transmitter-view` draws whatever `channelMap`/`actionMap` this operator is bound to, fed
 * live `axes`/`buttons` from whichever `RcSource` is selected — a plugged transmitter, the on-screen
 * surface, or the keyboard. It renders **before** engage so the operator sees their own
 * sticks/switches move immediately (decision U1); once engaged it becomes interactive only for the
 * on-screen source (decision U2) — a mirrored transmitter is never draggable. `transmitterChannelMap`
 * prefers the server's own `engaged.channelMap` once a session exists, falling back to this
 * operator's own resolved layout for the vehicle kind before/without one.
 *
 * <h2>Diagnostics, not gates</h2>
 * `disabledReason`/`block`/`_readiness` all still exist here, computed off the exact same pure
 * functions `fly-hud.ts` calls for its own Take-control pill — but nothing here disables a button
 * (there is none left to disable): this component only ever renders their text, the full-sentence
 * counterpart to whatever the HUD showed as an icon/word/`title`.
 */
@Component({
  selector: 'vision-rc-monitor',
  imports: [SidePanel, Notice, TransmitterView, ModePicker, RouterLink],
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
  /** `<vision-mode-picker>`'s own `modeAlsoOn` input — see `rc-monitor-logic.ts#modeAlsoOnHint`.
   * The Arm/Disarm row's identical hint (`armAlsoOnHint`) now lives in `fly-hud.ts`, which owns
   * `<vision-flight-command-panel>` directly — not duplicated logic, a second call site against the
   * same pure function reading each component's own `activeProfile`. */
  protected readonly modeAlsoOn = computed(() => modeAlsoOnHint(this.actionBindings()));

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
  /** The transmitter picture's keyboard action-key rows (docs/plans/active/MAVLINK-COMMANDS-PLAN.md
   * D3, wave W2 — closes wave W1's own "key legend doesn't list the action keys" gap). `[]` whenever
   * the keyboard isn't the selected source: `KeyboardRcInputService` only attaches its window
   * listeners then (`RcSource#keyboard.setEnabled`), so advertising Space/`Shift`+`Enter`/`1`-`4`
   * outside that selection would be discoverability for chords that, right now, do nothing. Reads
   * `capabilities()?.selectableModes` (the same capability read `ControlActionDispatcher` makes on
   * its own account) and `profiles.rules().dangerous` (the identical `ControlActionRules` the
   * dispatcher binds), so this legend's dangerous/hold state can never disagree with what actually
   * fires. */
  protected readonly keyActionRows = computed<readonly ActionKeyRow[]>(() =>
    this.source.kind() === 'keyboard'
      ? actionKeyRows(
          this.capabilities()?.selectableModes ?? [],
          this.profiles.rules().dangerous,
          this.armed(),
          this.keyboard.actionKeysDown(),
          this.dispatcher.holding(),
        )
      : [],
  );
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
  /** This asset's own `rc-relay` readiness row (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave R) —
   * read directly here rather than plumbed through `CockpitFacade`/`cockpit.html`, since nothing
   * else in the cockpit needs it today; `undefined` while loading or on a failed read (CLAUDE.md
   * "degrade honestly" — this drawer's diagnostics block renders nothing for that, never a
   * fabricated warning). */
  private readonly _readiness = signal<ReadinessReport | undefined>(undefined);
  /** This drawer's diagnostics block: `engageDisabledReason`'s own text while a more fundamental gate
   * blocks, else the RC-relay readiness row(s) once one is actually available — always advisory, this
   * component owns no button for it to gate. */
  protected readonly block = computed(() => engageBlock(this.engageGate(), this._readiness()));

  constructor() {
    // `fly-hud.ts` — this component's own host — owns the dispatcher-bind/virtual-bindTo/
    // keyboard-bind effects now (its own doc comment explains why: they must keep running whether or
    // not this drawer happens to be open, since a bound switch fires over the ordinary command
    // endpoints independently of any session). This component's own `activeProfile`/
    // `transmitterChannelMap`/`normalizedChannelMap` computeds below are pure re-derivations of the
    // identical shared signals for this drawer's own read-only rendering — safe to keep independent
    // (no effect, no write), unlike those three writes, which must have exactly one owner.

    // This asset's own `rc-relay` readiness read — re-fetched here independently of `fly-hud.ts`'s own
    // copy (a second GET while this drawer happens to be open, not a shared derivation) since a plain
    // read has no state to race, and plumbing the report down as an input would add coupling this
    // component doesn't otherwise need just for its own diagnostics block. Re-read on every asset
    // change; a failed read degrades to `undefined` (CLAUDE.md), which this drawer already renders as
    // nothing rather than an error the operator can't act on from here — the full picture, with a
    // retry, lives at `/operate/preflight`.
    effect(() => {
      const assetId = this.assetId();
      this._readiness.set(undefined);
      void this.api
        .assetReadiness(assetId)
        .then((report) => this._readiness.set(report))
        .catch(() => this._readiness.set(undefined));
    });
  }

  /** `fly-hud.ts` starts `RcInputService`'s own gamepad rAF loop once, for the life of the cockpit
   * route, since the HUD's badge/pills need a live `rc.connected()` whether or not this drawer is
   * open — see that class's own doc comment. This component no longer calls `rc.start()` itself. */
  ngOnInit(): void {
    // Silent on failure: without a layout nothing is bound, which this drawer already renders as
    // "nothing on your transmitter is bound" rather than as an error the operator can act on.
    void this.profiles.load().catch(() => undefined);
  }

  /** `vision-transmitter-view`'s `valuesChange` — absorbs `virtual-control-surface.ts`'s own
   * pointer/keyboard math verbatim (it now lives inside the shared component), forwarding straight
   * to the service that owns the on-screen surface's actual values. */
  protected onValuesChange(event: { axisIndex: number; value: number }): void {
    this.virtual.set(event.axisIndex, event.value);
  }
}
