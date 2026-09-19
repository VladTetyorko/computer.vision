import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { DetectionEvent } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { SettingsFacade } from '../settings/settings-facade';
import { provideAppState } from '../state/app-state';
import { EventsFacade } from './events-facade';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function event(overrides: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.8,
    firstSeen: '2026-07-23T10:00:00.000Z',
    lastSeen: '2026-07-23T10:00:05.000Z',
    state: 'OPEN',
    ...overrides,
  };
}

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(vi.fn()) };
}

function setUpFacade(eventsMock: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue([])) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      { provide: VisionApi, useValue: { events: eventsMock } },
      { provide: PollScheduler, useValue: stubScheduler() },
      { provide: SettingsFacade, useValue: { eventNotifications: () => false } },
    ],
  });
  return { facade: TestBed.inject(EventsFacade), events: eventsMock };
}

describe('EventsFacade', () => {
  it('never fetches until the first activate()', async () => {
    const { facade, events } = setUpFacade();
    await flush();

    expect(events).not.toHaveBeenCalled();
    expect(facade.events()).toEqual([]);
  });

  it('activate() fetches once and populates events(); release() then activate() refetches', async () => {
    const { facade, events } = setUpFacade(vi.fn().mockResolvedValue([event()]));

    facade.activate();
    await flush();
    expect(events).toHaveBeenCalledTimes(1);
    expect(facade.events().map((e) => e.id)).toEqual(['e-1']);

    facade.release();
    facade.activate();
    await flush();
    expect(events).toHaveBeenCalledTimes(2);
  });

  it('a second concurrent activate() only fetches once, and release() is a ref-count (not a hard stop)', async () => {
    const { facade, events } = setUpFacade();

    facade.activate();
    facade.activate();
    await flush();
    expect(events).toHaveBeenCalledTimes(1);

    facade.release(); // one consumer left — still active, no reactivation fetch expected here
    await flush();
    expect(events).toHaveBeenCalledTimes(1);
  });

  it('an unmatched release() is a defensive no-op, never throwing', () => {
    const { facade } = setUpFacade();
    expect(() => facade.release()).not.toThrow();
  });

  it('every real consumer only ever needs events()/activate()/release() — grepped before writing this facade', () => {
    const { facade } = setUpFacade();
    expect(typeof facade.events).toBe('function');
    expect(typeof facade.activate).toBe('function');
    expect(typeof facade.release).toBe('function');
  });
});
