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
