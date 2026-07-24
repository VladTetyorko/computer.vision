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

  it('info()/error() take no action — only ok()/notify() do', () => {
    const service = create();
    service.info('fyi');
    service.error('boom');
    expect(service.toasts().every((t) => t.action === undefined)).toBe(true);
  });

  it('auto-dismisses each kind after its own duration, ok/info/notification/error shortest to longest', () => {
    const service = create();
    service.ok('a');
    service.info('b');
    service.notify('c');
    service.error('d');
    expect(service.toasts()).toHaveLength(4);

    vi.advanceTimersByTime(4_000);
    expect(service.toasts().map((t) => t.kind)).toEqual(['info', 'notification', 'error']);

    vi.advanceTimersByTime(1_000); // 5s total
    expect(service.toasts().map((t) => t.kind)).toEqual(['notification', 'error']);

    vi.advanceTimersByTime(1_000); // 6s total
    expect(service.toasts().map((t) => t.kind)).toEqual(['error']);

    vi.advanceTimersByTime(3_000); // 9s total
    expect(service.toasts()).toEqual([]);
  });
});
