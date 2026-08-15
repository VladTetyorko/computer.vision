import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Stat } from './stat';

describe('Stat', () => {
  it('renders the label caption and value, defaulting to neutral tone with no sub/live', () => {
    const fixture = TestBed.createComponent(Stat);
    fixture.componentRef.setInput('label', 'Total flights');
    fixture.componentRef.setInput('value', 42);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.stat-label')?.textContent?.trim()).toBe('Total flights');
    expect(fixture.nativeElement.querySelector('.stat-value')?.textContent?.trim()).toBe('42');
    expect(fixture.nativeElement.querySelector('.stat-sub')).toBeNull();
    expect(fixture.nativeElement.querySelector('.stat-value .dot.live')).toBeNull();
    expect((fixture.nativeElement.querySelector('.stat-tile') as HTMLElement).classList.contains('tone-default')).toBe(true);
  });

  it('renders an optional sub caption and the live dot when requested', () => {
    const fixture = TestBed.createComponent(Stat);
    fixture.componentRef.setInput('label', 'Streaming');
    fixture.componentRef.setInput('value', '3');
    fixture.componentRef.setInput('sub', 'last 30 days');
    fixture.componentRef.setInput('live', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.stat-sub')?.textContent?.trim()).toBe('last 30 days');
    expect(fixture.nativeElement.querySelector('.stat-value .dot.live')).not.toBeNull();
  });

  it('applies the requested tone class', () => {
    const fixture = TestBed.createComponent(Stat);
    fixture.componentRef.setInput('label', 'Offline');
    fixture.componentRef.setInput('value', 1);
    fixture.componentRef.setInput('tone', 'danger');
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.stat-tile') as HTMLElement).classList.contains('tone-danger')).toBe(true);
  });
});
