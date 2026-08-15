import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Notice } from './notice';

@Component({
  selector: 'vision-test-notice-host',
  imports: [Notice],
  template: `<vision-notice variant="warn"><strong>GPS degraded</strong></vision-notice>`,
})
class HostHarness {}

describe('Notice', () => {
  it('defaults to the neutral variant with role=status and no intent class', () => {
    const fixture = TestBed.createComponent(Notice);
    fixture.detectChanges();

    const notice = fixture.nativeElement.querySelector('.notice') as HTMLElement;
    expect(notice.classList.contains('warn')).toBe(false);
    expect(notice.classList.contains('danger')).toBe(false);
    expect(notice.classList.contains('ok')).toBe(false);
    expect(notice.getAttribute('role')).toBe('status');
  });

  it('applies the intent class for the given variant', () => {
    const fixture = TestBed.createComponent(Notice);
    fixture.componentRef.setInput('variant', 'ok');
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.notice') as HTMLElement).classList.contains('ok')).toBe(true);
  });

  it('announces the danger variant assertively via role=alert', () => {
    const fixture = TestBed.createComponent(Notice);
    fixture.componentRef.setInput('variant', 'danger');
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.notice') as HTMLElement).getAttribute('role')).toBe('alert');
  });

  it('projects the message markup and reflects the variant class from a host', () => {
    const fixture = TestBed.createComponent(HostHarness);
    fixture.detectChanges();
    const notice = fixture.nativeElement.querySelector('.notice') as HTMLElement;
    expect(notice.classList.contains('warn')).toBe(true);
    expect(notice.querySelector('.notice-body strong')?.textContent?.trim()).toBe('GPS degraded');
  });
});
