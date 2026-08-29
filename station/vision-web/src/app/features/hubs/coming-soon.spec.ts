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
  it('renders inside the standard page frame — a page bar carrying the title', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    const pageBar = root.querySelector('vision-page-bar');
    expect(pageBar).not.toBeNull();
    expect(pageBar!.querySelector('.page-bar-name')?.textContent?.trim()).toBe('Flight plans / missions');
  });

  it('renders the card after the page bar, with eyebrow + description from inputs', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    const pageBar = root.querySelector('vision-page-bar');
    const card = root.querySelector('.card.empty');
    expect(card).not.toBeNull();
    // Sits after the page bar in the layout flow, not floating above/beside it.
    expect(pageBar!.compareDocumentPosition(card!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    expect(card!.querySelector('.eyebrow')?.textContent).toContain('Operate');
    expect(card!.querySelector('.eyebrow')?.textContent).toContain('Coming soon');
    expect(card!.querySelector('p')?.textContent?.trim()).toBe('Saved, uploadable flight plans are coming.');
  });

  it('renders no next-step link when nearestTo is unset — never a fabricated relation', () => {
    const fixture = render();
    expect(fixture.nativeElement.querySelector('.card.empty a')).toBeNull();
  });

  it('renders exactly one real next-step link when nearestTo/nearestLabel are both set', () => {
    const fixture = render();
    fixture.componentRef.setInput('nearestTo', '/fly');
    fixture.componentRef.setInput('nearestLabel', 'Open the cockpit');
    fixture.detectChanges();

    const links = fixture.nativeElement.querySelectorAll('.card.empty a');
    expect(links.length).toBe(1);
    expect(links[0].getAttribute('href')).toBe('/fly');
    expect(links[0].textContent?.trim()).toBe('Open the cockpit');
  });
});
