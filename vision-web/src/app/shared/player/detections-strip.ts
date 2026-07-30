import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { DetectionsStore } from '../../core/detections/detections-store';

/**
 * The compact detections strip (docs/MVP1-PLAN.md §C8 bullet 4): the last ~8 distinct labels seen
 * with their confidence, plus a subtle "CV" status dot.
 *
 * Purely presentational: injects the same `DetectionsStore` instance from its host page's DI (the
 * host lists it in its own `providers`, mirroring `TelemetryOsd`/`TelemetryStore`) rather than
 * polling independently. No inputs, no state of its own.
 *
 * Boxes themselves are never drawn here — they are burned into the frame server-side
 * (`Java2DOverlayRenderer`); this strip only lists what is currently being seen.
 *
 * Moved here from `pages/live/` (docs/MVP3-PLAN.md §C-b) when the Fly cockpit needed the identical
 * strip — this codebase has no precedent for one page importing another page's module (see
 * `core/fleet/device-logic.ts`'s doc comment for the original precedent this follows, most recently
 * repeated by `shared/map/live-map.ts`'s own move). `features/live/live.ts` now imports it from here; nothing
 * about its behavior changed.
 */
@Component({
  selector: 'vision-detections-strip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="strip">
      <span
        class="cv-dot"
        [class.on]="store.status() === 'on'"
        [attr.aria-label]="'CV ' + store.status()"
        [title]="'CV ' + store.status()"
      ></span>
      @if (store.chips().length > 0) {
        @for (chip of store.chips(); track chip.label) {
          <span class="chip">{{ chip.label }} · {{ chip.confidence.toFixed(2) }}</span>
        }
      } @else {
        <span class="muted hint">No detections yet.</span>
      }
    </div>
  `,
  styles: `
    .strip {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-8);
      padding: var(--space-8) var(--space-16);
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
    }

    .cv-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--text-faint);
      flex: none;
    }

    .cv-dot.on {
      background: var(--color-success);
    }
  `,
})
export class DetectionsStrip {
  protected readonly store = inject(DetectionsStore);
}
