import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_UNDO_TIMEOUT_MS, UndoToastService } from './undo-toast.service';

function create(): UndoToastService {
  TestBed.configureTestingModule({});
  return TestBed.inject(UndoToastService);
}

describe('UndoToastService', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a toast with the given message and the default 10s timeout', () => {
    const service = create();
    service.showUndo('Archived "drone-1".', vi.fn());

    expect(service.toast()).toMatchObject({ message: 'Archived "drone-1".', timeoutMs: DEFAULT_UNDO_TIMEOUT_MS });
  });

  it('honors a custom timeoutMs', () => {
    const service = create();
    service.showUndo('Deactivated "cam-2".', vi.fn(), { timeoutMs: 3_000 });

    expect(service.toast()?.timeoutMs).toBe(3_000);
  });

  it('undo() cancels the timer, runs the undo callback, and clears the toast — never commits', () => {
    const service = create();
    const undo = vi.fn();
    const onCommit = vi.fn();
    service.showUndo('Archived "drone-1".', undo, { onCommit });

    service.undo();

    expect(undo).toHaveBeenCalledOnce();
    expect(onCommit).not.toHaveBeenCalled();
    expect(service.toast()).toBeNull();

    // The original timer must actually be cancelled, not just ignored — advancing past the
    // original timeout must not fire a stray commit.
    vi.advanceTimersByTime(DEFAULT_UNDO_TIMEOUT_MS);
    expect(onCommit).not.toHaveBeenCalled();
  });

  it('undo() on an already-cleared toast is a harmless no-op', () => {
    const service = create();
    expect(() => service.undo()).not.toThrow();
  });

  it('auto-commits after timeoutMs elapses without Undo — runs onCommit, never undo', () => {
    const service = create();
    const undo = vi.fn();
    const onCommit = vi.fn();
    service.showUndo('Archived "drone-1".', undo, { onCommit, timeoutMs: 10_000 });

    vi.advanceTimersByTime(9_999);
    expect(service.toast()).not.toBeNull();
    expect(onCommit).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(service.toast()).toBeNull();
    expect(onCommit).toHaveBeenCalledOnce();
    expect(undo).not.toHaveBeenCalled();
  });

  it('dismiss() commits immediately, same as a natural timeout — never undoes', () => {
    const service = create();
    const undo = vi.fn();
    const onCommit = vi.fn();
    service.showUndo('Archived "drone-1".', undo, { onCommit });

    service.dismiss();

    expect(service.toast()).toBeNull();
    expect(onCommit).toHaveBeenCalledOnce();
    expect(undo).not.toHaveBeenCalled();
  });

  it('dismiss() with no active toast is a harmless no-op', () => {
    const service = create();
    expect(() => service.dismiss()).not.toThrow();
  });

  it('onCommit is optional — timeout/dismiss/replace never throw without one', () => {
    const service = create();
    service.showUndo('a', vi.fn());
    expect(() => service.dismiss()).not.toThrow();

    service.showUndo('b', vi.fn());
    expect(() => vi.advanceTimersByTime(DEFAULT_UNDO_TIMEOUT_MS)).not.toThrow();
  });

  it('replace semantics: a newer showUndo() commits the previous toast immediately (commit-on-replace)', () => {
    const service = create();
    const firstUndo = vi.fn();
    const firstCommit = vi.fn();
    service.showUndo('Archived "drone-1".', firstUndo, { onCommit: firstCommit });

    vi.advanceTimersByTime(4_000); // well before the first toast's own 10s window closes

    const secondUndo = vi.fn();
    service.showUndo('Archived "drone-2".', secondUndo);

    // The first toast's own action is committed (settled), not undone, the instant it's replaced.
    expect(firstCommit).toHaveBeenCalledOnce();
    expect(firstUndo).not.toHaveBeenCalled();
    expect(service.toast()?.message).toBe('Archived "drone-2".');

    // Undo now only ever applies to the *current* (second) toast.
    service.undo();
    expect(secondUndo).toHaveBeenCalledOnce();
    expect(firstUndo).not.toHaveBeenCalled();
  });

  it('a stale timer from a replaced toast never fires a second commit for the new one', () => {
    const service = create();
    service.showUndo('a', vi.fn(), { timeoutMs: 5_000 });
    vi.advanceTimersByTime(1_000);
    const secondCommit = vi.fn();
    service.showUndo('b', vi.fn(), { timeoutMs: 5_000, onCommit: secondCommit });

    // If the first toast's timer had leaked, it would fire at t=5000 (1000 + 4000 more) — assert
    // the second toast's own timer (armed at t=1000, due at t=6000) is the one that actually commits.
    vi.advanceTimersByTime(4_000); // t=5000
    expect(secondCommit).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1_000); // t=6000
    expect(secondCommit).toHaveBeenCalledOnce();
  });

  it('assigns increasing ids across successive toasts', () => {
    const service = create();
    service.showUndo('a', vi.fn());
    const first = service.toast();
    service.dismiss();
    service.showUndo('b', vi.fn());
    const second = service.toast();

    expect(second!.id).toBeGreaterThan(first!.id);
  });
});
