import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TileGrid } from './tile-grid';

@Component({
  selector: 'vision-test-tile-grid-host',
  imports: [TileGrid],
  template: `
    <vision-tile-grid>
      <a>One</a>
      <a>Two</a>
    </vision-tile-grid>
  `,
})
class HostHarness {}

describe('TileGrid', () => {
  it('is a pure layout wrapper that projects its content into .tile-grid', () => {
    TestBed.configureTestingModule({ imports: [HostHarness] });
    const fixture = TestBed.createComponent(HostHarness);
    fixture.detectChanges();

    const grid = fixture.nativeElement.querySelector('.tile-grid') as HTMLElement;
    expect(grid.children.length).toBe(2);
    expect(grid.textContent?.trim()).toBe('OneTwo');
  });
});
