import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { CvSubnav, type CvSubnavTree } from './cv-subnav';

@Component({
  selector: 'vision-test-cv-subnav-host',
  imports: [CvSubnav],
  template: `<vision-cv-subnav [active]="active" />`,
})
class HostHarness {
  active: CvSubnavTree = 'models';
}

function render(active: CvSubnavTree) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports: [HostHarness], providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(HostHarness);
  fixture.componentInstance.active = active;
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('CvSubnav', () => {
  it('renders exactly the three tree links, in order', () => {
    const el = render('models');
    const labels = Array.from(el.querySelectorAll('a[role="tab"]')).map((a) => a.textContent?.trim());
    expect(labels).toEqual(['Models', 'Labeling', 'Training']);
  });

  it('marks only the active tree — models', () => {
    const el = render('models');
    const tabs = Array.from(el.querySelectorAll('a[role="tab"]'));
    expect(tabs.map((t) => t.classList.contains('active'))).toEqual([true, false, false]);
    expect(tabs.map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
  });

  it('marks only the active tree — labeling', () => {
    const el = render('labeling');
    const tabs = Array.from(el.querySelectorAll('a[role="tab"]'));
    expect(tabs.map((t) => t.classList.contains('active'))).toEqual([false, true, false]);
  });

  it('marks only the active tree — training', () => {
    const el = render('training');
    const tabs = Array.from(el.querySelectorAll('a[role="tab"]'));
    expect(tabs.map((t) => t.classList.contains('active'))).toEqual([false, false, true]);
  });

  it('points each link at its own tree root', () => {
    const el = render('models');
    const hrefs = Array.from(el.querySelectorAll('a[role="tab"]')).map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(['/manage/training/models', '/manage/training', '/manage/training/runs']);
  });
});
