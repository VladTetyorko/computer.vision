import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SwitchGauge } from './switch-gauge';

function render(positions: readonly ('LOW' | 'MIDDLE' | 'HIGH')[]) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(SwitchGauge);
  fixture.componentRef.setInput('positions', positions);
  fixture.detectChanges();
  return fixture;
}

describe('SwitchGauge', () => {
  it('draws one cell per position, with a button (single position) round', () => {
    const fixture = render(['HIGH']);
    const cells = fixture.nativeElement.querySelectorAll('.sg-cell');
    expect(cells.length).toBe(1);
    expect(cells[0].classList.contains('round')).toBe(true);
  });

  it('draws two cells for a 2-position switch, three for a 3-position switch, neither round', () => {
    const two = render(['LOW', 'HIGH']);
    expect(two.nativeElement.querySelectorAll('.sg-cell').length).toBe(2);
    expect(two.nativeElement.querySelector('.sg-cell.round')).toBeNull();

    const three = render(['LOW', 'MIDDLE', 'HIGH']);
    const cells = three.nativeElement.querySelectorAll('.sg-cell');
    expect(cells.length).toBe(3);
    expect(three.nativeElement.querySelector('.sg-cell.round')).toBeNull();
  });

  it('lights exactly the cell matching the active position', () => {
    const fixture = render(['LOW', 'MIDDLE', 'HIGH']);
    fixture.componentRef.setInput('active', 'MIDDLE');
    fixture.detectChanges();

    const cells = fixture.nativeElement.querySelectorAll('.sg-cell');
    expect(Array.from(cells).map((c) => (c as HTMLElement).classList.contains('lit'))).toEqual([false, true, false]);
  });

  it('lights no cell when active is undefined', () => {
    const fixture = render(['LOW', 'HIGH']);
    expect(fixture.nativeElement.querySelector('.sg-cell.lit')).toBeNull();
  });

  it('renders the hold fill only inside the active cell while holding', () => {
    const fixture = render(['LOW', 'HIGH']);
    fixture.componentRef.setInput('active', 'HIGH');
    fixture.componentRef.setInput('holding', true);
    fixture.detectChanges();

    const cells = fixture.nativeElement.querySelectorAll('.sg-cell');
    expect(cells[0].querySelector('.sg-fill')).toBeNull();
    expect(cells[1].querySelector('.sg-fill')).not.toBeNull();
  });

  it('renders no hold fill when not holding, even on the active cell', () => {
    const fixture = render(['LOW', 'HIGH']);
    fixture.componentRef.setInput('active', 'HIGH');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.sg-fill')).toBeNull();
  });
});
