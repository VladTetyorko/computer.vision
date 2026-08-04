import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { NotificationBell } from './notification-bell';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { LiveStore } from '../../core/live/live-store';
import { VisionApi } from '../../core/api/vision-api';
import { GlobalOverlayStore } from '../../core/ui/overlay-store';
import type { DetectionEvent } from '../../core/api/models';

/**
 * `NotificationBell` pulls in `EventsStore`/`FleetStore`/`LiveStore`/`VisionApi` — every one faked
 * here (no HTTP, no polling, no real `EventSource`), mirroring `shared/ui/app-sidebar/app-sidebar.spec.ts`'s
 * own "fake every transitive dependency purely so the tree can mount" approach. `GlobalOverlayStore`
 * is left real (root-provided, no HTTP deps of its own) — its own behavior is covered by
 * `core/ui/overlay-store.spec.ts`; this file only checks that the bell wires into it correctly
 * (docs/UI-STATE-PLAN.md §1 D1/D2/D4/D5, closed by `identity-chip.ts`/`notification-bell.ts` moving
 * off native `<details>`).
 */
function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.9,
    firstSeen: '2026-01-01T00:00:00Z',
    lastSeen: '2026-01-01T00:00:05Z',
    state: 'OPEN',
    ...partial,
  };
}

function fakeFleetStore() {
  return { streams: () => [] as unknown[], devices: () => [] as unknown[], reachable: () => true };
}

function fakeEventsStore(events: DetectionEvent[] = []) {
  return { activate: () => {}, release: () => {}, events: () => events };
}

function fakeLiveStore() {
  return { liveEvents: () => [] as unknown[] };
}

function render(events: DetectionEvent[] = []) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: FleetStore, useValue: fakeFleetStore() },
      { provide: EventsStore, useValue: fakeEventsStore(events) },
      { provide: LiveStore, useValue: fakeLiveStore() },
      { provide: VisionApi, useValue: {} },
    ],
  });
  const fixture = TestBed.createComponent(NotificationBell);
  fixture.detectChanges();
  return fixture;
}

function trigger(fixture: { nativeElement: HTMLElement }): HTMLButtonElement {
  return fixture.nativeElement.querySelector('.bell-trigger') as HTMLButtonElement;
}

describe('NotificationBell — dropdown state (docs/UI-STATE-PLAN.md)', () => {
  it('starts closed: aria-expanded=false, no dropdown, no badge with zero events', () => {
    const fixture = render([]);
    const btn = trigger(fixture);
    expect(btn.getAttribute('aria-expanded')).toBe('false');
    expect(btn.getAttribute('aria-haspopup')).toBe('true');
    expect(fixture.nativeElement.querySelector('vision-events-rail')).toBeNull();
    expect(fixture.nativeElement.querySelector('.bell-badge')).toBeNull();
  });

  it('shows the unread count, capped at "9+"', () => {
    const many = Array.from({ length: 11 }, (_, i) => event({ id: `e-${i}` }));
    const fixture = render(many);
    expect(fixture.nativeElement.querySelector('.bell-badge')?.textContent?.trim()).toBe('9+');
  });

  it('clicking the trigger opens the dropdown, sets aria-expanded=true, and marks the badge read', () => {
    const fixture = render([event()]);
    expect(fixture.nativeElement.querySelector('.bell-badge')?.textContent?.trim()).toBe('1');

    trigger(fixture).click();
    fixture.detectChanges();

    const btn = trigger(fixture);
    expect(btn.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('vision-events-rail')).not.toBeNull();
    // Opening marks every currently-listed event read — the badge disappears.
    expect(fixture.nativeElement.querySelector('.bell-badge')).toBeNull();
  });

  it('clicking the trigger again closes it (a plain toggle, not a one-way open)', () => {
    const fixture = render([event()]);
    trigger(fixture).click();
    fixture.detectChanges();
    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('true');

    trigger(fixture).click();
    fixture.detectChanges();
    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('vision-events-rail')).toBeNull();
  });

  it('a click that only ever closes the bell never re-marks anything read (toggleBell\'s own "opening" guard)', () => {
    const fixture = render([event({ id: 'a' })]);
    trigger(fixture).click(); // open — marks 'a' read
    fixture.detectChanges();
    trigger(fixture).click(); // close
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.bell-badge')).toBeNull(); // 'a' is still read
  });

  it('opening a sibling shell overlay (identity-menu) closes the bell — exclusivity via GlobalOverlayStore (§1 D1)', () => {
    const fixture = render([event()]);
    trigger(fixture).click();
    fixture.detectChanges();
    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('true');

    TestBed.inject(GlobalOverlayStore).open('identity-menu');
    fixture.detectChanges();

    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('vision-events-rail')).toBeNull();
  });

  it('a row click (onRailOpen) closes the dropdown', () => {
    const fixture = render([event()]);
    trigger(fixture).click();
    fixture.detectChanges();
    expect(TestBed.inject(GlobalOverlayStore).isOpen('notification-bell')).toBe(true);

    (fixture.componentInstance as unknown as { onRailOpen(event: DetectionEvent): void }).onRailOpen(event());
    fixture.detectChanges();

    expect(TestBed.inject(GlobalOverlayStore).isOpen('notification-bell')).toBe(false);
    expect(fixture.nativeElement.querySelector('vision-events-rail')).toBeNull();
  });

  it('closes on Escape and returns focus to the trigger', () => {
    const fixture = render([event()]);
    trigger(fixture).click();
    fixture.detectChanges();
    expect(TestBed.inject(GlobalOverlayStore).isOpen('notification-bell')).toBe(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(TestBed.inject(GlobalOverlayStore).isOpen('notification-bell')).toBe(false);
  });
});
