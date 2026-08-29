import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationBell } from './notification-bell';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { LiveStore } from '../../core/live/live-store';
import { ToastService } from '../../core/toast.service';
import { VisionApi } from '../../core/api/vision-api';
import { GlobalOverlayStore } from '../../core/ui/overlay-store';
import type { DetectionEvent, LiveEvent } from '../../core/api/models';

/**
 * `NotificationBell` pulls in `EventsStore`/`FleetStore`/`LiveStore`/`VisionApi` — every one faked
 * here (no HTTP, no polling, no real `EventSource`), mirroring `shared/ui/app-sidebar/app-sidebar.spec.ts`'s
 * own "fake every transitive dependency purely so the tree can mount" approach. `GlobalOverlayStore`
 * is left real (root-provided, no HTTP deps of its own) — its own behavior is covered by
 * `core/ui/overlay-store.spec.ts`; this file only checks that the bell wires into it correctly
 * (docs/plans/done/UI-STATE-PLAN.md §1 D1/D2/D4/D5, closed by `identity-chip.ts`/`notification-bell.ts` moving
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

function liveEvent(partial: Partial<LiveEvent> = {}): LiveEvent {
  return {
    id: 'le-1',
    at: '2026-01-01T00:00:00Z',
    type: 'DEVICE_OFFLINE',
    message: 'Camera went quiet',
    attributes: {},
    ...partial,
  };
}

/** `fleet` (the always-on `fleet` SSE topic's own `AssetSummary[]`) backs `shouldToast`'s own
 *  "is this asset currently streaming" gate (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U4) —
 *  `streamingAssetIds` is every asset id this fake reports `'STREAMING'`; everything else is
 *  simply absent from the list, mirroring how a real offline/unknown asset just isn't in `fleet`'s
 *  own snapshot at all. */
function fakeLiveStore(events: LiveEvent[] = [], streamingAssetIds: readonly string[] = []) {
  return {
    liveEvents: () => events,
    fleet: () => streamingAssetIds.map((assetId) => ({ assetId, status: 'STREAMING' as const })),
  };
}

function render(events: DetectionEvent[] = [], liveEvents: LiveEvent[] = [], streamingAssetIds: readonly string[] = []) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: FleetStore, useValue: fakeFleetStore() },
      { provide: EventsStore, useValue: fakeEventsStore(events) },
      { provide: LiveStore, useValue: fakeLiveStore(liveEvents, streamingAssetIds) },
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

/**
 * `readIds` now persists under `vision.bell.readIds` (docs/plans/active/OPERATOR-UX-7-PLAN.md finding
 * B1) — every test below that isn't specifically exercising that persistence pre-seeds an *explicit,
 * empty* persisted set (not a true cold start) so a fresh component's initial events read as
 * genuinely unread, matching this whole suite's pre-B1 assumption (`seedReadIds`'s own doc comment:
 * an explicit persisted `[]` is trusted as-is, never re-seeded as if cold). The dedicated "read-ids
 * persistence" describe block below removes/sets this key itself per test to exercise the real
 * cold-start and reload paths.
 */
beforeEach(() => {
  localStorage.setItem('vision.bell.readIds', '[]');
});

afterEach(() => {
  localStorage.clear();
});

describe('NotificationBell — dropdown state (docs/plans/done/UI-STATE-PLAN.md)', () => {
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

describe('NotificationBell — system events (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2-§3.3)', () => {
  it('renders a durable system-events card, one row per non-DETECTION LiveEvent, with the empty state hidden', () => {
    const fixture = render([], [liveEvent({ id: 'le-1', type: 'DEVICE_OFFLINE', message: 'Camera went quiet' })]);
    trigger(fixture).click();
    fixture.detectChanges();

    const card = fixture.nativeElement.querySelector('.system-events');
    expect(card).not.toBeNull();
    expect(card?.querySelectorAll('vision-system-event-row')).toHaveLength(1);
    expect(card?.querySelector('.hint')).toBeNull();
  });

  it('excludes DETECTION from the system-events card — it already has its own card above', () => {
    const fixture = render(
      [],
      [liveEvent({ id: 'le-1', type: 'DETECTION', message: 'person detected' }), liveEvent({ id: 'le-2', type: 'DEVICE_ONLINE' })],
    );
    trigger(fixture).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.system-events')?.querySelectorAll('vision-system-event-row')).toHaveLength(1);
  });

  it('shows the empty hint with no system events at all', () => {
    const fixture = render([], []);
    trigger(fixture).click();
    fixture.detectChanges();

    const card = fixture.nativeElement.querySelector('.system-events');
    expect(card?.querySelector('.hint')?.textContent).toContain('No system events yet');
    expect(card?.querySelectorAll('vision-system-event-row')).toHaveLength(0);
  });

  it('closes with the rest of the dropdown', () => {
    const fixture = render([], [liveEvent()]);
    trigger(fixture).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.system-events')).not.toBeNull();

    trigger(fixture).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.system-events')).toBeNull();
  });
});

/**
 * Geofence breach toasts (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U4, §2 U4) — reproduces
 * and fixes the live symptom: a `KEEP-IN breach` toast firing on every page load for an asset that
 * has been offline for days. `Date.now()` is pinned so `NotificationBell#mountedAtMs` (captured at
 * `TestBed.createComponent` time) is a known instant `shouldToast`'s own gate can be tested against.
 */
function breachEvent(partial: Partial<LiveEvent> = {}): LiveEvent {
  return liveEvent({
    type: 'GEOFENCE_BREACH',
    message: 'KEEP-IN breach — Demo operating area',
    attributes: { assetId: 'a-1', zoneId: 'z-1', zoneName: 'Demo operating area', kind: 'KEEP_IN', direction: 'enter' },
    ...partial,
  });
}

describe('NotificationBell — geofence breach toasts (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U4)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-29T12:00:00.000Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("never toasts a breach that predates the bell mounting, even for a streaming asset — U4's own root cause (a replayed historic breach)", () => {
    const daysOld = breachEvent({ id: 'old', at: '2026-08-25T09:00:00.000Z' });
    render([], [daysOld], ['a-1']);
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('never toasts a breach for an asset that is not currently streaming, even with a perfectly fresh timestamp', () => {
    const fresh = breachEvent({ id: 'fresh', at: '2026-08-29T12:00:01.000Z' });
    render([], [fresh], []); // 'a-1' is not in the fleet's streaming set
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('toasts a genuinely fresh breach for a currently-streaming asset', () => {
    const fresh = breachEvent({ id: 'fresh', at: '2026-08-29T12:00:01.000Z' });
    render([], [fresh], ['a-1']);
    const toasts = TestBed.inject(ToastService).toasts();
    expect(toasts).toHaveLength(1);
    expect(toasts[0].text).toBe('KEEP-IN breach — Demo operating area');
  });

  it('an exit breach never toasts (relief, not a new alert) regardless of timing/streaming', () => {
    const exit = breachEvent({
      id: 'exit',
      at: '2026-08-29T12:00:01.000Z',
      attributes: { assetId: 'a-1', zoneId: 'z-1', zoneName: 'Demo operating area', kind: 'KEEP_IN', direction: 'exit' },
    });
    render([], [exit], ['a-1']);
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('the dropdown\'s durable system-events log is unaffected by toast eligibility — history stays visible there', () => {
    const daysOld = breachEvent({ id: 'old', at: '2026-08-25T09:00:00.000Z', type: 'GEOFENCE_BREACH' });
    const fixture = render([], [daysOld], []); // no toast either way
    trigger(fixture).click();
    fixture.detectChanges();
    // GEOFENCE_BREACH is excluded from the system-events card by design (class doc) — this asserts
    // the dropdown mounts and the suppressed toast didn't otherwise break rendering.
    expect(fixture.nativeElement.querySelector('vision-events-rail')).not.toBeNull();
  });
});

/**
 * `readIds` persistence (docs/plans/active/OPERATOR-UX-7-PLAN.md finding B1) — reproduces and fixes
 * the live symptom: a `9+` unread badge on every reload for a station where nothing has happened in
 * days, because the pre-B1 `readIds` was in-memory only. Each test here manages
 * `vision.bell.readIds` itself (overriding the file's own `beforeEach` seed) to exercise the real
 * cold-start / persisted-restore / write-through paths.
 */
describe('NotificationBell — read-ids persistence (docs/plans/active/OPERATOR-UX-7-PLAN.md finding B1)', () => {
  it('a true cold start (nothing ever persisted) seeds every currently-present event as read — no badge for pre-existing history', () => {
    localStorage.removeItem('vision.bell.readIds');
    const fixture = render([event({ id: 'a' }), event({ id: 'b' })]);
    expect(fixture.nativeElement.querySelector('.bell-badge')).toBeNull();
  });

  it('a persisted read-id set is trusted on mount — an id missing from it is genuinely unread', () => {
    localStorage.setItem('vision.bell.readIds', JSON.stringify(['a']));
    const fixture = render([event({ id: 'a' }), event({ id: 'b' })]);
    expect(fixture.nativeElement.querySelector('.bell-badge')?.textContent?.trim()).toBe('1');
  });

  it('opening the dropdown persists the newly-read ids — a fresh component (a reload) reads them back and shows no badge', () => {
    localStorage.removeItem('vision.bell.readIds');
    const first = render([event({ id: 'a' })]);
    trigger(first).click();
    first.detectChanges();
    expect(JSON.parse(localStorage.getItem('vision.bell.readIds') ?? '[]')).toEqual(['a']);

    TestBed.resetTestingModule();
    const second = render([event({ id: 'a' })]);
    expect(second.nativeElement.querySelector('.bell-badge')).toBeNull();
  });

  it('a corrupt persisted value degrades to a cold start rather than throwing', () => {
    localStorage.setItem('vision.bell.readIds', 'not valid json');
    expect(() => render([event({ id: 'a' })])).not.toThrow();

    TestBed.resetTestingModule();
    localStorage.setItem('vision.bell.readIds', 'not valid json');
    const fixture = render([event({ id: 'a' })]);
    expect(fixture.nativeElement.querySelector('.bell-badge')).toBeNull();
  });
});
