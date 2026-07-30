import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { IconButton } from './icon-button';

function render(icon: string, label: string) {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(IconButton);
  fixture.componentRef.setInput('icon', icon);
  fixture.componentRef.setInput('label', label);
  fixture.detectChanges();
  const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
  return { fixture, button };
}

describe('IconButton', () => {
  it('a required label drives both title and aria-label — the one accessible-name rule this component exists to enforce', () => {
    const { button } = render('close', 'Close panel');

    expect(button.getAttribute('title')).toBe('Close panel');
    expect(button.getAttribute('aria-label')).toBe('Close panel');
  });

  it('defaults to the ghost variant and aria-pressed="false" when active is unset', () => {
    const { button } = render('kebab', 'More actions');

    expect(button.className).toContain('icon-btn');
    expect(button.className).toContain('ghost');
    expect(button.getAttribute('aria-pressed')).toBe('false');
  });

  it('active=true sets aria-pressed and the .active class', () => {
    const { fixture, button } = render('layers', 'Toggle layers');
    fixture.componentRef.setInput('active', true);
    fixture.detectChanges();

    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.classList.contains('active')).toBe(true);
  });

  it('renders the requested variant class (hud/danger)', () => {
    const { fixture, button } = render('close', 'Close');
    fixture.componentRef.setInput('variant', 'hud');
    fixture.detectChanges();
    expect(button.className).toContain('hud');

    fixture.componentRef.setInput('variant', 'danger');
    fixture.detectChanges();
    expect(button.className).toContain('danger');
  });

  it('emits activated exactly once per click', () => {
    const { fixture, button } = render('plus', 'Add');
    const handler = vi.fn();
    fixture.componentInstance.activated.subscribe(handler);

    button.click();

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('renders a vision-icon with the given icon name', () => {
    const { fixture } = render('trash', 'Delete');
    const icon = fixture.nativeElement.querySelector('vision-icon');
    expect(icon).not.toBeNull();
  });
});
