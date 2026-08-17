import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { MarkPalette } from './mark-palette';
import { MarksStore } from '../../../core/map-data/marks-store';
import { LayersStore } from '../../../core/map-data/layers-store';
import { DrawingsStore } from '../../../core/map-data/drawings-store';
import type { MapMark } from '../../../core/api/models';

/**
 * BUG 2 regression coverage: `MarksStore` replaces its whole `marks` list with brand-new objects on
 * every SSE `map` event and on its 30s safety-net poll (`marks-store.ts`), so `[mark]` receives a new
 * object reference on every refresh even when the mark itself hasn't changed. `editPalette`/
 * `editLabel`/`editNote` used to re-seed straight off `mark()`, which silently discarded an operator's
 * in-progress edit on the very next refresh. The fix re-seeds only on `mark()?.markId` — this file
 * proves both halves: an edit survives an unrelated refresh, and a genuine mark switch still re-seeds.
 * The rest of the component (palette axes, create flow, save/cancel) has no dedicated spec, matching
 * this codebase's precedent of favoring pure-logic vitest over component specs for the map controls —
 * this file exists specifically because the bug is a reactivity wiring bug, not a pure-function one.
 */
function mark(overrides: Partial<MapMark> = {}): MapMark {
  return {
    markId: 'm1',
    layerId: 'layer-a',
    latitude: 50.45,
    longitude: 30.52,
    kind: 'TARGET',
    affiliation: 'HOSTILE',
    label: 'Original label',
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    verification: 'UNVERIFIED',
    ...overrides,
  };
}

function stubMarksStore() {
  return {
    palette: signal({ kind: 'TARGET', affiliation: 'HOSTILE' }).asReadonly(),
    draft: signal(null).asReadonly(),
    armed: signal(false).asReadonly(),
    setKind: vi.fn(),
    setAffiliation: vi.fn(),
    setLayer: vi.fn(),
    arm: vi.fn(),
    disarm: vi.fn(),
    cancelDraft: vi.fn(),
    annotate: vi.fn().mockResolvedValue(true),
    confirmDraft: vi.fn().mockResolvedValue(true),
  };
}

function stubLayersStore() {
  // `loaded: true` + `contributable: []` renders the "lands on your default layer" hint instead of
  // the `<select>` — irrelevant to this bug, kept minimal on purpose.
  return {
    loaded: signal(true).asReadonly(),
    contributable: signal([]).asReadonly(),
  };
}

function stubDrawingsStore() {
  return { stopDrawing: vi.fn() };
}

function create() {
  TestBed.configureTestingModule({
    providers: [
      { provide: MarksStore, useValue: stubMarksStore() },
      { provide: LayersStore, useValue: stubLayersStore() },
      { provide: DrawingsStore, useValue: stubDrawingsStore() },
    ],
  });
  return TestBed.createComponent(MarkPalette);
}

function labelInput(fixture: ReturnType<typeof create>): HTMLInputElement {
  return fixture.nativeElement.querySelector('input[aria-labelledby="mark-label"]');
}

describe('MarkPalette edit mode — re-seed on identity, not on every refresh', () => {
  it('keeps an in-progress label edit across a refresh that hands in a new object for the same mark', async () => {
    const fixture = create();
    fixture.componentRef.setInput('mark', mark());
    fixture.detectChanges();
    await fixture.whenStable();

    const input = labelInput(fixture);
    expect(input.value).toBe('Original label');

    input.value = 'Operator is mid-edit';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.value).toBe('Operator is mid-edit');

    // A poll/SSE refresh replaces the whole marks list — same mark, same unsaved server-side label,
    // but a brand-new object reference. The in-progress edit must not be wiped by this.
    fixture.componentRef.setInput('mark', mark({ label: 'Original label' }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(labelInput(fixture).value).toBe('Operator is mid-edit');
  });

  it('still re-seeds once the host points the component at a genuinely different mark', async () => {
    const fixture = create();
    fixture.componentRef.setInput('mark', mark({ markId: 'm1', label: 'First mark' }));
    fixture.detectChanges();
    await fixture.whenStable();

    const input = labelInput(fixture);
    input.value = 'Unsaved edit on m1';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.value).toBe('Unsaved edit on m1');

    fixture.componentRef.setInput('mark', mark({ markId: 'm2', label: 'Second mark' }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(labelInput(fixture).value).toBe('Second mark');
  });
});
