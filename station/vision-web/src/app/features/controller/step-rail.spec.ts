import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { StepRail } from './step-rail';
import { wizardSteps } from '../../core/rc/controller-wizard-logic';
import { blankControlDraft, type ControlDraft, type ProfileDraft } from '../../core/rc/controller-setup-logic';
import type { ControlProfile } from '../../core/api/models';

const ROVER_BUILTIN: ControlProfile = {
  id: 'builtin-rover',
  source: 'BUILT_IN',
  kind: 'ROVER',
  code: 'S-T-',
  name: 'Ground vehicle',
  active: true,
  stickMode: 2,
  forwardIsUp: true,
  channelMap: [
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'STEERING',
      sourceIndex: 0,
      rcChannel: 1,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'THROTTLE',
      sourceIndex: 2,
      rcChannel: 3,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
  ],
  actionMap: [],
};

const COPTER_BUILTIN: ControlProfile = {
  ...ROVER_BUILTIN,
  id: 'builtin-copter',
  kind: 'COPTER',
  code: 'AETR',
  channelMap: [
    { ...ROVER_BUILTIN.channelMap[0], function: 'ROLL' },
    { ...ROVER_BUILTIN.channelMap[0], function: 'PITCH', sourceIndex: 1, rcChannel: 2 },
    { ...ROVER_BUILTIN.channelMap[1], centerMicros: 1000 },
    { ...ROVER_BUILTIN.channelMap[0], function: 'YAW', sourceIndex: 3, rcChannel: 4 },
  ],
};

function draft(controls: readonly ControlDraft[], kind: ProfileDraft['kind'] = 'ROVER'): ProfileDraft {
  return { id: 'p1', kind, name: 'Bench layout', controls, stickMode: 2, forwardIsUp: true };
}

function render(props: { steps: ReturnType<typeof wizardSteps>; draft: ProfileDraft; currentIndex: number }) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(StepRail);
  fixture.componentRef.setInput('steps', props.steps);
  fixture.componentRef.setInput('draft', props.draft);
  fixture.componentRef.setInput('currentIndex', props.currentIndex);
  fixture.detectChanges();
  return fixture;
}

describe('StepRail', () => {
  it('lists a rover as throttle, steering, then the fixed tail', () => {
    const steps = wizardSteps('ROVER', ROVER_BUILTIN, undefined);
    const fixture = render({ steps, draft: draft([]), currentIndex: 0 });

    const titles = Array.from(fixture.nativeElement.querySelectorAll('.rail-title')).map((el) => (el as HTMLElement).textContent);
    expect(titles).toEqual(['Throttle', 'Steering', 'Arm', 'Mode', 'Extras', 'Review']);
  });

  it('lists a copter as throttle, yaw, pitch, roll, then the fixed tail', () => {
    const steps = wizardSteps('COPTER', COPTER_BUILTIN, undefined);
    const fixture = render({ steps, draft: draft([], 'COPTER'), currentIndex: 0 });

    const titles = Array.from(fixture.nativeElement.querySelectorAll('.rail-title')).map((el) => (el as HTMLElement).textContent);
    expect(titles).toEqual(['Throttle', 'Yaw', 'Pitch', 'Roll', 'Arm', 'Mode', 'Extras', 'Review']);
  });

  it('marks a step done once the draft actually binds it, and current on the open index', () => {
    const steps = wizardSteps('ROVER', ROVER_BUILTIN, undefined);
    const throttle: ControlDraft = {
      ...blankControlDraft('AXIS', 2, draft([])),
      role: 'CHANNEL',
      function: 'THROTTLE',
    };
    const fixture = render({ steps, draft: draft([throttle]), currentIndex: 1 });

    const rows = Array.from(fixture.nativeElement.querySelectorAll('.rail-step')) as HTMLElement[];
    expect(rows[0].classList.contains('done')).toBe(true);
    expect(rows[1].classList.contains('current')).toBe(true);
    expect(rows[1].classList.contains('done')).toBe(false);
    expect(rows[2].classList.contains('done')).toBe(false);
    expect(rows[2].classList.contains('current')).toBe(false);
  });

  it('emits the clicked step index so the page can jump there', () => {
    const steps = wizardSteps('ROVER', ROVER_BUILTIN, undefined);
    const fixture = render({ steps, draft: draft([]), currentIndex: 0 });

    let jumped: number | undefined;
    fixture.componentInstance.jump.subscribe((i) => (jumped = i));

    (fixture.nativeElement.querySelectorAll('.rail-step')[3] as HTMLElement).click();

    expect(jumped).toBe(3);
  });
});
