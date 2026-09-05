import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { StepRail, type StepRailItem } from './step-rail';

function items(overrides: readonly Partial<StepRailItem>[]): readonly StepRailItem[] {
  return overrides.map((partial, i) => ({ id: `s${i}`, label: `Step ${i}`, done: false, ...partial }));
}

function render(rows: readonly StepRailItem[], currentIndex: number) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(StepRail);
  fixture.componentRef.setInput('items', rows);
  fixture.componentRef.setInput('currentIndex', currentIndex);
  fixture.detectChanges();
  return fixture;
}

describe('StepRail', () => {
  it('renders one row per item, in order, using its label', () => {
    const fixture = render(items([{ label: 'Source' }, { label: 'Prove' }, { label: 'Identify' }]), 0);
    const titles = Array.from(fixture.nativeElement.querySelectorAll('.rail-title')).map(
      (el) => (el as HTMLElement).textContent,
    );
    expect(titles).toEqual(['Source', 'Prove', 'Identify']);
  });

  it('marks a row done exactly when the caller says so, current exactly at currentIndex', () => {
    const fixture = render(items([{ done: true }, {}, {}]), 1);
    const rows = Array.from(fixture.nativeElement.querySelectorAll('.rail-step')) as HTMLElement[];
    expect(rows[0].classList.contains('done')).toBe(true);
    expect(rows[0].classList.contains('current')).toBe(false);
    expect(rows[1].classList.contains('current')).toBe(true);
    expect(rows[1].classList.contains('done')).toBe(false);
    expect(rows[2].classList.contains('done')).toBe(false);
    expect(rows[2].classList.contains('current')).toBe(false);
  });

  it('renders a row that is both done and current with both classes — an already-bound step reopened', () => {
    const fixture = render(items([{ done: true }, {}]), 0);
    const rows = Array.from(fixture.nativeElement.querySelectorAll('.rail-step')) as HTMLElement[];
    expect(rows[0].classList.contains('done')).toBe(true);
    expect(rows[0].classList.contains('current')).toBe(true);
  });

  it('sets aria-current="step" only on the current row', () => {
    const fixture = render(items([{ done: true }, {}]), 1);
    const rows = Array.from(fixture.nativeElement.querySelectorAll('.rail-step')) as HTMLElement[];
    expect(rows[0].getAttribute('aria-current')).toBeNull();
    expect(rows[1].getAttribute('aria-current')).toBe('step');
  });

  it('emits the clicked row index so the page can jump there', () => {
    const fixture = render(items([{}, {}, {}, {}]), 0);
    let jumped: number | undefined;
    fixture.componentInstance.jump.subscribe((i) => (jumped = i));

    (fixture.nativeElement.querySelectorAll('.rail-step')[3] as HTMLElement).click();

    expect(jumped).toBe(3);
  });
});
