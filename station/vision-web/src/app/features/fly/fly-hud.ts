import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { FlightCommandPanel } from './flight-command-panel';
import { RcMonitor } from './rc-monitor';
import { TakeControlModal } from './take-control-modal';
import { RcInputService } from '../../core/rc/rc-input.service';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import { KeyboardRcInputService } from '../../core/rc/keyboard-rc-input.service';
import { RcSource, type RcSourceKind } from '../../core/rc/rc-source.service';
import { ManualControlClient, type ManualControlEngageState } from '../../core/rc/manual-control-client';
import { ControlActionDispatcher } from '../../core/rc/control-action-dispatcher';
import { ControlProfileFacade } from '../../core/rc/control-profile-facade';
import { ThresholdsFacade } from '../../core/ops/thresholds-facade';
import { ToastService } from '../../core/toast.service';
import { activeProfileFor } from '../../core/rc/control-action-logic';
import { armAlsoOnHint, engageDisabledReason, type EngageGateInput } from './rc-monitor-logic';
import { normalizeChannelMap, type ChannelMapLike } from '../../core/rc/transmitter-view-logic';
import { neutralGateReason } from '../../core/rc/neutral-gate-logic';
import {
  axisKeyGlyphs,
  hudBadgeFor,
  hudElementsFrom,
  hudTransitionToast,
  openTakeControlDisabledReason,
  sourceLocked,
  type HudElement,
  type OpenTakeControlGateInput,
} from '../../core/rc/fly-hud-logic';
import { REST_VALUE, displayPercentFor, knobLeftPercent, knobTopPercent, padsFrom } from '../../core/rc/control-surface-logic';
import type { FlightCapability, ManualControlChannelBinding } from '../../core/api/models';
import type { PreflightItem } from '../../core/telemetry/flight-state-logic';
import type { FlyStage } from './fly-logic';

/**
 * `vision-fly-hud` — the on-video control HUD (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3), reversing
 * CONTROLLER-UX U5's "everything in the drawer" call: chips, bars and pills now float on the video
 * itself; every full sentence stays off it (toast, `title`, or the informational rail this component
 * still hosts). Mounted unconditionally by `cockpit.html` inside `.grid-main` (once an asset is
 * selected) — the at-rest badge/Take-control pill must always be reachable, not only while a drawer
 * happens to be open.
 *
 * <h2>The sole owner of the RC provider stack</h2>
 * This component's own `providers` array is now where `RcInputService`/`VirtualRcInputService`/
 * `KeyboardRcInputService`/`RcSource`/`ManualControlClient`/`ControlActionDispatcher` live — moved
 * here from `rc-monitor.ts`, which used to own them (and used to only mount, and only run its
 * Gamepad rAF poll loop, while its own drawer was open). `<vision-rc-monitor>` is now a template-
 * nested *child* of this component with no `providers` of its own, so hierarchical DI resolves its
 * `inject()` calls to these exact instances — a bound switch fires, the transmitter picture animates,
 * and a live session survives identically whether or not that drawer happens to be open.
 *
 * **Lifecycle consequence, stated plainly**: the Gamepad `requestAnimationFrame` loop
 * (`RcInputService#start`) now runs for the entire life of the cockpit route, not only while the
 * Controller drawer was open, since this HUD's own "No link"/"Ready" badge needs a live
 * `rc.connected()` at rest too. A background, idle rAF polling `navigator.getGamepads()` is cheap
 * (this is exactly what the Gamepad API is for), so this is accepted as the honest cost of the badge
 * actually being live rather than a flag that only updates once a drawer is opened to look.
 *
 * <h2>This component also owns the three write-effects that used to live in `rc-monitor.ts`</h2>
 * `dispatcher.bind`/`dispatcher.setArmed`, `virtual.bindTo`/`virtual.clear`, and `keyboard.bind` each
 * have **exactly one** caller now — this class's own constructor — never duplicated across this
 * component and `rc-monitor.ts`. That single-owner rule is load-bearing, not cosmetic:
 * `KeyboardRcInputService#bind` unconditionally resets every ramped value
 * (`this._values.set(new Map())`), so two independent components each re-deriving the "same" channel
 * map and calling `bind()` on every recompute could reset a stick mid-ramp for no operator-visible
 * reason. `rc-monitor.ts` keeps its own *read-only* re-derivations of the identical underlying
 * signals (`activeProfile`/`transmitterChannelMap`/`normalizedChannelMap`) for its own rendering —
 * safe to duplicate, since a `computed()` with no side effect can never race another.
 *
 * <h2>Zones (§3's own contract, at-rest content revised by FLY-FLOW-PLAN.md §4 W4)</h2>
 * Bottom-center, at rest: the badge and the Take-control pill — nothing else (docs/plans/active/
 * FLY-FLOW-PLAN.md §4 W4 item 2, owner's own words: *"at rest the dock is `[badge] [Take control]` —
 * nothing else"*). Once `client.state() === 'engaged'`: the live input widget (bars/a 2D glyph/a
 * keyboard key-glyph ticker) + a Release pill, plus the locked source pill (unchanged — see this
 * class's own "The connect ritual" section below for why that one pill survives while its at-rest
 * twin doesn't). Bottom-right: this component's own `<vision-flight-command-panel>` (Arm/Disarm). The
 * informational rail (`<vision-rc-monitor>`) mounts only while `rcPanelOpen()` — this component
 * forwards `close` as {@link closeRcPanel} rather than owning the `UiStore` toggle itself, which stays
 * `cockpit.ts`'s own tool-rail concern (this component has no opinion on *where* the rail lives, only
 * on what feeds it).
 *
 * <h2>The connect ritual (FLY-FLOW-PLAN.md §4 W4 item 2) — history, not just current state</h2>
 * §3 (wave WEB1) originally wanted the at-rest zone bare — "ONE frosted 'Take control' pill + one
 * commandable badge ... Nothing else new on video" — but that wave added the three input-source pills
 * at rest anyway, flagged as a deliberate deviation: {@link sourceLocked} freezes the source choice
 * the instant a handshake starts, so a picker that only appeared *after* Take Control would give the
 * operator no way to ever choose anything but whatever `RcSource` already held. W4's owner review
 * called that same always-on trio "overwhelming" and asked for a modal instead — so the pills are
 * deleted again, this time for good, and {@link openTakeControl}/`<vision-take-control-modal>`
 * (mounted below, `@if`-gated on {@link takeControlOpen}) now hold the picker: it opens *before* the
 * handshake starts, closes the exact same gap WEB1's deviation existed to close, and does it without
 * three pills sitting on the video at every rest frame. The engaged-state source pill is unaffected —
 * that one shows what is actually driving the vehicle post-engage, a different fact from "what could I
 * pick", and stays exactly as before.
 *
 * A second friction, unrelated to the modal and still live: the on-screen source's actual *draggable*
 * stick surface (`vision-transmitter-view`'s `[interactive]` mode) lives only inside
 * `<vision-rc-monitor>` — §3 describes the HUD's own engaged widget as a read-only live *readout*
 * ("bars/glyphs"), never a drag target, and building a second interactive surface directly on the
 * video is out of scope for either wave. Left alone, an operator who confirms "On-screen" in the modal
 * and never opens the rail would have selected a source with no way to actually move it.
 * {@link selectSource} closes that gap the cheap way, unchanged since WEB1: choosing `'virtual'` also
 * emits {@link openRcPanel} — now reached via {@link confirmTakeControl} rather than a pill's own
 * click, same side effect either way.
 */
@Component({
  selector: 'vision-fly-hud',
  imports: [Icon, FlightCommandPanel, RcMonitor, TakeControlModal],
  templateUrl: './fly-hud.html',
  styleUrl: './fly-hud.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RcInputService, VirtualRcInputService, KeyboardRcInputService, RcSource, ManualControlClient, ControlActionDispatcher],
})
export class FlyHud implements OnInit {
  protected readonly rc = inject(RcInputService);
  protected readonly source = inject(RcSource);
  protected readonly client = inject(ManualControlClient);
  protected readonly dispatcher = inject(ControlActionDispatcher);
  protected readonly virtual = inject(VirtualRcInputService);
  protected readonly keyboard = inject(KeyboardRcInputService);
  protected readonly profiles = inject(ControlProfileFacade);
  private readonly thresholds = inject(ThresholdsFacade);
  private readonly toasts = inject(ToastService);

  readonly assetId = input.required<string>();
  readonly assetDisplayName = input.required<string>();
  readonly canCommand = input<boolean>(false);
  readonly capabilities = input<FlightCapability | undefined>(undefined);
  readonly armed = input<boolean | undefined>(undefined);
  readonly mode = input<string | undefined>(undefined);
  readonly sampleAgeSeconds = input<number | undefined>(undefined);
  readonly hasTelemetryDevice = input<boolean>(false);
  readonly live = input<boolean>(false);
  readonly operatorEngaged = input<boolean>(false);
  readonly sessionBusy = input<boolean>(false);
  /** `CockpitFacade.stage()` (`fly-logic.ts#flyStage`, docs/plans/active/FLY-FLOW-PLAN.md §4 W1) — this
   * component's own `.hud-bottom-center` zone renders only at `'live'`/`'engaged'`, ceding the one
   * bottom-center anchor to `cockpit.html`'s `.dock` at `'idle'`/`'starting'`. Deliberately a plain
   * `input()`, not read from a facade injected here: this component has no dependency on
   * `CockpitFacade` today (its own inputs are already the full contract with `cockpit.ts`), and
   * `client.state()` — this component's *other* notion of "engaged" — lives in this component's own
   * injector, not the facade's, so the two can never be unified into one signal anyway (see
   * `fly-logic.ts#flyStage`'s own doc comment, "the two 'engaged's are not the same thing, on
   * purpose", for the general shape of that mismatch). */
  readonly stage = input<FlyStage>('idle');
  /** `GroundingStore.groundedReason` (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "S1 gate semantics") —
   * forwarded straight through to `<vision-flight-command-panel>`, composed there with
   * {@link sticksNotNeutralReason} (grounding wins). */
  readonly groundedReason = input<string | undefined>(undefined);
  /** `CockpitFacade#preflightItems` (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 item 2a) — forwarded
   * straight through to `<vision-take-control-modal>`, since this component has no `CockpitFacade` of
   * its own (see this class's own doc comment). `[]` before the facade's first telemetry read
   * resolves; `<vision-preflight-checklist>` already renders that honestly (every row `'unknown'`,
   * never fabricated). */
  readonly preflightItems = input<readonly PreflightItem[]>([]);
  /** `cockpit.ts`'s own `panels` `UiStore` — whether the informational rail is open. This component
   * owns no toggle of its own; it only reads whether to mount `<vision-rc-monitor>` and forwards its
   * `close`. */
  readonly rcPanelOpen = input<boolean>(false);
  readonly closeRcPanel = output<void>();
  /** Emitted when picking the on-screen source ought to open the rail — see this class's own doc
   * comment's "The connect ritual" section, second paragraph ("A second friction..."). */
  readonly openRcPanel = output<void>();
  readonly engageAssetLink = output<void>();
  readonly endAssetSession = output<void>();

  // --- Layout/mapping (read-only re-derivations; the write-effects below are what actually bind
  // these into the dispatcher/virtual surface/keyboard service) -----------------------------------

  protected readonly activeProfile = computed(() => activeProfileFor(this.profiles.profiles(), this.capabilities()?.vehicleKind));
  private readonly actionBindings = computed(() => this.activeProfile()?.actionMap ?? []);
  /** `<vision-flight-command-panel>`'s own `armAlsoOn` input — see `rc-monitor.ts#modeAlsoOn`'s
   * doc comment for why this is a second call site against the same pure function, not duplicated
   * logic. */
  protected readonly armAlsoOn = computed(() => armAlsoOnHint(this.actionBindings()));
  /** Prefers the engaged frame's own map once a session exists, falling back to this operator's own
   * resolved layout before/without one — what the keyboard service binds against, so `W`/`A`/`S`/`D`
   * work before engaging too (`keyboard-rc-input.service.ts`'s own doc comment). */
  private readonly transmitterChannelMap = computed<ChannelMapLike>(
    () => this.client.channelMap() ?? this.activeProfile()?.channelMap ?? [],
  );
  private readonly normalizedChannelMap = computed(() => normalizeChannelMap(this.transmitterChannelMap(), this.profiles.catalog()));
  private readonly onePad = computed(() => padsFrom(this.normalizedChannelMap()).length <= 1);
  /** The keyboard ticker's own key set — see `fly-hud-logic.ts#axisKeyGlyphs`'s own doc comment. */
  protected readonly keyGlyphs = computed(() => axisKeyGlyphs(this.onePad()));

  // --- At-rest badge + Take-control gate ----------------------------------------------------------

  protected readonly badge = computed(() =>
    hudBadgeFor({ canCommand: this.canCommand(), sourceKind: this.source.kind(), gamepadConnected: this.rc.connected() }),
  );

  private readonly engageGate = computed<EngageGateInput>(() => ({
    hasAsset: this.assetId().length > 0,
    canCommand: this.canCommand(),
    sourceKind: this.source.kind(),
    gamepadConnected: this.rc.connected(),
    engageState: this.client.state(),
  }));
  /** {@link requestEngage}'s own internal poka-yoke guard, against the *committed* `source.kind()` —
   * no longer the Take-control pill's own displayed reason (FLY-FLOW-PLAN.md §4 W4: see
   * {@link openDisabledReason} below for that). Kept as defense-in-depth against a future direct
   * caller of {@link requestEngage} that bypasses the connect-ritual modal entirely. */
  private readonly disabledReason = computed(() => engageDisabledReason(this.engageGate()));
  private readonly engageDisabled = computed(() => this.disabledReason() !== undefined);

  /** The at-rest Take-control pill's own poka-yoke reason/title (FLY-FLOW-PLAN.md §4 W4 item 2) —
   * `fly-hud-logic.ts#openTakeControlDisabledReason`'s own doc comment explains why this is a
   * narrower gate than {@link disabledReason} above: it must never block on the *selected* source,
   * since picking a different one is exactly what the modal this pill opens is for. */
  private readonly openGate = computed<OpenTakeControlGateInput>(() => ({
    canCommand: this.canCommand(),
    engageState: this.client.state(),
  }));
  protected readonly openDisabledReason = computed(() => openTakeControlDisabledReason(this.openGate()));
  protected readonly openDisabled = computed(() => this.openDisabledReason() !== undefined);

  /** Whether `<vision-take-control-modal>` is mounted (`fly-hud.html`'s own `@if`) — see this class's
   * own doc comment's "connect ritual" section. Closed automatically once the session actually
   * reaches `engaged` (the constructor effect below); Cancel/scrim-click/`Esc`/the header "×" all
   * close it early without touching any in-flight handshake (`take-control-modal.ts`'s own doc
   * comment explains why closing is not a promise to abort). */
  protected readonly takeControlOpen = signal(false);

  /** §3's own "input choice is frozen for the life of a session" — the engaged-state source pill locks
   * the instant a handshake starts, not only once `engaged` (mirrors `fly-hud-logic.ts#sourceLocked`'s
   * own doc comment: swapping mid-handshake is exactly as undoable as swapping mid-flight). The
   * connect-ritual modal keeps its own independent read of the identical function, scoped to its own
   * in-progress {@link TakeControlModal.selected} rather than this component's committed
   * `source.kind()` — see that class's own doc comment. */
  protected readonly sourceChoiceLocked = computed(() => sourceLocked(this.client.state()));

  // --- Engaged widget ------------------------------------------------------------------------------

  /** The engaged frame's own map, grouped into the HUD's live elements — `hudElementsFrom` never
   * reads a pre-engage profile map, since this widget only ever renders once `state() === 'engaged'`
   * (unlike `transmitterChannelMap` above, which the keyboard service needs *before* that). */
  protected readonly hudElements = computed<readonly HudElement[]>(() => hudElementsFrom(this.client.channelMap() ?? []));

  // --- Neutral-stick arm gate (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2) ------------------------

  /** `core/rc/neutral-gate-logic.ts#neutralGateReason`, fed `ThresholdsStore.rc()`'s own tolerance —
   * the first real consumer of that signal. `undefined` outside an engaging/engaged session, or once
   * every bound axis reads neutral. Forwarded to `<vision-flight-command-panel>`, which composes it
   * with {@link groundedReason} (grounding wins). */
  protected readonly sticksNotNeutralReason = computed(() =>
    neutralGateReason(
      this.client.state(),
      this.client.channelMap() ?? [],
      this.source.axes(),
      this.thresholds.rc().neutralTolerancePercent,
    ),
  );

  /** Tracks the previous `client.state()` purely for {@link hudTransitionToast}'s own edge detection
   * — read via `untracked()` inside the toast effect below so that effect depends only on
   * `client.state()`/`deniedReason()`/`watchdogTripped()`, never on its own bookkeeping signal. */
  private readonly previousEngageState = signal<ManualControlEngageState>('idle');

  constructor() {
    // --- The three write-effects moved from `rc-monitor.ts` — see this class's own doc comment for
    // why each must have exactly one owner. ---------------------------------------------------------
    effect(() => {
      this.dispatcher.bind(this.assetId(), this.activeProfile(), this.profiles.rules(), this.canCommand());
    });
    effect(() => this.dispatcher.setArmed(this.armed()));
    effect(() => {
      const map = this.client.channelMap();
      if (map) {
        this.virtual.bindTo(map);
      } else {
        this.virtual.clear();
      }
    });
    effect(() => this.keyboard.bind(this.normalizedChannelMap()));

    // --- Transient toasts (§3 "become toasts ... never persistent video text") ----------------------
    effect(() => {
      const to = this.client.state();
      const deniedReason = this.client.deniedReason();
      const watchdogTripped = this.client.watchdogTripped();
      const from = untracked(() => this.previousEngageState());
      const toast = hudTransitionToast(from, to, deniedReason, watchdogTripped);
      if (toast) {
        if (toast.kind === 'error') {
          this.toasts.error(toast.text);
        } else {
          this.toasts.warn(toast.text);
        }
      }
      this.previousEngageState.set(to);
    });

    // --- Connect-ritual auto-close (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 item 2, "auto-closes once
    // engaged") — lives beside the toast effect above since both watch the same `client.state()`
    // transition; a `'denied'`/`'released'` result deliberately leaves the modal open so the operator
    // reads the denial reason (`take-control-modal.html`'s own `@if (denied())` block) and can retry
    // without re-opening the ritual from scratch. -----------------------------------------------------
    effect(() => {
      if (this.client.state() === 'engaged') {
        this.takeControlOpen.set(false);
      }
    });
  }

  /** `rc.start()` moved here from `rc-monitor.ts` — see this class's own doc comment's "Lifecycle
   * consequence" section. */
  ngOnInit(): void {
    this.rc.start();
    // Silent on failure: without a layout nothing is bound, which the badge/pill already render
    // honestly (a bound switch simply does nothing) rather than as an error the operator can act on
    // from a HUD control.
    void this.profiles.load().catch(() => undefined);
  }

  /** Opens `<vision-take-control-modal>` (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 item 2) — the at-rest
   * pill's own click handler. Guarded on {@link openDisabled} the same way every other pill in this
   * component guards its own action, even though the template also disables the button itself
   * (defense-in-depth against a stray programmatic call). */
  protected openTakeControl(): void {
    if (this.openDisabled()) {
      return;
    }
    this.takeControlOpen.set(true);
  }

  /** `<vision-take-control-modal>`'s own `(closed)` — see that class's own doc comment for why this
   * never touches `client`/`source` (closing is not a promise to abort an in-flight handshake). */
  protected closeTakeControl(): void {
    this.takeControlOpen.set(false);
  }

  /** `<vision-take-control-modal>`'s own `(confirmed)` — reuses {@link selectSource}/{@link
   * requestEngage} verbatim rather than duplicating the commit/engage sequence, so choosing "On-screen"
   * still opens the informational rail exactly as it always has (this class's own doc comment's "The
   * connect ritual" section, second paragraph). Does not itself close the modal — {@link requestEngage} drives
   * `client.state()` to `'engaging'`, and the auto-close effect in the constructor above closes the
   * modal once (and only once) that resolves to `'engaged'`; a denial leaves it open so the operator
   * reads the reason in place. */
  protected confirmTakeControl(kind: RcSourceKind): void {
    this.selectSource(kind);
    this.requestEngage();
  }

  protected requestEngage(): void {
    if (this.engageDisabled()) {
      return;
    }
    this.client.engage(this.assetId());
  }

  protected release(): void {
    this.client.release();
  }

  protected selectSource(kind: RcSourceKind): void {
    if (this.sourceChoiceLocked()) {
      return;
    }
    this.source.use(kind);
    if (kind === 'virtual') {
      // See this class's own doc comment's "The connect ritual" section, second paragraph.
      this.openRcPanel.emit();
    }
  }

  // --- Live widget math (thin wrappers over `control-surface-logic.ts`'s own tested functions —
  // this file only ever derives grouping/positioning from them, never re-implements them) ----------

  protected axisValue(binding: ManualControlChannelBinding | undefined): number {
    return binding ? (this.source.axes()[binding.sourceIndex] ?? REST_VALUE) : REST_VALUE;
  }

  protected displayPercent(binding: ManualControlChannelBinding | undefined): number {
    return binding ? displayPercentFor(binding.travel, this.axisValue(binding)) : 50;
  }

  protected restPercent(binding: ManualControlChannelBinding | undefined): number {
    return binding ? displayPercentFor(binding.travel, REST_VALUE) : 50;
  }

  protected fillFrom(binding: ManualControlChannelBinding | undefined): number {
    return Math.min(this.restPercent(binding), this.displayPercent(binding));
  }

  protected fillSpan(binding: ManualControlChannelBinding | undefined): number {
    return Math.abs(this.displayPercent(binding) - this.restPercent(binding));
  }

  protected knobLeft(binding: ManualControlChannelBinding | undefined): number {
    return knobLeftPercent(binding, this.axisValue(binding));
  }

  protected knobTop(binding: ManualControlChannelBinding | undefined): number {
    return knobTopPercent(binding, this.axisValue(binding));
  }

  protected elementValueLabel(el: HudElement): string {
    switch (el.kind) {
      case 'bar':
        return `${this.displayPercent(el.x)}%`;
      case 'throttle':
        return `${this.displayPercent(el.y)}%`;
      case 'glyph2d':
        return `${this.displayPercent(el.x)}/${this.displayPercent(el.y)}`;
    }
  }
}
