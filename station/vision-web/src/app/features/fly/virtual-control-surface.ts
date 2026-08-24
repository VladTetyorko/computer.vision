import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Notice } from '../../shared/ui/notice';
import { VirtualRcInputService } from '../../core/rc/virtual-rc-input.service';
import {
  KEY_STEP,
  type ControlPad,
  displayPercent,
  fractionAlong,
  knobLeftPercent,
  knobTopPercent,
  nudge,
  padsFrom,
  profileCaveat,
  valueFromFraction,
} from '../../core/rc/control-surface-logic';
import type { ManualControlChannelBinding, VehicleKind } from '../../core/api/models';

/**
 * `vision-virtual-control-surface` — on-screen sticks for an engaged manual-control session
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10-P12). Until this existed the only
 * way to move a control from this app was a USB gamepad, which put an ESP32 rover — or any vehicle
 * an operator drives from a laptop with nothing plugged in — out of reach entirely.
 *
 * <h2>The layout is the server's, not this component's</h2>
 * Every pad, label, rest position and spring-back rule is derived from the `engaged.channelMap`
 * passed in. Nothing here knows what a multirotor is: a rover arrives as one steer/drive pad whose
 * throttle centres at stop, an aircraft as two pads whose throttle holds where it is left, and both
 * fall out of the same code. Hardcoding a two-stick layout would just be the airframe assumption
 * this whole change removed, moved one layer up.
 *
 * <h2>Pointer capture, so a finger that slides off does not strand a control</h2>
 * Each pad captures its pointer on `pointerdown`, so the drag keeps tracking past the pad's own
 * edges and — the part that matters — the release still lands on the pad that owns the control even
 * if the pointer is elsewhere by then. Without capture, dragging a throttle off the pad and letting
 * go would leave a centred control pinned wherever it was abandoned.
 */
@Component({
  selector: 'vision-virtual-control-surface',
  imports: [Notice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './virtual-control-surface.html',
  styleUrl: './virtual-control-surface.css',
})
export class VirtualControlSurface {
  private readonly virtual = inject(VirtualRcInputService);

  /** The engaged channel map, straight from the server's `engaged` frame. */
  readonly channelMap = input.required<readonly ManualControlChannelBinding[]>();
  /** What the vehicle reported itself to be — drives the caveat, nothing else. */
  readonly vehicleKind = input.required<VehicleKind>();
  /** e.g. `"Ground vehicle"` — the server's own name for the profile in use. */
  readonly profileName = input.required<string>();

  protected readonly pads = computed(() => padsFrom(this.channelMap()));
  protected readonly caveat = computed(() => profileCaveat(this.vehicleKind()));

  protected readonly knobLeftPercent = knobLeftPercent;
  protected readonly knobTopPercent = knobTopPercent;

  /** One control's 0-100 readout — the operator's own units (a car reads 50 at stop). */
  protected percent(binding: ManualControlChannelBinding): number {
    return displayPercent(binding, this.virtual.value(binding.sourceIndex));
  }

  /** A pad axis may be unbound (a single-control pad); an absent binding reads as rest. */
  protected value(binding: ManualControlChannelBinding | undefined): number {
    return binding ? this.virtual.value(binding.sourceIndex) : 0;
  }

  protected onPointerDown(event: PointerEvent, pad: ControlPad): void {
    const el = event.currentTarget as HTMLElement;
    el.setPointerCapture(event.pointerId);
    el.focus();
    this.applyPointer(event, pad, el);
  }

  protected onPointerMove(event: PointerEvent, pad: ControlPad): void {
    const el = event.currentTarget as HTMLElement;
    if (el.hasPointerCapture(event.pointerId)) {
      this.applyPointer(event, pad, el);
    }
  }

  protected onPointerUp(event: PointerEvent, pad: ControlPad): void {
    const el = event.currentTarget as HTMLElement;
    if (el.hasPointerCapture(event.pointerId)) {
      el.releasePointerCapture(event.pointerId);
    }
    this.releasePad(pad);
  }

  /** Arrow keys nudge — the only way to set a hover throttle with any precision. */
  protected onKeyDown(event: KeyboardEvent, pad: ControlPad): void {
    const step = this.stepFor(event.key);
    if (step === undefined) {
      return;
    }
    const binding = step.axis === 'x' ? pad.x : pad.y;
    if (!binding) {
      return;
    }
    event.preventDefault();
    this.virtual.set(binding.sourceIndex, nudge(binding, this.virtual.value(binding.sourceIndex), step.delta));
  }

  protected centreAll(): void {
    this.virtual.centreAll();
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
      this.virtual.set(pad.x.sourceIndex, valueFromFraction(pad.x, fractionAlong(event.clientX, rect.left, rect.width)));
    }
    if (pad.y) {
      this.virtual.set(
        pad.y.sourceIndex,
        valueFromFraction(pad.y, 1 - fractionAlong(event.clientY, rect.top, rect.height)),
      );
    }
  }

  private releasePad(pad: ControlPad): void {
    if (pad.x) {
      this.virtual.release(pad.x);
    }
    if (pad.y) {
      this.virtual.release(pad.y);
    }
  }
}
