import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { EmptyState } from './empty-state';

@Component({
  selector: 'vision-test-empty-host',
  imports: [EmptyState],
  template: `
    <vision-empty title="No devices yet" message="Attach one to get started.">
      <button type="button" class="btn">Attach device</button>
    </vision-empty>
  `,
})
class HostHarness {}

describe('EmptyState', () => {
  it('renders the required title as an <h3>, with no message by default', () => {
    const fixture = TestBed.createComponent(EmptyState);
    fixture.componentRef.setInput('title', 'Nothing here');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.empty h3')?.textContent?.trim()).toBe('Nothing here');
    expect(fixture.nativeElement.querySelector('.empty p')).toBeNull();
  });

  it('renders an optional message when provided', () => {
    const fixture = TestBed.createComponent(EmptyState);
    fixture.componentRef.setInput('title', 'Nothing here');
    fixture.componentRef.setInput('message', 'Try widening your filters.');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty p')?.textContent?.trim()).toBe('Try widening your filters.');
  });

  it('projects a call-to-action below the message', () => {
    const fixture = TestBed.createComponent(HostHarness);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty .btn')?.textContent?.trim()).toBe('Attach device');
  });
});
