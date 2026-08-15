import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { SidePanel } from './side-panel';

function render(title: string) {
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(SidePanel);
  fixture.componentRef.setInput('title', title);
  fixture.detectChanges();
  return fixture;
}

describe('SidePanel', () => {
  it('renders role="dialog" with an aria-label equal to the title', async () => {
    const fixture = render('Flight');
    await fixture.whenStable();

    const aside = fixture.nativeElement.querySelector('aside') as HTMLElement;
    expect(aside.getAttribute('role')).toBe('dialog');
    expect(aside.getAttribute('aria-label')).toBe('Flight');
  });

  it('moves focus to the head on open', async () => {
    const fixture = render('CV control');
    await fixture.whenStable();

    const head = fixture.nativeElement.querySelector('.side-panel-head') as HTMLElement;
    expect(document.activeElement).toBe(head);
  });

  it('emits close when the close icon-button is activated', async () => {
    const fixture = render('Layers');
    await fixture.whenStable();
    const handler = vi.fn();
    fixture.componentInstance.close.subscribe(handler);

    const closeButton = fixture.nativeElement.querySelector('button[aria-label="Close panel"]') as HTMLButtonElement;
    closeButton.click();

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('emits close on Escape from within the panel', async () => {
    const fixture = render('Help');
    await fixture.whenStable();
    const handler = vi.fn();
    fixture.componentInstance.close.subscribe(handler);

    const aside = fixture.nativeElement.querySelector('aside') as HTMLElement;
    aside.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('renders an optional subtitle and leading icon only when provided', async () => {
    const fixture = render('Flight');
    await fixture.whenStable();
    // Scoped to a direct child of the head — the close button *also* renders its own nested
    // <vision-icon name="close">, so a bare `querySelector('vision-icon')` would always match that
    // one regardless of whether the optional leading icon is present.
    expect(fixture.nativeElement.querySelector('.sub')).toBeNull();
    expect(fixture.nativeElement.querySelector('.side-panel-head > vision-icon')).toBeNull();

    fixture.componentRef.setInput('subtitle', 'Mode, arm, disarm');
    fixture.componentRef.setInput('icon', 'cockpit');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.sub')?.textContent?.trim()).toBe('Mode, arm, disarm');
    expect(fixture.nativeElement.querySelector('.side-panel-head > vision-icon')).not.toBeNull();
  });

  it('renders an empty footer bar when no [footer] content is projected — CSS (:empty) hides it', async () => {
    const fixture = render('Flight');
    await fixture.whenStable();

    const foot = fixture.nativeElement.querySelector('.side-panel-foot') as HTMLElement;
    expect(foot.children.length).toBe(0);
  });
});
