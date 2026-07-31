import { beforeEach, describe, expect, it } from 'vitest';
import { UiStore } from './ui-store';

describe('UiStore', () => {
  beforeEach(() => localStorage.clear());

  it('starts closed', () => {
    const ui = new UiStore();
    expect(ui.active()).toBeNull();
    expect(ui.isOpen('a')).toBe(false);
  });

  it('opens one overlay and reports it open', () => {
    const ui = new UiStore();
    ui.open('a');
    expect(ui.active()).toBe('a');
    expect(ui.isOpen('a')).toBe(true);
  });

  it('opening a second overlay closes the first — never two at once', () => {
    const ui = new UiStore();
    ui.open('a');
    ui.open('b');
    expect(ui.active()).toBe('b');
    expect(ui.isOpen('a')).toBe(false);
    expect(ui.isOpen('b')).toBe(true);
  });

  it('toggle opens then closes the same id', () => {
    const ui = new UiStore();
    ui.toggle('a');
    expect(ui.active()).toBe('a');
    ui.toggle('a');
    expect(ui.active()).toBeNull();
  });

  it('close() with no id closes whatever is open', () => {
    const ui = new UiStore();
    ui.open('a');
    ui.close();
    expect(ui.active()).toBeNull();
  });

  it('close(id) only closes when id is the open one (stale close is a no-op)', () => {
    const ui = new UiStore();
    ui.open('b');
    ui.close('a'); // 'a' is not open — must not clobber 'b'
    expect(ui.active()).toBe('b');
    ui.close('b');
    expect(ui.active()).toBeNull();
  });

  describe('persistence', () => {
    it('round-trips the active id through localStorage when a storageKey is given', () => {
      const key = 'vision.test.ui';
      const ui = new UiStore(key);
      ui.open('a');
      expect(localStorage.getItem(key)).toBe('a');

      // a fresh instance on the same key restores it
      expect(new UiStore(key).active()).toBe('a');
    });

    it('removes the key on close rather than persisting a literal "null"', () => {
      const key = 'vision.test.ui';
      const ui = new UiStore(key);
      ui.open('a');
      ui.close();
      expect(localStorage.getItem(key)).toBeNull();
      expect(new UiStore(key).active()).toBeNull();
    });

    it('a transient store (no storageKey) never writes to localStorage', () => {
      const ui = new UiStore();
      ui.open('a');
      expect(localStorage.length).toBe(0);
    });
  });
});
