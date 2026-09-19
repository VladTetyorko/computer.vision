import { describe, expect, it } from 'vitest';
import { OverlayHostRegistry } from './overlay-host-registry';

function mountHost(): { root: HTMLElement; trigger: HTMLButtonElement } {
  const root = document.createElement('div');
  const trigger = document.createElement('button');
  root.appendChild(trigger);
  return { root, trigger };
}

describe('OverlayHostRegistry', () => {
  it('returns undefined for an id that was never registered', () => {
    expect(new OverlayHostRegistry().get('identity-menu')).toBeUndefined();
  });

  it('returns the registered root/trigger pair', () => {
    const registry = new OverlayHostRegistry();
    const host = mountHost();
    registry.register('notification-bell', host.root, host.trigger);
    expect(registry.get('notification-bell')).toEqual({ root: host.root, trigger: host.trigger });
  });

  it('re-registering the same id overwrites the previous entry (the sidebar hamburger/scrim swap case)', () => {
    const registry = new OverlayHostRegistry();
    const first = mountHost();
    const second = mountHost();
    registry.register('sidebar-mobile', first.root, first.trigger);
    registry.register('sidebar-mobile', second.root, second.trigger);
    expect(registry.get('sidebar-mobile')).toEqual({ root: second.root, trigger: second.trigger });
  });

  it('keeps independent entries per id', () => {
    const registry = new OverlayHostRegistry();
    const bell = mountHost();
    const identity = mountHost();
    registry.register('notification-bell', bell.root, bell.trigger);
    registry.register('identity-menu', identity.root, identity.trigger);
    expect(registry.get('notification-bell')?.trigger).toBe(bell.trigger);
    expect(registry.get('identity-menu')?.trigger).toBe(identity.trigger);
  });
});
