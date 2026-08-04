import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { GlobalOverlayStore } from './overlay-store';

@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

function makeStore(): GlobalOverlayStore {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'a', component: StubPage },
        { path: 'b', component: StubPage },
      ]),
    ],
  });
  return TestBed.inject(GlobalOverlayStore);
}

/**
 * A fake overlay's DOM — a `root` containing a real `trigger` button, both attached to
 * `document.body` so `Node.contains`/`.focus()` behave exactly as they do for the real
 * `identity-chip`/`notification-bell` hosts (`register()`'s own doc comment), not merely detached
 * nodes a real browser would treat differently.
 */
function mountHost(): { root: HTMLElement; trigger: HTMLButtonElement; cleanup: () => void } {
  const root = document.createElement('div');
  const trigger = document.createElement('button');
  root.appendChild(trigger);
  document.body.appendChild(root);
  return { root, trigger, cleanup: () => root.remove() };
}

describe('GlobalOverlayStore', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('starts with nothing open', () => {
    const store = makeStore();
    expect(store.active()).toBeNull();
    expect(store.isOpen('identity-menu')).toBe(false);
    expect(store.isOpen('notification-bell')).toBe(false);
    expect(store.isOpen('sidebar-mobile')).toBe(false);
  });

  it('is exclusive — opening a second overlay closes the first (docs/UI-STATE-PLAN.md §1 D1)', () => {
    const store = makeStore();
    store.open('notification-bell');
    expect(store.isOpen('notification-bell')).toBe(true);

    store.open('identity-menu');
    expect(store.isOpen('identity-menu')).toBe(true);
    expect(store.isOpen('notification-bell')).toBe(false);
  });

  it('toggle() opens then closes the same id', () => {
    const store = makeStore();
    store.toggle('sidebar-mobile');
    expect(store.isOpen('sidebar-mobile')).toBe(true);
    store.toggle('sidebar-mobile');
    expect(store.active()).toBeNull();
  });

  it('a stale close(id) is a no-op — mirrors UiStore.close\'s own semantic, preserved not reimplemented', () => {
    const store = makeStore();
    store.open('notification-bell');
    store.close('identity-menu'); // not the one open — must not clobber notification-bell
    expect(store.isOpen('notification-bell')).toBe(true);

    store.close('notification-bell');
    expect(store.active()).toBeNull();
  });

  it('closes everything on NavigationEnd (§1 D2 — this survived a route change before this store existed)', async () => {
    const store = makeStore();
    store.open('identity-menu');
    expect(store.isOpen('identity-menu')).toBe(true);

    await TestBed.inject(Router).navigateByUrl('/a');
    expect(store.active()).toBeNull();

    // Not a one-shot: a second, unrelated navigation still closes whatever got reopened meanwhile.
    store.open('notification-bell');
    await TestBed.inject(Router).navigateByUrl('/b');
    expect(store.active()).toBeNull();
  });

  describe('Escape', () => {
    it('closes whatever is open and returns focus to its registered trigger (§4 a11y bullet)', () => {
      const store = makeStore();
      const host = mountHost();
      store.register('identity-menu', host.root, host.trigger);
      store.open('identity-menu');

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

      expect(store.active()).toBeNull();
      expect(document.activeElement).toBe(host.trigger);
      host.cleanup();
    });

    it('is a no-op when nothing is open', () => {
      const store = makeStore();
      expect(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))).not.toThrow();
      expect(store.active()).toBeNull();
    });

    it('ignores every other key', () => {
      const store = makeStore();
      store.open('notification-bell');
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      expect(store.isOpen('notification-bell')).toBe(true);
    });
  });

  describe('outside click (§2.2 rule 3, §1 D3)', () => {
    it('closes the open overlay on a click outside its registered root', () => {
      const store = makeStore();
      const host = mountHost();
      store.register('notification-bell', host.root, host.trigger);
      store.open('notification-bell');

      const outside = document.createElement('div');
      document.body.appendChild(outside);
      outside.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(store.active()).toBeNull();
      host.cleanup();
      outside.remove();
    });

    it('does not close on a click inside the registered root — e.g. the dropdown content itself', () => {
      const store = makeStore();
      const host = mountHost();
      const dropdownRow = document.createElement('span');
      host.root.appendChild(dropdownRow);
      store.register('notification-bell', host.root, host.trigger);
      store.open('notification-bell');

      dropdownRow.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(store.isOpen('notification-bell')).toBe(true);
      host.cleanup();
    });

    it('does not close while a *different* overlay has no registered host at all (no host ⇒ every click is "outside")', () => {
      // An overlay that never called `register()` still degrades safely: any click closes it (fails
      // safe, never fails open) rather than throwing on a missing host lookup.
      const store = makeStore();
      store.open('sidebar-mobile');
      document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(store.active()).toBeNull();
    });

    it('clicking the trigger while open closes it once — not close-then-reopen (the plan\'s own explicit callout)', () => {
      const store = makeStore();
      const host = mountHost();
      store.register('notification-bell', host.root, host.trigger);
      // Mirrors the real trigger's own `(click)="toggle(...)"` binding — registered directly on the
      // element, exactly like Angular's own event binding, so DOM bubble order is genuinely exercised
      // rather than assumed.
      host.trigger.addEventListener('click', () => store.toggle('notification-bell'));
      store.open('notification-bell');

      host.trigger.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(store.active()).toBeNull();
      host.cleanup();
    });

    it('clicking the trigger while closed opens it and the document listener leaves it open', () => {
      const store = makeStore();
      const host = mountHost();
      store.register('notification-bell', host.root, host.trigger);
      host.trigger.addEventListener('click', () => store.toggle('notification-bell'));

      host.trigger.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(store.isOpen('notification-bell')).toBe(true);
      host.cleanup();
    });
  });

  it('register() overwrites a stale entry for the same id (the sidebar hamburger/scrim swap case)', () => {
    const store = makeStore();
    const first = mountHost();
    const second = mountHost();
    store.register('sidebar-mobile', first.root, first.trigger);
    store.register('sidebar-mobile', second.root, second.trigger);
    store.open('sidebar-mobile');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(document.activeElement).toBe(second.trigger);
    first.cleanup();
    second.cleanup();
  });
});
