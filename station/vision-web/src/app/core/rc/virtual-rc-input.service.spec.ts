import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { VirtualRcInputService } from './virtual-rc-input.service';
import type { ManualControlChannelBinding } from '../api/models';

const axis = (
  fn: ManualControlChannelBinding['function'],
  travel: ManualControlChannelBinding['travel'],
  sourceIndex: number,
  rcChannel: number,
  label: string,
): ManualControlChannelBinding => ({
  source: 'AXIS',
  kind: 'AXIS',
  function: fn,
  travel,
  sourceIndex,
  rcChannel,
  minMicros: 1000,
  centerMicros: travel === 'CENTERED' ? 1500 : 1000,
  maxMicros: 2000,
  label,
});

const COPTER_THROTTLE = axis('THROTTLE', 'UNIDIRECTIONAL', 2, 3, 'Throttle');
const ROVER_THROTTLE = axis('THROTTLE', 'CENTERED', 2, 3, 'Throttle');
const STEERING = axis('STEERING', 'CENTERED', 0, 1, 'Steering');

function create(): VirtualRcInputService {
  TestBed.configureTestingModule({ providers: [VirtualRcInputService] });
  return TestBed.inject(VirtualRcInputService);
}

describe('VirtualRcInputService', () => {
  it('produces nothing until a session binds it', () => {
    const service = create();
    expect(service.axes()).toEqual([]);
    expect(service.buttons()).toEqual([]);
  });

  it('starts every control at rest, so a session can never begin with the throttle up', () => {
    const service = create();
    service.bindTo([STEERING, COPTER_THROTTLE]);

    expect(service.axes()).toEqual([0, 0, 0]);
    expect(service.value(COPTER_THROTTLE.sourceIndex)).toBe(0);
  });

  it('places a moved control at the index the server said it reads from', () => {
    const service = create();
    service.bindTo([STEERING, ROVER_THROTTLE]);
    service.set(ROVER_THROTTLE.sourceIndex, 0.6);

    expect(service.axes()).toEqual([0, 0, 0.6]);
  });

  it("springs a centred control back on release — a let-go rover stops, it doesn't coast", () => {
    const service = create();
    service.bindTo([STEERING, ROVER_THROTTLE]);
    service.set(ROVER_THROTTLE.sourceIndex, 0.6);
    service.release(ROVER_THROTTLE);

    expect(service.value(ROVER_THROTTLE.sourceIndex)).toBe(0);
  });

  it('holds a unidirectional throttle on release — springing it to idle would drop the aircraft', () => {
    const service = create();
    service.bindTo([COPTER_THROTTLE]);
    service.set(COPTER_THROTTLE.sourceIndex, 0.6);
    service.release(COPTER_THROTTLE);

    expect(service.value(COPTER_THROTTLE.sourceIndex)).toBe(0.6);
  });

  it('centreAll returns even a held throttle to rest', () => {
    const service = create();
    service.bindTo([COPTER_THROTTLE]);
    service.set(COPTER_THROTTLE.sourceIndex, 0.6);
    service.centreAll();

    expect(service.value(COPTER_THROTTLE.sourceIndex)).toBe(0);
    expect(service.bindings()).toEqual([COPTER_THROTTLE]);
  });

  it('clear() unbinds, so a re-engage cannot inherit the last session position', () => {
    const service = create();
    service.bindTo([COPTER_THROTTLE]);
    service.set(COPTER_THROTTLE.sourceIndex, 0.6);
    service.clear();

    expect(service.bindings()).toEqual([]);
    expect(service.axes()).toEqual([]);
  });
});
