import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  afterNextRender,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icon } from '../ui/icon';
import { IconButton } from '../ui/icon-button';
import { Notice } from '../ui/notice';
import type { Annotation } from '../../core/api/models';
import {
  boxToPx,
  clampBox,
  hitTest,
  letterboxRect,
  moveBox,
  normalizeDragRect,
  pxToBox,
  resizeBox,
  type HandleId,
  type HitTarget,
  type PxRect,
} from './sample-box-editor-logic';

/** Screen-px hit radius around a corner handle — deliberately larger than the drawn handle itself for touch-friendly targets (CLAUDE.md's own "large touch targets" rule). */
const HANDLE_HIT_PX = 12;
/** Below this drawn size (in either dimension), a drag-to-create gesture is treated as an accidental tap/click, not a new box. */
const MIN_CREATE_PX = 6;
/** A box can never be resized smaller than this on screen — keeps a corner drag from collapsing a box to nothing. */
const MIN_BOX_PX = 6;
/** The default size (as a fraction of the frame) for a box inserted via the "Add box" button, centered in the frame. */
const DEFAULT_BOX_FRACTION = 0.2;

type DragMode = 'move' | HandleId | 'create';

interface DragState {
  readonly mode: DragMode;
  readonly index: number; // -1 while drawing a new box
  readonly startPointerPx: { x: number; y: number };
  readonly startRectPx: PxRect;
}

/**
 * The editable annotation canvas for a training sample (docs/plans/done/CV-TRAINING-PLAN.md §4, Wave T5) — an
 * `<img>` of the captured frame with its `annotations` drawn as draggable/resizable boxes, plus a
 * side list for label (constrained to `classes`) and delete. A **fork** of the live detection
 * overlay's box-drawing idiom (`shared/player/player.ts`'s own `letterboxRect`/`DrawnBox`/`drawBox`),
 * not a shared import — see `sample-box-editor-logic.ts`'s own doc comment for why forking, not
 * reusing, was the right call here.
 *
 * **Controlled input, uncontrolled edit session**: `annotations` seeds the working copy once per
 * sample (the `effect()` below re-seeds whenever the input's identity changes — in practice, once
 * per navigation to a different sample; a parent never mutates it mid-edit); every drag/resize/
 * label/add/remove after that is owned locally and reported via {@link annotationsChange} on each
 * *committed* change (drag end, a label pick, add, remove) — not on every pointermove, so a parent
 * facade's own signal doesn't thrash on every animation frame of a drag.
 *
 * Colors follow the same fixed, categorical two-value encoding the rest of this app already uses for
 * detection boxes: `MODEL` (still-unreviewed suggestion) stays the exact blue every detection box has
 * always drawn (`--color-info`, the app's one accent hue); `OPERATOR` (drawn or corrected by hand)
 * uses `--color-success` — green doubling as "confirmed truth", not an invented hue. The selected box
 * gets the same amber ring `player.ts` already uses for a hovered detection.
 */
@Component({
  selector: 'vision-sample-box-editor',
  imports: [FormsModule, Icon, IconButton, Notice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sample-box-editor.html',
  styleUrl: './sample-box-editor.css',
})
export class SampleBoxEditor {
  readonly imageUrl = input.required<string>();
  readonly naturalWidth = input.required<number>();
  readonly naturalHeight = input.required<number>();
  readonly classes = input.required<readonly string[]>();
  readonly annotations = input.required<readonly Annotation[]>();
  readonly disabled = input<boolean>(false);

  /** Emitted after every committed edit — always the full, current annotation list. */
  readonly annotationsChange = output<readonly Annotation[]>();

  private readonly canvasRef = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');

  private readonly draftSignal = signal<readonly Annotation[]>([]);
  readonly draft = this.draftSignal.asReadonly();

  readonly selectedIndex = signal<number | null>(null);

  private drag: DragState | null = null;
  private creatingRect: PxRect | null = null;
  private canvasReady = false;

  constructor() {
    // Re-seeds the working copy whenever the *input* changes identity — see class doc's "controlled
    // input, uncontrolled edit session" note.
    effect(() => {
      this.draftSignal.set(this.annotations());
      this.selectedIndex.set(null);
      this.redraw();
    });

    afterNextRender(() => {
      this.canvasReady = true;
      this.redraw();
    });
  }

  @HostListener('window:resize')
  protected onWindowResize(): void {
    this.redraw();
  }

  protected onImageLoad(): void {
    this.redraw();
  }

  select(index: number): void {
    this.selectedIndex.set(index);
    this.redraw();
  }

  setLabel(index: number, label: string): void {
    if (this.disabled()) {
      return;
    }
    this.updateDraft((current) => current.map((ann, i) => (i === index ? { ...ann, label } : ann)));
  }

  remove(index: number): void {
    if (this.disabled()) {
      return;
    }
    this.updateDraft((current) => current.filter((_, i) => i !== index));
    if (this.selectedIndex() === index) {
      this.selectedIndex.set(null);
    }
  }

  /** Inserts a default centered box (docs/plans/done/CV-TRAINING-PLAN.md §4) — a precise, no-drag-skill-needed way to add a box the model missed entirely, still fully draggable/resizable afterward. */
  addBox(): void {
    if (this.disabled()) {
      return;
    }
    const label = this.classes()[0];
    if (label === undefined) {
      return;
    }
    const half = DEFAULT_BOX_FRACTION / 2;
    const box = { x: 0.5 - half, y: 0.5 - half, width: DEFAULT_BOX_FRACTION, height: DEFAULT_BOX_FRACTION };
    this.updateDraft((current) => [...current, { label, source: 'OPERATOR', box }]);
    this.selectedIndex.set(this.draftSignal().length - 1);
  }

  protected onPointerDown(event: PointerEvent): void {
    if (this.disabled()) {
      return;
    }
    const canvas = this.canvasRef().nativeElement;
    canvas.setPointerCapture(event.pointerId);
    const point = this.pointerToCanvasPx(event);
    const content = this.contentRect();
    const targets: HitTarget<number>[] = this.draftSignal().map((ann, index) => ({
      id: index,
      rect: boxToPx(ann.box, content),
    }));
    const hit = hitTest(targets, point, HANDLE_HIT_PX);

    if (hit) {
      this.selectedIndex.set(hit.id);
      this.drag = {
        mode: hit.handle,
        index: hit.id,
        startPointerPx: point,
        startRectPx: boxToPx(this.draftSignal()[hit.id].box, content),
      };
    } else {
      this.selectedIndex.set(null);
      this.drag = { mode: 'create', index: -1, startPointerPx: point, startRectPx: { x: point.x, y: point.y, width: 0, height: 0 } };
    }
    this.redraw();
  }

  protected onPointerMove(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag) {
      return;
    }
    const point = this.pointerToCanvasPx(event);
    const content = this.contentRect();
    const dx = point.x - drag.startPointerPx.x;
    const dy = point.y - drag.startPointerPx.y;

    if (drag.mode === 'create') {
      this.creatingRect = normalizeDragRect(drag.startPointerPx.x, drag.startPointerPx.y, point.x, point.y);
    } else if (drag.mode === 'move') {
      const rect = moveBox(drag.startRectPx, dx, dy, content);
      this.applyDragRect(drag.index, rect);
    } else {
      const rect = resizeBox(drag.startRectPx, drag.mode, dx, dy, MIN_BOX_PX, content);
      this.applyDragRect(drag.index, rect);
    }
    this.redraw();
  }

  protected onPointerUp(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag) {
      return;
    }
    const canvas = this.canvasRef().nativeElement;
    if (canvas.hasPointerCapture(event.pointerId)) {
      canvas.releasePointerCapture(event.pointerId);
    }
    if (drag.mode === 'create' && this.creatingRect) {
      const rect = this.creatingRect;
      if (rect.width >= MIN_CREATE_PX && rect.height >= MIN_CREATE_PX) {
        const label = this.classes()[0];
        if (label !== undefined) {
          const box = pxToBox(rect, this.contentRect());
          this.updateDraft((current) => [...current, { label, source: 'OPERATOR', box }]);
          this.selectedIndex.set(this.draftSignal().length - 1);
        }
      }
      this.creatingRect = null;
    } else {
      // A committed move/resize — report the final state up.
      this.annotationsChange.emit(this.draftSignal());
    }
    this.drag = null;
    this.redraw();
  }

  protected onPointerLeave(event: PointerEvent): void {
    // A pointer that leaves the canvas mid-drag without a proper pointerup (rare, but possible on
    // some touch/trackpad setups) still needs to commit or cancel — pointer capture normally keeps
    // move/resize events flowing even off-canvas, so this mainly guards the 'create' gesture.
    this.onPointerUp(event);
  }

  private applyDragRect(index: number, rectPx: PxRect): void {
    const box = clampBox(pxToBox(rectPx, this.contentRect()));
    this.draftSignal.update((current) => current.map((ann, i) => (i === index ? { ...ann, box } : ann)));
  }

  private updateDraft(mutate: (current: readonly Annotation[]) => readonly Annotation[]): void {
    const next = mutate(this.draftSignal());
    this.draftSignal.set(next);
    this.annotationsChange.emit(next);
    this.redraw();
  }

  private pointerToCanvasPx(event: PointerEvent): { x: number; y: number } {
    const canvas = this.canvasRef().nativeElement;
    const bounds = canvas.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
  }

  private contentRect(): PxRect {
    const canvas = this.canvasRef().nativeElement;
    return letterboxRect(canvas.clientWidth, canvas.clientHeight, this.naturalWidth(), this.naturalHeight());
  }

  private redraw(): void {
    if (!this.canvasReady) {
      return;
    }
    const canvas = this.canvasRef().nativeElement;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (width === 0 || height === 0) {
      return;
    }
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }
    ctx.clearRect(0, 0, width, height);

    const content = this.contentRect();
    const selected = this.selectedIndex();
    this.draftSignal().forEach((ann, index) => {
      this.drawBox(ctx, boxToPx(ann.box, content), ann, index === selected);
    });
    if (this.drag?.mode === 'create' && this.creatingRect) {
      ctx.setLineDash([4, 3]);
      ctx.lineWidth = 2;
      ctx.strokeStyle = '#ffd479';
      ctx.strokeRect(this.creatingRect.x, this.creatingRect.y, this.creatingRect.width, this.creatingRect.height);
      ctx.setLineDash([]);
    }
  }

  private drawBox(ctx: CanvasRenderingContext2D, rect: PxRect, ann: Annotation, selected: boolean): void {
    const color = selected ? '#ffd479' : ann.source === 'MODEL' ? '#4f8cff' : '#37c977';
    ctx.lineWidth = selected ? 3 : 2;
    ctx.strokeStyle = color;
    ctx.strokeRect(rect.x, rect.y, rect.width, rect.height);

    ctx.font = '11px ui-monospace, monospace';
    const label = ann.label || '(no label)';
    const metrics = ctx.measureText(label);
    const labelHeight = 14;
    ctx.fillStyle = color;
    ctx.fillRect(rect.x, Math.max(0, rect.y - labelHeight), metrics.width + 6, labelHeight);
    ctx.fillStyle = '#04101f';
    ctx.fillText(label, rect.x + 3, Math.max(labelHeight - 3, rect.y - 3));

    if (selected) {
      const handleSize = 6;
      ctx.fillStyle = color;
      for (const [hx, hy] of [
        [rect.x, rect.y],
        [rect.x + rect.width, rect.y],
        [rect.x, rect.y + rect.height],
        [rect.x + rect.width, rect.y + rect.height],
      ]) {
        ctx.fillRect(hx - handleSize / 2, hy - handleSize / 2, handleSize, handleSize);
      }
    }
  }
}
