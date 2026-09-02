import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { RcInputService } from '../../core/rc/rc-input.service';
import { RcSource, type RcSourceKind } from '../../core/rc/rc-source.service';
import { ManualControlClient } from '../../core/rc/manual-control-client';
import { sourceLocked } from '../../core/rc/fly-hud-logic';
import { PreflightChecklist } from '../../shared/ui/preflight-checklist';
import type { PreflightItem } from '../../core/telemetry/flight-state-logic';
import { engageDisabledReason, type EngageGateInput } from './rc-monitor-logic';

/**
 * The connect ritual (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 item 2, owner's own words: *"Take
 * control is overwhelming — better a modal with 3 choices and then, based on it, start"*) —
 * `fly-hud.html`'s at-rest Take-control pill now opens this instead of engaging directly. Top to
 * bottom: the pre-flight checklist (the exact rows the deleted `.main-preflight` floating chip
 * carried), the three-tile "Control with" picker, and a primary Start-control action.
 *
 * **Mounted inside `<vision-fly-hud>` (its own template, `@if`-gated on an open signal FlyHud owns),
 * not as a `cockpit.ts`-level dialog** — per the task's own instruction, "wired from fly-hud (which
 * owns `client`/`source`)". `RcInputService`/`RcSource`/`ManualControlClient` are all injected
 * directly here, unqualified: FlyHud's own `providers` array is where those live (its own doc
 * comment, "the sole owner of the RC provider stack"), and this component is a template-nested
 * descendant of it — hierarchical DI resolves every `inject()` below to FlyHud's own instances, the
 * identical pattern `<vision-rc-monitor>` already relies on. `assetId`/`canCommand`/`preflightItems`
 * are plain `@Input()`s instead (FlyHud already carries `assetId`/`canCommand` as its own inputs;
 * `preflightItems` is new — threaded from `cockpit.html`'s `facade.preflightItems()` through
 * `fly-hud.html`'s own `[preflightItems]` input, since FlyHud has no `CockpitFacade` of its own).
 *
 * <h2>Selecting a tile never touches `RcSource` until Start control fires</h2>
 * {@link selected} is this component's own local signal, defaulted to whatever `RcSource.kind()`
 * already reads at construction time — "remembering the last choice" (the plan's own wording) falls
 * out for free from `RcSource` itself never resetting its `_kind` on release (only an explicit
 * `use()`, or its own gamepad-plugged-in promotion, ever changes it — see that class's own doc
 * comment), so a returning operator who flew keyboard last time sees Keys pre-selected without this
 * component needing a persistence layer of its own. Browsing tiles is inert: nothing downstream
 * (the badge, the drawer, any other reader of `RcSource`) can observe a choice made in here until
 * {@link confirm} actually commits it — mirrored by this component *not* calling `source.use()`
 * itself at all; it emits {@link confirmed} and leaves the actual commit + engage call to
 * `fly-hud.ts#confirmTakeControl`, which reuses its own already-tested `selectSource`/`requestEngage`
 * methods verbatim (so picking "On-screen" still opens the informational rail the same way it always
 * has — see `fly-hud.ts`'s own "The connect ritual" doc-comment section for why that side effect
 * exists).
 *
 * <h2>The Start-control gate is a second, independent read of the same pure function</h2>
 * {@link startDisabledReason} calls `rc-monitor-logic.ts#engageDisabledReason` with {@link selected}
 * (the in-progress pick), not `RcSource.kind()` (the committed value) — deliberately a different
 * `EngageGateInput` than `fly-hud.ts`'s own `disabledReason`/`engageDisabled` (which now only gate the
 * outer pill's narrower "can the ritual even open" question, see `fly-hud-logic.ts#
 * openTakeControlDisabledReason`'s own doc comment for why the two must not share one gate). Once
 * {@link confirm} runs, `source.use(selected)` lands before `requestEngage()` reads `source.kind()`
 * again (Angular signals resolve synchronously), so the two gates agree again immediately — there is
 * never a render where they visibly disagree.
 *
 * <h2>Escape handling deliberately deviates from this app's "one `document` listener" rule</h2>
 * Every other dialog in this cockpit (`cv-setup-modal.ts`'s own doc comment) leaves `Esc` to
 * `cockpit.ts`'s single page-level `keydown` listener and its `collapseOverlays()` cascade
 * (`fly-logic.ts#nextCollapseAction`) — deliberately not extended to cover this modal: that cascade
 * lives on `CockpitPage`/`CockpitFacade`, neither of which can see anything inside `FlyHud`'s own
 * component-scoped injector (this modal's whole reason for existing here, not there). Reaching across
 * that boundary would mean `cockpit.ts` holding a `viewChild(FlyHud)` and a new public method on it
 * just for one key — more coupling than "wired from fly-hud" asks for. Instead this component adds
 * its own scoped listener, safe *because* it is only ever mounted while open (`fly-hud.html`'s own
 * `@if`) — `DestroyRef` removes it the instant the modal unmounts, so it can never fire while closed.
 * Flagged, accepted trade-off (this wave's own "flag, don't silently resolve" instruction): a
 * tool-rail drawer left open behind this modal would see *both* listeners react to the same
 * keypress — `cockpit.ts` closing the drawer via its own cascade, this one closing itself — a narrow,
 * cosmetic double-collapse on one specific `Esc` press, not a functional bug.
 */
@Component({
  selector: 'vision-take-control-modal',
  imports: [PreflightChecklist],
  templateUrl: './take-control-modal.html',
  styleUrl: './take-control-modal.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TakeControlModal {
  readonly assetId = input.required<string>();
  readonly canCommand = input<boolean>(false);
  readonly preflightItems = input<readonly PreflightItem[]>([]);

  /** Emitted on a scrim click, the header "×", Cancel, or `Esc` — the host (`fly-hud.ts`) owns the
   *  actual close (`takeControlOpen.set(false)`), mirroring `cv-setup-modal.ts#closed`'s identical
   *  "component only reports the request" shape. Does **not** release or cancel an in-flight
   *  handshake (see this class's own doc comment) — closing the modal is not a promise to abort. */
  readonly closed = output<void>();

  /** Start-control fired with the tile the operator had selected at that moment — `fly-hud.ts`'s own
   *  `confirmTakeControl(kind)` performs the actual `selectSource(kind)` + `requestEngage()`, see
   *  this class's own doc comment for why the commit lives there rather than here. */
  readonly confirmed = output<RcSourceKind>();

  protected readonly rc = inject(RcInputService);
  protected readonly source = inject(RcSource);
  protected readonly client = inject(ManualControlClient);

  /** The in-progress, not-yet-committed pick — see this class's own doc comment. */
  protected readonly selected = signal<RcSourceKind>(this.source.kind());

  /** Locked the instant a handshake starts, same rule the (now-deleted) at-rest source pills always
   *  followed (`fly-hud-logic.ts#sourceLocked`) — a tile tapped mid-"Engaging…" would silently pick a
   *  source the in-flight `engage()` call already committed past. */
  protected readonly locked = computed(() => sourceLocked(this.client.state()));

  protected readonly engaging = computed(() => this.client.state() === 'engaging');
  protected readonly denied = computed(() => this.client.state() === 'denied');

  private readonly startGate = computed<EngageGateInput>(() => ({
    hasAsset: this.assetId().length > 0,
    canCommand: this.canCommand(),
    sourceKind: this.selected(),
    gamepadConnected: this.rc.connected(),
    engageState: this.client.state(),
  }));

  /** Start-control's own poka-yoke reason/title — reads {@link selected}, so switching tiles updates
   *  this without needing Start control clicked first (never "enabled, then errors on click"). */
  protected readonly startDisabledReason = computed(() => engageDisabledReason(this.startGate()));
  protected readonly startDisabled = computed(() => this.startDisabledReason() !== undefined);

  constructor() {
    const onKeydown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault();
        this.closed.emit();
      }
    };
    document.addEventListener('keydown', onKeydown);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('keydown', onKeydown));
  }

  protected choose(kind: RcSourceKind): void {
    if (this.locked()) {
      return;
    }
    this.selected.set(kind);
  }

  protected confirm(): void {
    if (this.startDisabled()) {
      return;
    }
    this.confirmed.emit(this.selected());
  }
}
