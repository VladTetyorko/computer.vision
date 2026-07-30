import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { KebabMenu } from './kebab-menu';

@Component({
  selector: 'vision-test-kebab-host',
  imports: [KebabMenu],
  template: `
    <vision-kebab-menu label="More actions for camera 3">
      <button type="button">Details</button>
      <div class="kebab-divider" role="separator"></div>
      <button type="button" class="danger-action">Archive</button>
    </vision-kebab-menu>
  `,
})
class HostHarness {}

describe('KebabMenu', () => {
  it('exposes the label as the trigger aria-label', () => {
    const fixture = TestBed.createComponent(KebabMenu);
    fixture.componentRef.setInput('label', 'More actions for asset 7');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.kebab-trigger')?.getAttribute('aria-label')).toBe('More actions for asset 7');
  });

  it('projects menu entries and closes the disclosure when one is activated', () => {
    const fixture = TestBed.createComponent(HostHarness);
    fixture.detectChanges();

    const details = fixture.nativeElement.querySelector('details.kebab') as HTMLDetailsElement;
    details.open = true;
    fixture.detectChanges();

    const archive = fixture.nativeElement.querySelector('.danger-action') as HTMLButtonElement;
    expect(archive.textContent?.trim()).toBe('Archive');
    archive.click();
    fixture.detectChanges();

    expect(details.open).toBe(false);
  });
});
