import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { ComingSoon } from './coming-soon';

function render() {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(ComingSoon);
  fixture.componentRef.setInput('title', 'Flight plans / missions');
  fixture.componentRef.setInput('description', 'Saved, uploadable flight plans are coming.');
  fixture.componentRef.setInput('eyebrow', 'Operate');
  fixture.detectChanges();
  return fixture;
}

describe('ComingSoon', () => {
  it('renders the title/description/eyebrow, reusing the .empty primitive', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('.card.empty')).not.toBeNull();
    expect(root.querySelector('h3')?.textContent?.trim()).toBe('Flight plans / missions');
    expect(root.querySelector('p')?.textContent?.trim()).toBe('Saved, uploadable flight plans are coming.');
    expect(root.querySelector('.eyebrow')?.textContent).toContain('Operate');
    expect(root.querySelector('.eyebrow')?.textContent).toContain('Coming soon');
  });

  it('renders no next-step link when nearestTo is unset — never a fabricated relation', () => {
    const fixture = render();
    expect(fixture.nativeElement.querySelector('a')).toBeNull();
  });

  it('renders exactly one real next-step link when nearestTo/nearestLabel are both set', () => {
    const fixture = render();
    fixture.componentRef.setInput('nearestTo', '/fly');
    fixture.componentRef.setInput('nearestLabel', 'Open the cockpit');
    fixture.detectChanges();

    const links = fixture.nativeElement.querySelectorAll('a');
    expect(links.length).toBe(1);
    expect(links[0].getAttribute('href')).toBe('/fly');
    expect(links[0].textContent?.trim()).toBe('Open the cockpit');
  });
});
