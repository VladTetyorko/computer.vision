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
import { FollowHud } from '../../shared/player/follow-hud/follow-hud';
import { DetectionsStore } from '../../core/detections/detections-store';
import type { FollowStatus } from '../../core/api/models';
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
 *
 * **Follow, read-only** (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.3, wave W5; WALL-FLOW-PLAN §5 —
 * "the Wall is a watch surface", no per-tile control surface): a lock elsewhere (Fly, `/live`) is
 * peripheral awareness here, same as everything else this tile shows at rest. {@link lockedTrackId}
 * rides the per-frame detections feed this tile's own `DetectionsStore` already polls/subscribes to
 * for its box overlay — free, no new request. {@link follow} needs the richer `GET .../tracks` poll,
 * gated on {@link wantsTracksPoll}'s own tight formula so the overwhelming majority of tiles (no
 * lock ever issued) add **zero** extra requests; only a tile actively holding (or just having lost) a
 * lock pays for it. `<vision-follow-hud>` is mounted as a DOM **sibling** of `.tile`, never a
 * descendant (`wall-tile.html`'s own comment) — `canRelease=false` and neither `(release)` nor
 * `(reacquire)` is bound, so this mount can never send a write.
 */
@Component({
  selector: 'vision-wall-tile',
  imports: [Player, Icon, FollowHud],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DetectionsStore],
  templateUrl: './wall-tile.html',
  styleUrl: './wall-tile.css',
})
export class WallTile {
  readonly tile = input.required<WallTileModel>();
  readonly boxesMode = input.required<BoxesMode>();
  /** F2 crop-follow (per-viewer, facade-owned like {@link boxesMode}); echoed, never stored here. */
  readonly cropFollowEnabled = input<boolean>(false);
  readonly cropFollowEnabledChange = output<boolean>();

  readonly focused = output<string>();

  protected readonly visible = signal(true);
  protected readonly detections = inject(DetectionsStore);

  protected readonly reasonsTooltip = computed(() => this.tile().reasons.map((reason) => reason.text).join(' '));

  /** The FOLLOW-locked track id, read from the per-frame detections feed — mirrors
   *  `CockpitFacade#lockedTrackId`/`LiveFacade#lockedTrackId`. `0` = no lock known. Not read by this
   *  component's own template directly today (`<vision-player>` here has never bound
   *  `lockedTrackId`/tier highlighting — out of this wave's scope), but is what {@link wantsTracksPoll}
   *  gates on, at zero extra cost since the feed already runs whenever {@link visible}. */
  protected readonly lockedTrackId = computed(() => this.detections.results()[0]?.tracking?.lockedTrackId ?? 0);

  /** The follow lock's own lifecycle, mirrored 1:1 from `GET .../tracks`' trailing `follow` object —
   *  `null` before any lock has ever been issued, after a `RELEASED` read, on any tracks-poll
   *  transport failure, or simply because {@link wantsTracksPoll} has never turned the poll on for
   *  this tile. Feeds `<vision-follow-hud>` (`wall-tile.html`). */
  protected readonly follow = computed<FollowStatus | null>(() => this.detections.tracks()?.follow ?? null);

  /**
   * Whether this tile's own `GET .../tracks` poll should run (docs/plans/active/TRACK-FOLLOW-PLAN.md
   * §3.5) — the **tightest** form of the formula in this plan: unlike `CockpitFacade`'s "drawer open"
   * clause or `LiveFacade`'s "merely live" clause, a Wall tile has no narrower "operator plausibly
   * cares about Vision right now" signal *and* Wall can show dozens of tiles at once, so a broader
   * gate would mean dozens of needless extra 2s polls. `true` only once a lock has actually been
   * observed (the free per-frame fact) or the last poll read `LOST` — every tile nobody has ever
   * followed costs nothing extra, ever.
   */
  private readonly wantsTracksPoll = computed(() => this.lockedTrackId() !== 0 || this.follow()?.state === 'LOST');

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

    // Follow's own tracks poll (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5, wave W5) — a fully
    // independent lifecycle from `track()`/`reset()` above (`DetectionsStore`'s own doc comment), so
    // it needs its own on/off-screen gate rather than inheriting the effect above's. Off-screen
    // always wins (`this.visible() && …`), matching the box-overlay feed's identical off-screen
    // teardown.
    effect(() => {
      this.detections.followTracks(this.tile().streamId, this.visible() && this.wantsTracksPoll());
    });
  }
}
