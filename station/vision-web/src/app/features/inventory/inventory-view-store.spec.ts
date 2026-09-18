import { afterEach, describe, expect, it, vi } from 'vitest';
import { InventoryViewStore } from './inventory-view-store';

const KEY = 'vision.inventory.view';

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('InventoryViewStore', () => {
  it('remembers a view across instances', () => {
    new InventoryViewStore().write('issued');
    expect(new InventoryViewStore().read()).toBe('issued');
  });

  it('remembers an explicit All as a value, distinct from never having chosen', () => {
    const store = new InventoryViewStore();
    expect(store.read()).toBeUndefined();
    store.write(null);
    expect(localStorage.getItem(KEY)).toBe('all');
    expect(store.read()).toBeNull();
  });

  it('reads a stale or hand-edited value as never chosen', () => {
    localStorage.setItem(KEY, 'grounded');
    expect(new InventoryViewStore().read()).toBeUndefined();
  });

  it('answers "never chosen" instead of throwing when storage is unreadable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });
    expect(new InventoryViewStore().read()).toBeUndefined();
  });

  it('swallows a write that storage refuses', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota');
    });
    expect(() => new InventoryViewStore().write('in-field')).not.toThrow();
  });
});
