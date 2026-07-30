import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { NavTile } from './nav-tile';

function render() {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(NavTile);
  fixture.componentRef.setInput('icon', 'cockpit');
  fixture.componentRef.setInput('name', 'Cockpit');
  fixture.componentRef.setInput('to', '/fly');
  fixture.detectChanges();
  return fixture;
}

describe('NavTile', () => {
  it('is a real, keyboard-focusable <a> — the whole tile is one link', () => {
    const fixture = render();

    const link = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(link.tagName).toBe('A');
    expect(link.getAttribute('href')).toBe('/fly');
    expect(link.tabIndex).not.toBe(-1);
  });

  it('renders the name always, description/badge only when provided', () => {
    const fixture = render();

    expect(fixture.nativeElement.querySelector('.tile-name')?.textContent?.trim()).toBe('Cockpit');
    expect(fixture.nativeElement.querySelector('.tile-desc')).toBeNull();
    expect(fixture.nativeElement.querySelector('.chip')).toBeNull();

    fixture.componentRef.setInput('description', 'Fly one drone');
    fixture.componentRef.setInput('badge', 'soon');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.tile-desc')?.textContent?.trim()).toBe('Fly one drone');
    expect(fixture.nativeElement.querySelector('.chip')?.textContent?.trim()).toBe('soon');
  });

  it('description is not a second tab stop — only the <a> itself is focusable', () => {
    const fixture = render();
    fixture.componentRef.setInput('description', 'Fly one drone');
    fixture.detectChanges();

    const desc = fixture.nativeElement.querySelector('.tile-desc') as HTMLElement;
    expect(desc.tagName).toBe('SPAN');
    expect(desc.hasAttribute('tabindex')).toBe(false);
  });
});
