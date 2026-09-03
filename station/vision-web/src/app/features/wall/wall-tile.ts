import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { Player, type BoxesMode } from '../../shared/player/player';
import { Icon } from '../../shared/ui/icon';
import { DetectionsStore } from '../../core/detections/detections-store';
import type { WallTileModel } from './wall-logic';

/** Start decoding slightly before a tile scrolls into view, so it is ready on arrival. */
const PREROLL_MARGIN = '250px';

/**
 * One live tile (docs/plans/active/WALL-FLOW-PLAN.md wave W2, frozen I/O `§3.4`) — one input
 * `[tile]: WallTileModel`, one input `[boxesMode]: BoxesMode` (the wall-level declutter control,
 * `WallFacade#boxesMode`, bound identically by every tile), one output `(focused): string`
 * (`streamId`) — the tile *is* the click target for drill-in (D9/D10); there is no separate
 * "Watch live" link, boxes-cycle button, glyph chips or altitude readout any more (D5–D10), and no
 * per-tile `TelemetryStore` — `WallTile`'s identity/battery/telemetry-age/mode/armed/failsafe all
 * arrive pre-derived on `tile()` from `WallFacade`'s one fleet-summary join (D4/D5).
 *
 * **At rest** (L2 — "identity + at most ONE state indicator"): the picture, the title, and — only
 * when `tile().healthLabel` is non-null — one HUD line. Severity reads as a border tint + a
 * `.dot`; `pulse` reads as a brief border flash (skipped under `prefers-reduced-motion`, the chip
 * alone stays) plus one label chip. The footer (battery, telemetry age, "Not linked to an asset")
 * is hover/focus-within only — see `wall-tile.css`.
 *
 * Still suspends its player while off-screen (`IntersectionObserver`, unchanged from the pre-W2
 * tile — docs/plans/done/WEB-PLAN.md W6) and still runs its own `DetectionsStore`, now calling
 * `track(streamId, assetId)`: per-frame detection boxes cannot come from a summary poll, but
 * passing `assetId` (when the tile has one) lets it use the free asset-scoped live SSE transport
 * instead of a poll (D4's other half).
 */
@Component({
  selector: 'vision-wall-tile',
  imports: [Player, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DetectionsStore],
  templateUrl: './wall-tile.html',
  styleUrl: './wall-tile.css',
})
export class WallTile {
  readonly tile = input.required<WallTileModel>();
  readonly boxesMode = input.required<BoxesMode>();

  readonly focused = output<string>();

  protected readonly visible = signal(true);
  protected readonly detections = inject(DetectionsStore);

  protected readonly reasonsTooltip = computed(() => this.tile().reasons.map((reason) => reason.text).join(' '));

  protected onActivate(): void {
    this.focused.emit(this.tile().streamId);
  }

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement;
    const observer = new IntersectionObserver(
      (entries) => this.visible.set(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: PREROLL_MARGIN },
    );
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());

    // On-screen only (docs/main/CYCLES-PLAN.md §11 item 4/6) — off-screen tiles already stop
    // decoding video (above), so they stop polling/subscribing for detections too. `assetId` is
    // passed whenever the tile has one, so an on-wall asset with `LiveStore` already open gets the
    // live SSE transport for free (D4).
    effect(() => {
      if (this.visible()) {
        this.detections.track(this.tile().streamId, this.tile().assetId);
      } else {
        this.detections.reset();
      }
    });
  }
}
