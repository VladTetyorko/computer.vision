import { afterEach, describe, expect, it, vi } from 'vitest';
import { InventoryViewStorage } from './inventory-view-storage';

const KEY = 'vision.inventory.view';

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('InventoryViewStorage', () => {
  it('remembers a view across instances', () => {
    new InventoryViewStorage().write('issued');
    expect(new InventoryViewStorage().read()).toBe('issued');
  });

  it('remembers an explicit All as a value, distinct from never having chosen', () => {
    const store = new InventoryViewStorage();
    expect(store.read()).toBeUndefined();
    store.write(null);
    expect(localStorage.getItem(KEY)).toBe('all');
    expect(store.read()).toBeNull();
  });

  it('reads a stale or hand-edited value as never chosen', () => {
    localStorage.setItem(KEY, 'grounded');
    expect(new InventoryViewStorage().read()).toBeUndefined();
  });

  it('answers "never chosen" instead of throwing when storage is unreadable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });
    expect(new InventoryViewStorage().read()).toBeUndefined();
  });

  it('swallows a write that storage refuses', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota');
    });
    expect(() => new InventoryViewStorage().write('in-field')).not.toThrow();
  });
});
