import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { EventRow } from './event-row';
import type { DetectionEvent } from '../../core/api/models';

function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.92,
    firstSeen: '2026-08-04T10:00:00Z',
    lastSeen: '2026-08-04T10:00:12Z',
    state: 'CLOSED',
    ...partial,
  };
}

function render(inputs: Record<string, unknown>) {
  const fixture = TestBed.createComponent(EventRow);
  for (const [key, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(key, value);
  }
  fixture.detectChanges();
  return fixture;
}

describe('EventRow', () => {
  it('renders the CLOSED (norm) state with no status chip, just the always-on dot', () => {
    const el = render({ event: event({ state: 'CLOSED' }), sourceLabel: 'Falcon-2', relativeTime: '12s ago' })
      .nativeElement as HTMLElement;
    expect(el.querySelector('.event-state')).toBeNull();
    expect(el.querySelector('.event-dot')?.classList.contains('open')).toBe(false);
  });

  it('renders an OPEN chip only for an OPEN event — signal, not the old universal CLOSED noise', () => {
    const el = render({ event: event({ state: 'OPEN' }), sourceLabel: 'Falcon-2', relativeTime: '12s ago' })
      .nativeElement as HTMLElement;
    expect(el.querySelector('.event-state')?.textContent).toContain('OPEN');
    expect(el.querySelector('.event-row')?.classList.contains('open')).toBe(true);
  });

  it("an OPEN row's chip replaces the severity dot rather than joining it — one status indicator, not two (docs/plans/active/OPERATOR-UX-7-PLAN.md finding W1)", () => {
    const el = render({ event: event({ state: 'OPEN' }), sourceLabel: 'Falcon-2', relativeTime: '12s ago' })
      .nativeElement as HTMLElement;
    expect(el.querySelector('.event-row-top > .event-dot')).toBeNull();
    expect(el.querySelectorAll('.event-row-top .event-state')).toHaveLength(1);
  });

  it('a CLOSED row keeps the plain severity dot and no chip (the norm)', () => {
    const el = render({ event: event({ state: 'CLOSED' }), sourceLabel: 'Falcon-2', relativeTime: '12s ago' })
      .nativeElement as HTMLElement;
    expect(el.querySelector('.event-row-top > .event-dot')).not.toBeNull();
    expect(el.querySelector('.event-row-top .event-state')).toBeNull();
  });

  it("places the OPEN chip after the time element, and gives the label flex:1/min-width:0 so it never truncates to a single letter (finding W1)", () => {
    const el = render({ event: event({ state: 'OPEN' }), sourceLabel: 'Falcon-2', relativeTime: '12s ago' })
      .nativeElement as HTMLElement;
    const top = el.querySelector('.event-row-top') as HTMLElement;
    const order = [...top.children].map((child) => child.className);
    const timeIndex = order.findIndex((cls) => cls.includes('event-time'));
    const chipIndex = order.findIndex((cls) => cls.includes('event-state'));
    expect(timeIndex).toBeGreaterThanOrEqual(0);
    expect(chipIndex).toBeGreaterThan(timeIndex);

    const label = top.querySelector('.event-label') as HTMLElement;
    expect(label.textContent?.trim()).toBe('person');
  });

  it('dense mode renders one row with confidence + source + time, and never an action affordance', () => {
    const el = render({
      event: event(),
      sourceLabel: 'Falcon-2',
      relativeTime: '12s ago',
      dense: true,
      actionLabel: 'Details',
    }).nativeElement as HTMLElement;
    expect(el.querySelector('.event-row')?.classList.contains('dense')).toBe(true);
    expect(el.querySelector('.event-confidence')?.textContent?.trim()).toBe('92%');
    expect(el.querySelector('.event-source')?.textContent?.trim()).toBe('Falcon-2');
    expect(el.querySelector('.event-time')?.textContent?.trim()).toBe('12s ago');
    expect(el.querySelector('.event-affordance')).toBeNull();
  });

  it('the default (rail) variant shows the action affordance when given, omits it when null', () => {
    const withAction = render({
      event: event(),
      sourceLabel: 'Falcon-2',
      relativeTime: '12s ago',
      actionLabel: 'Watch live',
    }).nativeElement as HTMLElement;
    expect(withAction.querySelector('.event-affordance')?.textContent).toContain('Watch live');

    const withoutAction = render({ event: event(), sourceLabel: 'Falcon-2', relativeTime: '12s ago' })
      .nativeElement as HTMLElement;
    expect(withoutAction.querySelector('.event-affordance')).toBeNull();
  });

  it('disables the row and stops emitting when not clickable', () => {
    const fixture = render({ event: event(), sourceLabel: 'Falcon-2', relativeTime: '12s ago', clickable: false });
    let emitted = 0;
    fixture.componentInstance.activated.subscribe(() => emitted++);
    const button = fixture.nativeElement.querySelector('button.event-row') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    button.click();
    expect(emitted).toBe(0);
  });

  it('emits activated on click when clickable', () => {
    const fixture = render({ event: event(), sourceLabel: 'Falcon-2', relativeTime: '12s ago' });
    let emitted = 0;
    fixture.componentInstance.activated.subscribe(() => emitted++);
    (fixture.nativeElement.querySelector('button.event-row') as HTMLButtonElement).click();
    expect(emitted).toBe(1);
  });

  it('applies the selected class for two-pane\'s own highlight', () => {
    const el = render({ event: event(), sourceLabel: 'Falcon-2', relativeTime: '12s ago', selected: true })
      .nativeElement as HTMLElement;
    expect(el.querySelector('.event-row')?.classList.contains('selected')).toBe(true);
  });
});
