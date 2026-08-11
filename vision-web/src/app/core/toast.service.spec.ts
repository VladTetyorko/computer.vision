import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastService } from './toast.service';

function create(): ToastService {
  TestBed.configureTestingModule({});
  return TestBed.inject(ToastService);
}

describe('ToastService', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('pushes an ok/info/error/notification toast with the right kind and text', () => {
    const service = create();
    service.ok('saved');
    service.info('fyi');
    service.error('boom');
    service.notify('person detected');

    const kinds = service.toasts().map((t) => [t.kind, t.text]);
    expect(kinds).toEqual([
      ['ok', 'saved'],
      ['info', 'fyi'],
      ['error', 'boom'],
      ['notification', 'person detected'],
    ]);
  });

  it('assigns increasing ids regardless of kind', () => {
    const service = create();
    service.ok('a');
    service.notify('b');
    const [first, second] = service.toasts();
    expect(second.id).toBeGreaterThan(first.id);
  });

  it('dismiss(id) removes only that toast', () => {
    const service = create();
    service.ok('a');
    service.notify('b');
    const [first, second] = service.toasts();

    service.dismiss(first.id);

    expect(service.toasts()).toEqual([second]);
  });

  it('carries an optional action through ok() and notify() alike', () => {
    const service = create();
    const onClick = vi.fn();
    service.ok('archived', { label: 'Undo', onClick });
    service.notify('person detected', { label: 'Watch live', onClick });

    expect(service.toasts()[0].action?.label).toBe('Undo');
    expect(service.toasts()[1].action?.label).toBe('Watch live');
  });

  it('info()/error()/warn() take no action — only ok()/notify() do', () => {
    const service = create();
    service.info('fyi');
    service.error('boom');
    service.warn('careful');
    expect(service.toasts().every((t) => t.action === undefined)).toBe(true);
  });

  it('warn() pushes a warning-kind toast (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1\'s NO_ACK outcome)', () => {
    const service = create();
    service.warn('no acknowledgement');
    expect(service.toasts()).toEqual([{ id: expect.any(Number), kind: 'warning', text: 'no acknowledgement', action: undefined }]);
  });

  it('auto-dismisses every kind after the same 5s, none lingering longer than another', () => {
    const service = create();
    service.ok('a');
    service.info('b');
    service.notify('c');
    service.warn('e');
    service.error('d');
    expect(service.toasts()).toHaveLength(5);

    vi.advanceTimersByTime(4_999);
    expect(service.toasts()).toHaveLength(5);

    vi.advanceTimersByTime(1); // 5s total
    expect(service.toasts()).toEqual([]);
  });

  it('starts each toast\'s own 5s clock when it is pushed, not when the first one was', () => {
    const service = create();
    service.error('first');
    vi.advanceTimersByTime(3_000);
    service.error('second');

    vi.advanceTimersByTime(2_000); // first is 5s old, second only 2s
    expect(service.toasts().map((t) => t.text)).toEqual(['second']);

    vi.advanceTimersByTime(3_000);
    expect(service.toasts()).toEqual([]);
  });
});
