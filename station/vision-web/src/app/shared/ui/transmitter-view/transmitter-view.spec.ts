import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { TransmitterView, type ActionKeyRow } from './transmitter-view';
import { KEY_STEP } from '../../../core/rc/control-surface-logic';
import type {
  ActionBinding,
  ControlCatalog,
  ManualControlChannelBinding,
  VehicleKind,
} from '../../../core/api/models';

const CATALOG: ControlCatalog = {
  vehicleKinds: [
    { name: 'ROVER', label: 'Rover' },
    { name: 'COPTER', label: 'Multirotor' },
  ],
  inputKinds: [
    { name: 'AXIS', label: 'Axis', sources: ['AXIS'], positions: [] },
    { name: 'BUTTON', label: 'Button', sources: ['BUTTON'], positions: ['HIGH'] },
    { name: 'SWITCH_2', label: '2-position switch', sources: ['AXIS', 'BUTTON'], positions: ['LOW', 'HIGH'] },
    { name: 'SWITCH_3', label: '3-position switch', sources: ['AXIS'], positions: ['LOW', 'MIDDLE', 'HIGH'] },
  ],
  positions: [
    { name: 'LOW', label: 'Low', level: 0 },
    { name: 'MIDDLE', label: 'Middle', level: 1 },
    { name: 'HIGH', label: 'High', level: 2 },
  ],
  functions: [
    { name: 'STEERING', label: 'Steering' },
    { name: 'THROTTLE', label: 'Throttle' },
    { name: 'YAW', label: 'Yaw' },
    { name: 'ROLL', label: 'Roll' },
    { name: 'PITCH', label: 'Pitch' },
  ],
  actions: [
    { name: 'ARM', label: 'Arm', parameter: 'NONE', dangerous: true },
    { name: 'DISARM', label: 'Disarm', parameter: 'NONE', dangerous: true },
  ],
  auxFunctions: [],
};

const axisBinding = (
  fn: ManualControlChannelBinding['function'],
  travel: ManualControlChannelBinding['travel'],
  sourceIndex: number,
  label: string,
): ManualControlChannelBinding => ({
  source: 'AXIS',
  kind: 'AXIS',
  function: fn,
  travel,
  sourceIndex,
  rcChannel: sourceIndex + 1,
  minMicros: 1000,
  centerMicros: travel === 'CENTERED' ? 1500 : 1000,
  maxMicros: 2000,
  label,
});

const STEERING = axisBinding('STEERING', 'CENTERED', 0, 'Steering');
const ROVER_THROTTLE = axisBinding('THROTTLE', 'CENTERED', 1, 'Throttle');
const YAW = axisBinding('YAW', 'CENTERED', 3, 'Yaw');
const COPTER_THROTTLE = axisBinding('THROTTLE', 'UNIDIRECTIONAL', 2, 'Throttle');
const ROLL = axisBinding('ROLL', 'CENTERED', 0, 'Roll');
const PITCH = axisBinding('PITCH', 'CENTERED', 1, 'Pitch');

const ARM_SWITCH: ActionBinding = {
  source: 'AXIS',
  kind: 'SWITCH_3',
  sourceIndex: 4,
  positions: [
    { position: 'LOW', action: 'DISARM' },
    { position: 'HIGH', action: 'ARM' },
  ],
};

interface RenderProps {
  readonly channelMap: readonly ManualControlChannelBinding[];
  readonly actionMap?: readonly ActionBinding[];
  readonly vehicleKind?: VehicleKind;
  readonly axes?: readonly number[];
  readonly buttons?: readonly number[];
  readonly interactive?: boolean;
  readonly holding?: string;
  readonly keyRows?: readonly ActionKeyRow[];
}

function render(props: RenderProps) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(TransmitterView);
  fixture.componentRef.setInput('channelMap', props.channelMap);
  fixture.componentRef.setInput('actionMap', props.actionMap ?? []);
  fixture.componentRef.setInput('vehicleKind', props.vehicleKind ?? 'ROVER');
  fixture.componentRef.setInput('catalog', CATALOG);
  fixture.componentRef.setInput('axes', props.axes ?? []);
  fixture.componentRef.setInput('buttons', props.buttons ?? []);
  fixture.componentRef.setInput('interactive', props.interactive ?? false);
  fixture.componentRef.setInput('holding', props.holding);
  fixture.componentRef.setInput('keyRows', props.keyRows ?? []);
  fixture.detectChanges();
  return fixture;
}

/** A key row builder — `id: 'MODE_1'` (with `dangerous: false`) is the default, overridable per
 * test (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3, wave W2). */
const keyRow = (overrides: Partial<ActionKeyRow> = {}): ActionKeyRow => ({
  id: 'MODE_1',
  keyLabel: '1',
  text: 'Guided',
  dangerous: false,
  pressed: false,
  holding: false,
  ...overrides,
});

describe('TransmitterView', () => {
  it('draws a rover as one steer/drive pad', () => {
    const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE] });
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(1);
  });

  it('draws a copter as two pads: yaw/throttle and roll/pitch', () => {
    const fixture = render({ channelMap: [YAW, COPTER_THROTTLE, ROLL, PITCH], vehicleKind: 'COPTER' });
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(2);
  });

  it('accepts a profile ControlBinding[] channelMap the same way, via the adapter', () => {
    const rawSteering = { ...STEERING, deadband: 0.05, reversed: false };
    const rawThrottle = { ...ROVER_THROTTLE, deadband: 0.05, reversed: false };
    const fixture = render({ channelMap: [rawSteering, rawThrottle] });
    expect(fixture.nativeElement.querySelectorAll('.tv-pad').length).toBe(1);
  });

  it('draws a thin centred rest mark at 50% and a heavier unidirectional one at the pad edge', () => {
    const fixture = render({ channelMap: [YAW, COPTER_THROTTLE], vehicleKind: 'COPTER' });
    const pad = fixture.nativeElement.querySelector('.tv-pad') as HTMLElement;

    const restH = pad.querySelector('.tv-rest-h') as HTMLElement; // throttle: unidirectional
    expect(restH.classList.contains('heavy')).toBe(true);
    expect(restH.style.top).toBe('100%');

    const restV = pad.querySelector('.tv-rest-v') as HTMLElement; // yaw: centred
    expect(restV.classList.contains('heavy')).toBe(false);
    expect(restV.style.left).toBe('50%');
  });

  it('renders one switch-gauge row per action binding, labelled by controlLabel', () => {
    const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE], actionMap: [ARM_SWITCH], axes: [0, 0, 0, 0, 1] });
    const rows = fixture.nativeElement.querySelectorAll('.tv-row');
    expect(rows.length).toBe(1);
    expect(rows[0].querySelector('.tv-row-id')?.textContent?.trim()).toBe('Axis 5');
    expect(rows[0].querySelector('vision-switch-gauge')).not.toBeNull();
  });

  it('renders the action list "Disarm · — · Arm", lit position in info colour, dangerous ones in danger-text', () => {
    const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE], actionMap: [ARM_SWITCH], axes: [0, 0, 0, 0, 1] });
    const texts = fixture.nativeElement.querySelectorAll('.tv-cell-text');
    expect(Array.from(texts).map((t) => (t as HTMLElement).textContent?.trim())).toEqual(['Disarm', '—', 'Arm']);

    expect(texts[0].classList.contains('dangerous')).toBe(true); // Disarm: dangerous, not lit
    expect(texts[0].classList.contains('lit')).toBe(false);
    expect(texts[2].classList.contains('lit')).toBe(true); // Arm: the switch is HIGH right now
  });

  it('shows the hold-to-fire fill only on the row the dispatcher named', () => {
    const named = render({
      channelMap: [STEERING, ROVER_THROTTLE],
      actionMap: [ARM_SWITCH],
      axes: [0, 0, 0, 0, 1],
      holding: 'Hold Axis 5 to arm',
    });
    expect(named.nativeElement.querySelector('.sg-fill')).not.toBeNull();

    const other = render({
      channelMap: [STEERING, ROVER_THROTTLE],
      actionMap: [ARM_SWITCH],
      axes: [0, 0, 0, 0, 1],
      holding: 'Hold Sw 3 to disarm',
    });
    expect(other.nativeElement.querySelector('.sg-fill')).toBeNull();
  });

  it('renders a faint unmapped line naming every unclaimed input, with a link to setupLink', () => {
    const fixture = render({ channelMap: [STEERING], axes: [0, 0, 0], buttons: [0] });
    const unmapped = fixture.nativeElement.querySelector('.tv-unmapped');
    expect(unmapped).not.toBeNull();
    expect(unmapped.textContent).toContain('Axis 2');
    expect(unmapped.textContent).toContain('Axis 3');
    expect(unmapped.textContent).toContain('Sw 1');
    expect(unmapped.querySelector('a')?.textContent?.trim()).toBe('Set up ›');
  });

  it('hides the unmapped line when there is no device (no axes, no buttons)', () => {
    const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE] });
    expect(fixture.nativeElement.querySelector('.tv-unmapped')).toBeNull();
  });

  it('hides the unmapped line once every input is claimed', () => {
    const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE], axes: [0, 0] });
    expect(fixture.nativeElement.querySelector('.tv-unmapped')).toBeNull();
  });

  describe('interactive mode', () => {
    it('is read-only by default: the pad is not tabbable and arrow keys emit nothing', () => {
      const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE] });
      const emitted: { axisIndex: number; value: number }[] = [];
      fixture.componentInstance.valuesChange.subscribe((e) => emitted.push(e));
      const pad = fixture.nativeElement.querySelector('.tv-pad') as HTMLElement;

      expect(pad.getAttribute('tabindex')).toBeNull();
      pad.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));
      expect(emitted.length).toBe(0);
    });

    it('nudges the focused pad with arrow keys and emits valuesChange when interactive', () => {
      const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE], interactive: true });
      const emitted: { axisIndex: number; value: number }[] = [];
      fixture.componentInstance.valuesChange.subscribe((e) => emitted.push(e));
      const pad = fixture.nativeElement.querySelector('.tv-pad') as HTMLElement;

      expect(pad.getAttribute('tabindex')).toBe('0');
      pad.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));

      expect(emitted.length).toBe(1);
      expect(emitted[0].axisIndex).toBe(ROVER_THROTTLE.sourceIndex);
      expect(emitted[0].value).toBeCloseTo(KEY_STEP * 2, 5); // centred: span 2
    });

    it('does not nudge an axis the focused pad has no binding for', () => {
      const fixture = render({ channelMap: [ROLL], vehicleKind: 'COPTER', interactive: true }); // x-only pad
      const emitted: { axisIndex: number; value: number }[] = [];
      fixture.componentInstance.valuesChange.subscribe((e) => emitted.push(e));
      const pad = fixture.nativeElement.querySelector('.tv-pad') as HTMLElement;

      pad.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));
      expect(emitted.length).toBe(0);
    });
  });

  it('shows the generic-layout caveat only for an UNKNOWN vehicle kind', () => {
    const known = render({ channelMap: [STEERING, ROVER_THROTTLE], vehicleKind: 'ROVER' });
    expect(known.nativeElement.querySelector('vision-notice')).toBeNull();

    const unknown = render({ channelMap: [STEERING, ROVER_THROTTLE], vehicleKind: 'UNKNOWN' });
    expect(unknown.nativeElement.querySelector('vision-notice')).not.toBeNull();
  });

  describe('keyRows (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3, wave W2 — keyboard action-key legend)', () => {
    it('renders nothing extra when keyRows is empty (the default)', () => {
      const fixture = render({ channelMap: [STEERING, ROVER_THROTTLE] });
      expect(fixture.nativeElement.querySelector('.tv-keys')).toBeNull();
    });

    it('renders one row per key row, labelled by keyLabel, gauge plus its text', () => {
      const fixture = render({
        channelMap: [STEERING, ROVER_THROTTLE],
        keyRows: [
          keyRow({ id: 'EMERGENCY_STOP', keyLabel: 'Space', text: 'Emergency stop', dangerous: false }),
          keyRow({ id: 'TOGGLE_ARM', keyLabel: 'Shift+Enter', text: 'Arm', dangerous: true }),
        ],
      });
      const rows = fixture.nativeElement.querySelectorAll('.tv-keys .tv-row');
      expect(rows.length).toBe(2);
      expect(rows[0].querySelector('.tv-row-id')?.textContent?.trim()).toBe('Space');
      expect(rows[0].querySelector('.tv-cell-text')?.textContent?.trim()).toBe('Emergency stop');
      expect(rows[0].querySelector('vision-switch-gauge')).not.toBeNull();
      expect(rows[1].querySelector('.tv-row-id')?.textContent?.trim()).toBe('Shift+Enter');
      expect(rows[1].querySelector('.tv-cell-text')?.textContent?.trim()).toBe('Arm');
    });

    it('lights the cell text when the chord is pressed, and marks an unpressed dangerous one instead', () => {
      const pressed = render({
        channelMap: [STEERING, ROVER_THROTTLE],
        keyRows: [keyRow({ pressed: true, dangerous: true })],
      });
      const pressedText = pressed.nativeElement.querySelector('.tv-cell-text') as HTMLElement;
      expect(pressedText.classList.contains('lit')).toBe(true);
      expect(pressedText.classList.contains('dangerous')).toBe(false); // lit takes priority

      const idle = render({
        channelMap: [STEERING, ROVER_THROTTLE],
        keyRows: [keyRow({ pressed: false, dangerous: true })],
      });
      const idleText = idle.nativeElement.querySelector('.tv-cell-text') as HTMLElement;
      expect(idleText.classList.contains('lit')).toBe(false);
      expect(idleText.classList.contains('dangerous')).toBe(true);
    });

    it('shows the hold-to-fire fill only on a row whose own holding flag is true', () => {
      const fixture = render({
        channelMap: [STEERING, ROVER_THROTTLE],
        keyRows: [
          // A hold in progress means the chord is still physically down — same as a switch mid-hold
          // still sitting in the position that started it (`ControlActionDispatcher`'s own
          // `holdContinues` cancels the hold the instant the key comes back up).
          keyRow({ id: 'TOGGLE_ARM', keyLabel: 'Shift+Enter', dangerous: true, pressed: true, holding: true }),
          keyRow({ id: 'MODE_1', keyLabel: '1', dangerous: false, pressed: false, holding: false }),
        ],
      });
      const rows = fixture.nativeElement.querySelectorAll('.tv-keys .tv-row');
      expect(rows[0].querySelector('.sg-fill')).not.toBeNull();
      expect(rows[1].querySelector('.sg-fill')).toBeNull();
    });

    it('a layout with no bound controls but live keyRows does not show the "Nothing is bound" empty state', () => {
      const fixture = render({ channelMap: [], keyRows: [keyRow()] });
      expect(fixture.nativeElement.querySelector('.tv-empty')).toBeNull();
    });
  });
});
