import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ControllerDiagram, type DiagramControl, type DiagramPick } from './controller-diagram';

/** A transmitter reporting AETR channel order, the way EdgeTX actually reports one. */
const AETR: readonly DiagramControl[] = [
  { source: 'AXIS', sourceIndex: 0, caption: 'CH1 · Roll', function: 'ROLL' },
  { source: 'AXIS', sourceIndex: 1, caption: 'CH2 · Pitch', function: 'PITCH' },
  { source: 'AXIS', sourceIndex: 2, caption: 'CH3 · Throttle', function: 'THROTTLE' },
  { source: 'AXIS', sourceIndex: 3, caption: 'CH4 · Yaw', function: 'YAW' },
];

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(ControllerDiagram);
  fixture.componentRef.setInput('axes', [0, 0, -1, 0]);
  fixture.componentRef.setInput('buttons', [0, 0]);
  fixture.componentRef.setInput('controls', AETR);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

function padDot(host: HTMLElement, index: number): HTMLElement {
  return host.querySelectorAll('.pad-dot')[index] as HTMLElement;
}

describe('ControllerDiagram', () => {
  it('names both sticks and captions each direction with the axis behind it', () => {
    const { host } = render();
    const labels = [...host.querySelectorAll('.stick-label')].map((e) => e.textContent?.trim());
    const captions = [...host.querySelectorAll('.slot .tag-binding')].map((e) => e.textContent?.trim());

    expect(labels).toEqual(['Left stick', 'Right stick']);
    expect(captions[0]).toBe('Axis 3 · CH3 · Throttle');
    expect(captions[1]).toBe('Axis 4 · CH4 · Yaw');
  });

  it('draws throttle at the bottom when the stick is down, the transmitter convention', () => {
    const { host } = render({ axes: [0, 0, -1, 0] });

    expect(padDot(host, 0).style.top).toBe('100%');
  });

  it('draws the same reading at the top for a gamepad, when told so', () => {
    const { host } = render({ axes: [0, 0, -1, 0], positiveIsUp: false });

    expect(padDot(host, 0).style.top).toBe('0%');
  });

  it('follows the operator to mode 1 — throttle moves to the right pad', () => {
    const { host } = render({ axes: [0, 0, -1, 0], stickMode: 1 });

    expect(padDot(host, 0).style.top).toBe('50%'); // pitch, centred
    expect(padDot(host, 1).style.top).toBe('100%'); // throttle, down
  });

  it('shows a live value for every axis', () => {
    const { host } = render({ axes: [0.5, 0, -1, 0] });
    const values = [...host.querySelectorAll('.tag-value')].map((e) => e.textContent?.trim());

    expect(values).toContain('50%');
    expect(values).toContain('-100%');
  });

  it('draws unbound axes as bars, with nothing said about what they do', () => {
    const { host } = render({ axes: [0, 0, 0, 0, 0.5, -1] });

    // A transmitter reports far more controls than a layout binds; a caption on each of them was
    // the same word repeated down the page, so an unmapped bar carries only its name and value.
    expect(host.querySelectorAll('.bar').length).toBe(2);
    expect(host.querySelector('.bar.mapped')).toBeNull();
    expect(host.querySelector('.bar .tag-binding')).toBeNull();
  });

  it('says a stick direction is empty rather than leaving it blank', () => {
    const { host } = render({ controls: [AETR[2]] });

    expect(host.querySelector('.slot.empty')?.textContent).toContain('nothing on this stick');
  });

  it('marks a pressed button live', () => {
    const { host } = render({ buttons: [0, 1] });
    const pills = host.querySelectorAll('.btn-pill');

    expect(pills[0].classList.contains('on')).toBe(false);
    expect(pills[1].classList.contains('on')).toBe(true);
  });

  it('calls out the control autodetect just named', () => {
    const { host } = render({ highlight: 'BUTTON:0' });

    expect(host.querySelector('.btn-pill')?.classList.contains('detected')).toBe(true);
  });

  it('emits the axis behind the stick direction that was pointed at', () => {
    const { fixture, host } = render({ interactive: true });
    const picks: DiagramPick[] = [];
    fixture.componentInstance.picked.subscribe((p) => picks.push(p));

    (host.querySelector('.slot') as HTMLButtonElement).click();

    expect(picks).toEqual([{ source: 'AXIS', sourceIndex: 2 }]);
  });

  it('points at nothing when the layout may not be edited', () => {
    const { host } = render({ interactive: false });

    expect((host.querySelector('.slot') as HTMLButtonElement).disabled).toBe(true);
    expect((host.querySelector('.btn-pill') as HTMLButtonElement).disabled).toBe(true);
  });
});
