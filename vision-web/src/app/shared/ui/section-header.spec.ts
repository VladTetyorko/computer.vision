import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SectionHeader } from './section-header';

@Component({
  selector: 'vision-test-section-header-host',
  imports: [SectionHeader],
  template: `
    <vision-section-header title="Hardware & devices">
      <button type="button" actions>Attach device</button>
    </vision-section-header>
  `,
})
class HostHarness {}

describe('SectionHeader', () => {
  it('renders the required title as an <h2>, with no eyebrow/subtitle by default', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(SectionHeader);
    fixture.componentRef.setInput('title', 'Full telemetry');
    fixture.detectChanges();

    const h2 = fixture.nativeElement.querySelector('h2') as HTMLElement;
    expect(h2.textContent?.trim()).toBe('Full telemetry');
    expect(fixture.nativeElement.querySelector('.label')).toBeNull();
    expect(fixture.nativeElement.querySelector('p.muted')).toBeNull();
  });

  it('renders an optional eyebrow and subtitle when provided', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(SectionHeader);
    fixture.componentRef.setInput('title', 'Usage history');
    fixture.componentRef.setInput('eyebrow', 'Drill-in');
    fixture.componentRef.setInput('subtitle', 'Every finished flight for this asset.');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.label')?.textContent?.trim()).toBe('Drill-in');
    expect(fixture.nativeElement.querySelector('p.muted')?.textContent?.trim()).toBe('Every finished flight for this asset.');
  });

  it('projects [actions] content right-aligned inside .section-head-actions', () => {
    TestBed.configureTestingModule({ imports: [HostHarness] });
    const fixture = TestBed.createComponent(HostHarness);
    fixture.detectChanges();

    const actions = fixture.nativeElement.querySelector('.section-head-actions') as HTMLElement;
    expect(actions.querySelector('button')?.textContent?.trim()).toBe('Attach device');
  });
});
