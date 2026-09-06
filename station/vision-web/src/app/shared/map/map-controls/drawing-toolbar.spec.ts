import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { DrawingToolbar } from './drawing-toolbar';
import { DrawingsStore } from '../../../core/map-data/drawings-store';
import { LayersStore } from '../../../core/map-data/layers-store';
import { MarksStore } from '../../../core/map-data/marks-store';
import { DRAWING_COLOR_TOKENS } from '../../../core/map-data/drawings-logic';
import type { MapDrawingResponse } from '../../../core/api/models';

/**
 * BUG 2 regression coverage (drawing-toolbar half): `DrawingsStore.selected` is a `computed()` that
 * `.find()`s the currently-selected drawing out of `drawingsSignal()` — a list `DrawingsStore` replaces
 * wholesale on every SSE `map` event and 30s safety-net poll (`drawings-store.ts`), so `selected()`
 * hands back a brand-new object on every refresh even when the same drawing is still selected. `editLabel`
 * used to re-seed straight off `selected()?.label`, silently discarding an in-progress label edit on the
 * next refresh. The fix keys the re-seed on `DrawingsStore.selectedDrawingId` (already a primitive id
 * signal) instead. Mirrors `mark-palette.spec.ts`'s identical two cases.
 */
function drawing(overrides: Partial<MapDrawingResponse> = {}): MapDrawingResponse {
  return {
    drawingId: 'd1',
    layerId: 'layer-a',
    kind: 'LINE',
    label: 'Original label',
    colorToken: 'accent',
    points: [
      { latitude: 50.45, longitude: 30.52 },
      { latitude: 50.46, longitude: 30.53 },
    ],
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    ...overrides,
  };
}

function stubDrawingsStore(initial: MapDrawingResponse) {
  const selectedId = signal<string | undefined>(initial.drawingId);
  const selectedDrawing = signal<MapDrawingResponse | undefined>(initial);
  return {
    mode: signal(null).asReadonly(),
    colorTokens: DRAWING_COLOR_TOKENS,
    colorToken: signal(DRAWING_COLOR_TOKENS[0].token).asReadonly(),
    selectedDrawingId: selectedId.asReadonly(),
    selected: selectedDrawing.asReadonly(),
    canEditSelected: signal(true).asReadonly(),
    setMode: vi.fn(),
    stopDrawing: vi.fn(),
    setColorToken: vi.fn(),
    setDetails: vi.fn().mockResolvedValue(true),
    remove: vi.fn().mockResolvedValue(true),
    deselect: vi.fn(),
    /** Test-only helper: swaps in a fresh object for the *same* drawing, mimicking a poll refresh. */
    __refreshSameSelection: (next: MapDrawingResponse) => selectedDrawing.set(next),
    /** Test-only helper: mimics the host selecting a genuinely different drawing. */
    __selectDifferent: (next: MapDrawingResponse) => {
      selectedId.set(next.drawingId);
      selectedDrawing.set(next);
    },
    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3 — `DrawingToolbar`'s constructor now calls these directly.
    activate: vi.fn(),
    release: vi.fn(),
  };
}

function stubLayersStore() {
  return {
    loaded: signal(true).asReadonly(),
    contributable: signal([]).asReadonly(),
    activate: vi.fn(),
    release: vi.fn(),
  };
}

async function create(initial: MapDrawingResponse) {
  const drawingsStore = stubDrawingsStore(initial);
  TestBed.configureTestingModule({
    providers: [
      { provide: DrawingsStore, useValue: drawingsStore },
      { provide: LayersStore, useValue: stubLayersStore() },
      { provide: MarksStore, useValue: { disarm: vi.fn(), activate: vi.fn(), release: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(DrawingToolbar);
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, drawingsStore };
}

function labelInput(fixture: Awaited<ReturnType<typeof create>>['fixture']): HTMLInputElement {
  return fixture.nativeElement.querySelector('input[aria-label="Drawing label"]');
}

describe('DrawingToolbar editLabel — re-seed on identity, not on every refresh', () => {
  it('keeps an in-progress label edit across a refresh that hands in a new object for the same selection', async () => {
    const { fixture, drawingsStore } = await create(drawing());
    const input = labelInput(fixture);
    expect(input.value).toBe('Original label');

    input.value = 'Operator is mid-edit';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.value).toBe('Operator is mid-edit');

    // A poll/SSE refresh replaces the whole drawings list — same selection, same unsaved server-side
    // label, but a brand-new object reference for `selected()`.
    drawingsStore.__refreshSameSelection(drawing({ label: 'Original label' }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(labelInput(fixture).value).toBe('Operator is mid-edit');
  });

  it('still re-seeds once a genuinely different drawing is selected', async () => {
    const { fixture, drawingsStore } = await create(drawing({ drawingId: 'd1', label: 'First drawing' }));
    const input = labelInput(fixture);

    input.value = 'Unsaved edit on d1';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.value).toBe('Unsaved edit on d1');

    drawingsStore.__selectDifferent(drawing({ drawingId: 'd2', label: 'Second drawing' }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(labelInput(fixture).value).toBe('Second drawing');
  });
});
