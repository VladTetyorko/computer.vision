import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Notice } from '../notice';
import { SwitchGauge } from '../switch-gauge/switch-gauge';
import type { ActionBinding, ControlCatalog, ManualControlChannelBinding, VehicleKind } from '../../../core/api/models';
import {
  KEY_STEP,
  REST_VALUE,
  displayPercent,
  fractionAlong,
  knobLeftPercent,
  knobTopPercent,
  nudge,
  profileCaveat,
  springsBack,
  valueFromFraction,
  type ControlPad,
} from '../../../core/rc/control-surface-logic';
import {
  actionSwitchRows,
  displayPads,
  normalizeChannelMap,
  padRestX,
  padRestY,
  unmappedControls,
  type ChannelMapLike,
} from '../../../core/rc/transmitter-view-logic';

/**
 * `vision-transmitter-view` — draws a bound controller layout **as a transmitter**
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.1): stick pads with a live knob and a rest mark,
 * one gauge row per bound switch/button, and a faint line for whatever the device reports that
 * neither map claims. Driven entirely by inputs, so the same picture serves three different jobs
 * without knowing which one it's in:
 *
 * - **A read-only mirror** of a plugged-in transmitter, before a session is ever engaged
 *   (`interactive="false"`, `axes`/`buttons` fed from `RcInputService`).
 * - **The interactive on-screen surface** an operator without a transmitter drags
 *   (`interactive="true"`) — absorbs `features/fly/virtual-control-surface.ts`'s pointer/keyboard
 *   math verbatim, but emits {@link valuesChange} instead of writing to an injected service, so this
 *   component stays a plain controlled input: the caller owns wherever the values actually live
 *   (`VirtualRcInputService` in the cockpit today, something else wherever this is reused next).
 * - **The setup wizard's live review step**, fed by whatever the wizard is currently detecting.
 *
 * <h2>Two wire shapes, one picture</h2>
 * `channelMap` accepts either the engaged frame's `ManualControlChannelBinding[]` or a profile's
 * own `ControlBinding[]` (`core/rc/transmitter-view-logic.ts#normalizeChannelMap` adapts the
 * latter, filling `travel`/`label` from `catalog` only in the defensive case a read omitted them).
 * `catalog` is otherwise how this component learns a switch kind's cell count and which actions are
 * dangerous (decision C8) — never hardcoded.
 *
 * <h2>The rest mark says what "letting go" means</h2>
 * A centred control's rest mark is a thin crosshair line at centre; a unidirectional control's is a
 * heavier line at the pad's low edge — because that control does not spring back to a centre, it
 * holds at idle (`control-surface-logic.ts#springsBack`). Both come from the same
 * {@link knobLeftPercent}/{@link knobTopPercent} math the knob itself uses, evaluated at
 * `REST_VALUE`, so the mark and a truly-at-rest knob always coincide exactly.
 */
@Component({
  selector: 'vision-transmitter-view',
  imports: [Notice, SwitchGauge, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './transmitter-view.html',
  styleUrl: './transmitter-view.css',
})
export class TransmitterView {
  /** The engaged frame's `ManualControlChannelBinding[]` or a profile's own `ControlBinding[]`. */
  readonly channelMap = input.required<ChannelMapLike>();
  readonly actionMap = input<readonly ActionBinding[]>([]);
  /** Drives the "generic centred layout" caveat when the vehicle didn't report what it is. */
  readonly vehicleKind = input.required<VehicleKind>();
  /** `undefined` while `GET /api/control-profiles/catalog` hasn't resolved yet — degrades to the
   * fixed structural fallback in `transmitter-view-logic.ts#positionsForKind`, never a blocked page. */
  readonly catalog = input<ControlCatalog | undefined>(undefined);
  /** Raw Gamepad-shaped readings — `RcInputService.axes()`/a virtual source/the wizard's detector. */
  readonly axes = input<readonly number[]>([]);
  readonly buttons = input<readonly number[]>([]);
  /** `false` (default): a read-only mirror. `true`: pads are draggable and emit {@link valuesChange}. */
  readonly interactive = input<boolean>(false);
  /** `ControlActionDispatcher.holding()`'s free-text hold-to-fire notice, or `undefined`. */
  readonly holding = input<string | undefined>(undefined);
  /** Where the "Set up ›" link on the unmapped line goes. */
  readonly setupLink = input<string>('/manage/controller');

  /** Emitted while dragging/nudging a pad in interactive mode — the caller applies the value. */
  readonly valuesChange = output<{ axisIndex: number; value: number }>();

  protected readonly normalizedChannelMap = computed(() => normalizeChannelMap(this.channelMap(), this.catalog()));
  protected readonly pads = computed(() => displayPads(this.channelMap(), this.catalog()));
  protected readonly rows = computed(() =>
    actionSwitchRows(this.actionMap(), this.axes(), this.buttons(), this.catalog(), this.holding(), this.vehicleKind()),
  );
  protected readonly unmapped = computed(() =>
    unmappedControls(this.normalizedChannelMap(), this.actionMap(), this.axes().length, this.buttons().length),
  );
  protected readonly unmappedLabel = computed(() => this.unmapped().map((u) => u.label).join(', '));
  protected readonly caveat = computed(() => profileCaveat(this.vehicleKind()));

  protected readonly knobLeftPercent = knobLeftPercent;
  protected readonly knobTopPercent = knobTopPercent;
  protected readonly restX = padRestX;
  protected readonly restY = padRestY;

  /** A pad axis may be unbound (a single-control pad); an absent binding reads as rest. */
  protected value(binding: ManualControlChannelBinding | undefined): number {
    if (!binding) {
      return REST_VALUE;
    }
    const raw = this.axes()[binding.sourceIndex];
    return typeof raw === 'number' && Number.isFinite(raw) ? raw : REST_VALUE;
  }

  /** One control's 0-100 readout — the operator's own units (a car reads 50 at stop). */
  protected percent(binding: ManualControlChannelBinding): number {
    return displayPercent(binding, this.value(binding));
  }

  protected onPointerDown(event: PointerEvent, pad: ControlPad): void {
    if (!this.interactive()) {
      return;
    }
    const el = event.currentTarget as HTMLElement;
    el.setPointerCapture(event.pointerId);
    el.focus();
    this.applyPointer(event, pad, el);
  }

  protected onPointerMove(event: PointerEvent, pad: ControlPad): void {
    if (!this.interactive()) {
      return;
    }
    const el = event.currentTarget as HTMLElement;
    if (el.hasPointerCapture(event.pointerId)) {
      this.applyPointer(event, pad, el);
    }
  }

  protected onPointerUp(event: PointerEvent, pad: ControlPad): void {
    if (!this.interactive()) {
      return;
    }
    const el = event.currentTarget as HTMLElement;
    if (el.hasPointerCapture(event.pointerId)) {
      el.releasePointerCapture(event.pointerId);
    }
    this.releasePad(pad);
  }

  /** Arrow keys nudge — the only way to set a hover throttle with any precision. */
  protected onKeyDown(event: KeyboardEvent, pad: ControlPad): void {
    if (!this.interactive()) {
      return;
    }
    const step = this.stepFor(event.key);
    if (step === undefined) {
      return;
    }
    const binding = step.axis === 'x' ? pad.x : pad.y;
    if (!binding) {
      return;
    }
    event.preventDefault();
    this.emitValue(binding, nudge(binding, this.value(binding), step.delta));
  }

  private stepFor(key: string): { axis: 'x' | 'y'; delta: number } | undefined {
    switch (key) {
      case 'ArrowLeft':
        return { axis: 'x', delta: -KEY_STEP };
      case 'ArrowRight':
        return { axis: 'x', delta: KEY_STEP };
      case 'ArrowDown':
        return { axis: 'y', delta: -KEY_STEP };
      case 'ArrowUp':
        return { axis: 'y', delta: KEY_STEP };
      default:
        return undefined;
    }
  }

  private applyPointer(event: PointerEvent, pad: ControlPad, el: HTMLElement): void {
    event.preventDefault();
    const rect = el.getBoundingClientRect();
    if (pad.x) {
      this.emitValue(pad.x, valueFromFraction(pad.x, fractionAlong(event.clientX, rect.left, rect.width)));
    }
    if (pad.y) {
      this.emitValue(pad.y, valueFromFraction(pad.y, 1 - fractionAlong(event.clientY, rect.top, rect.height)));
    }
  }

  /** A centred control springs back to rest on release; a unidirectional one holds where it was left. */
  private releasePad(pad: ControlPad): void {
    if (pad.x && springsBack(pad.x)) {
      this.emitValue(pad.x, REST_VALUE);
    }
    if (pad.y && springsBack(pad.y)) {
      this.emitValue(pad.y, REST_VALUE);
    }
  }

  private emitValue(binding: ManualControlChannelBinding, value: number): void {
    this.valuesChange.emit({ axisIndex: binding.sourceIndex, value });
  }
}
