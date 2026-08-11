import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PollScheduler, shouldPoll } from './poll-scheduler';

function create(): PollScheduler {
  TestBed.configureTestingModule({});
  return TestBed.inject(PollScheduler);
}

describe('shouldPoll', () => {
  it('polls while the tab is visible', () => {
    expect(shouldPoll(false)).toBe(true);
  });

  it('pauses while the tab is hidden', () => {
    expect(shouldPoll(true)).toBe(false);
  });
});

describe('PollScheduler', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not call back before its period has elapsed', () => {
    const scheduler = create();
    const callback = vi.fn();
    scheduler.schedule(2_000, callback);

    vi.advanceTimersByTime(1_000);
    expect(callback).not.toHaveBeenCalled();
  });

  it('calls back once per period on a single shared timer', () => {
    const scheduler = create();
    const callback = vi.fn();
    scheduler.schedule(2_000, callback);

    vi.advanceTimersByTime(2_000);
    expect(callback).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(4_000);
    expect(callback).toHaveBeenCalledTimes(3);
  });

  it('drives multiple consumers with different cadences off the one timer', () => {
    const scheduler = create();
    const fast = vi.fn();
    const slow = vi.fn();
    scheduler.schedule(1_000, fast);
    scheduler.schedule(5_000, slow);

    vi.advanceTimersByTime(5_000);
    expect(fast).toHaveBeenCalledTimes(5);
    expect(slow).toHaveBeenCalledTimes(1);
  });

  it('pauses every task while the tab is hidden, without dropping the eventual call', () => {
    const scheduler = create();
    const callback = vi.fn();
    scheduler.schedule(2_000, callback);

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    vi.advanceTimersByTime(10_000);
    expect(callback).not.toHaveBeenCalled();

    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    vi.advanceTimersByTime(2_000);
    expect(callback).toHaveBeenCalledTimes(1);
  });

  it('stops calling back once unsubscribed', () => {
    const scheduler = create();
    const callback = vi.fn();
    const unsubscribe = scheduler.schedule(1_000, callback);

    vi.advanceTimersByTime(1_000);
    expect(callback).toHaveBeenCalledTimes(1);

    unsubscribe();
    vi.advanceTimersByTime(5_000);
    expect(callback).toHaveBeenCalledTimes(1);
  });

  it('an `ignoreHidden` task keeps calling back while the tab is hidden', () => {
    const scheduler = create();
    const callback = vi.fn();
    scheduler.schedule(2_000, callback, { ignoreHidden: true });

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    vi.advanceTimersByTime(6_000);

    expect(callback).toHaveBeenCalledTimes(3);
  });

  it('a default task still pauses while an `ignoreHidden` sibling keeps running', () => {
    const scheduler = create();
    const paused = vi.fn();
    const unpaused = vi.fn();
    scheduler.schedule(1_000, paused);
    scheduler.schedule(1_000, unpaused, { ignoreHidden: true });

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    vi.advanceTimersByTime(3_000);

    expect(paused).not.toHaveBeenCalled();
    expect(unpaused).toHaveBeenCalledTimes(3);
  });

  // --- In-flight guard (docs/plans/done/MVP2-PLAN.md §S, S-b) ---------------------------------------------
  // Without this, a hung/slow backend turns "N pollers × M elapsed ticks" into an unbounded pile
  // of overlapping HTTP requests — every real poller in this app already returns its own promise
  // from `callback` (see e.g. `FleetStore`'s registration), so this guard applies to all of them.

  it('skips a due tick while the previous promise-returning call is still pending', async () => {
    const scheduler = create();
    let resolveFirst!: () => void;
    const callback = vi.fn(() => new Promise<void>((resolve) => (resolveFirst = resolve)));
    scheduler.schedule(1_000, callback);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(callback).toHaveBeenCalledTimes(1); // fired, still pending

    await vi.advanceTimersByTimeAsync(3_000); // three more due ticks while it's in flight
    expect(callback).toHaveBeenCalledTimes(1); // none of them fired a second overlapping call

    resolveFirst();
    await vi.advanceTimersByTimeAsync(0); // let the microtask that clears `inFlight` run
    await vi.advanceTimersByTimeAsync(1_000);
    expect(callback).toHaveBeenCalledTimes(2); // resumes on the next due tick once settled
  });

  it('a rejected promise still clears the in-flight guard — a failed poll is not stuck forever', async () => {
    const scheduler = create();
    let rejectFirst!: (error: unknown) => void;
    const callback = vi
      .fn()
      .mockImplementationOnce(() => new Promise<void>((_resolve, reject) => (rejectFirst = reject)))
      .mockResolvedValue(undefined);
    scheduler.schedule(1_000, callback);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(callback).toHaveBeenCalledTimes(1);

    rejectFirst(new Error('boom'));
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(callback).toHaveBeenCalledTimes(2);
  });

  it('a synchronous (non-promise) callback is unaffected by the guard — fires on every due tick', () => {
    const scheduler = create();
    const callback = vi.fn(); // returns `undefined`, e.g. a clock-tick registration
    scheduler.schedule(1_000, callback);

    vi.advanceTimersByTime(3_000);
    expect(callback).toHaveBeenCalledTimes(3);
  });

  it('an unrelated task keeps running after a sibling unsubscribes', () => {
    const scheduler = create();
    const a = vi.fn();
    const b = vi.fn();
    const unsubscribeA = scheduler.schedule(1_000, a);
    scheduler.schedule(1_000, b);

    unsubscribeA();
    vi.advanceTimersByTime(1_000);

    expect(a).not.toHaveBeenCalled();
    expect(b).toHaveBeenCalledTimes(1);
  });
});
