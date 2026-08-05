import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { PreflightItem } from '../../core/telemetry/flight-state-logic';
import { PreflightChecklist } from './preflight-checklist';

const ITEMS: readonly PreflightItem[] = [
  { label: 'Video feed', state: 'ok' },
  { label: 'Telemetry link', state: 'ok' },
  { label: 'GPS fix', state: 'unknown', detail: 'No GPS reading yet.' },
  { label: 'Battery', state: 'ok', detail: '87%' },
  { label: 'Armable', state: 'ok' },
];

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(PreflightChecklist);
  fixture.componentRef.setInput('items', ITEMS);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host };
}

describe('PreflightChecklist', () => {
  it('renders the plain heading and every row when the host asks for no collapsing (the /operate/preflight page)', () => {
    const { host } = render();

    expect(host.querySelector('h3')?.textContent?.trim()).toBe('Pre-flight');
    expect(host.querySelector('button')).toBeNull();
    expect(host.querySelectorAll('li').length).toBe(5);
  });

  it('stays expanded when collapsed is set but collapsible is not — collapse is opt-in', () => {
    const { host } = render({ collapsed: true });

    expect(host.querySelectorAll('li').length).toBe(5);
  });

  it('collapsible + expanded: a toggle head that still lists every row', () => {
    const { host } = render({ collapsible: true });
    const head = host.querySelector('button') as HTMLButtonElement;

    expect(head.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelectorAll('li').length).toBe(5);
  });

  it('collapsed: drops the rows and leaves only the head with its worst-state summary', () => {
    const { host } = render({ collapsible: true, collapsed: true });
    const head = host.querySelector('button') as HTMLButtonElement;

    expect(head.getAttribute('aria-expanded')).toBe('false');
    expect(host.querySelectorAll('li').length).toBe(0);
    expect(host.querySelector('.summary')?.textContent?.trim()).toBe('1 unchecked');
    expect(host.querySelector('.checklist')?.classList.contains('collapsed')).toBe(true);
  });

  it('the head emits the state the operator asked for, and never flips itself — the host owns it', () => {
    const { fixture, host } = render({ collapsible: true, collapsed: true });
    const handler = vi.fn();
    fixture.componentInstance.collapsedChange.subscribe(handler);

    (host.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(handler).toHaveBeenCalledWith(false);
    // Still collapsed: the input never changed, so nothing rendered differently.
    expect(host.querySelectorAll('li').length).toBe(0);
  });
});
