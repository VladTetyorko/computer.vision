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
import { IconButton } from '../../shared/ui/icon-button';
import { FollowHud } from '../../shared/player/follow-hud/follow-hud';
import { DetectionsFacade } from '../../core/detections/detections-facade';
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
 * **Video is opt-in, not just off-screen-suspended** (ALWAYS-ON-FLOW-PLAN.md §4 Wave C1/C2): before
 * this wave every tile mounted a `<vision-player>` unconditionally and only `IntersectionObserver`
 * decided whether it decoded — which does nothing for a grid that fits on one screen, i.e. every wall
 * an operator actually watches. {@link videoUp} (owned by `WallFacade.isVideoUp`, capped wall-wide —
 * see `wall-logic.ts#requestWallVideo`) now gates whether a player exists **at all**; `false` renders
 * `wall-tile.html`'s state-only placeholder instead, identity/severity/battery/telemetry-age chrome
 * unchanged either way. `@if`, not a `[suspended]` toggle: `player.ts` already tears down WebRTC/HLS
 * decode fully on `[suspended]`, but still runs its own RAF loop and `ResizeObserver` while merely
 * suspended — a tile with video-off has no player instance at all, not a suspended one. `(videoToggled)`
 * is the explicit per-tile gesture that flips it — never anything driven by `tile().severity` (see
 * `requestWallVideo`'s own doc comment for why `critical` deliberately does not auto-raise this).
 * `DetectionsStore.track`/`followTracks` below now also require {@link videoUp}: boxes drawn over a
 * picture nobody asked to see would be pure waste, and — the load-shedding half that matters more
 * than the pixels — every detections feed this tile can open is CV demand on the backend
 * (`StreamController:444`), so an unmounted player must not keep one warm either.
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
  imports: [Player, Icon, IconButton, FollowHud],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DetectionsFacade],
  templateUrl: './wall-tile.html',
  styleUrl: './wall-tile.css',
})
export class WallTile {
  readonly tile = input.required<WallTileModel>();
  readonly boxesMode = input.required<BoxesMode>();
  /** F2 crop-follow (per-viewer, facade-owned like {@link boxesMode}); echoed, never stored here. */
  readonly cropFollowEnabled = input<boolean>(false);
  readonly cropFollowEnabledChange = output<boolean>();

  /** Whether `WallFacade` currently counts this tile among its capped, raised set — see this class's
   *  own "Video is opt-in" doc section. Facade-owned, like {@link boxesMode}: this component never
   *  decides the cap or the eviction rule itself, it only renders whichever side of the toggle it's on. */
  readonly videoUp = input.required<boolean>();
  /** The tile's own "Show/Hide video" gesture — `WallFacade.toggleVideo(streamId)` on the other end. */
  readonly videoToggled = output<string>();

  readonly focused = output<string>();

  protected readonly visible = signal(true);
  protected readonly detections = inject(DetectionsFacade);

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

  /** The video-toggle button is a DOM sibling of `.tile` (see `wall-tile.html`'s own comment,
   *  mirroring `<vision-follow-hud>`'s identical reason) so it never fires `onActivate` above. */
  protected onToggleVideo(): void {
    this.videoToggled.emit(this.tile().streamId);
  }

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement;
    const observer = new IntersectionObserver(
      (entries) => this.visible.set(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: PREROLL_MARGIN },
    );
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());

    // On-screen AND video-up only (§4 Wave C1/C2 — this class's own "Video is opt-in" doc section):
    // off-screen tiles already stopped decoding video before this wave; now a tile that is on-screen
    // but has no player mounted at all (the new default) must stop just as completely, since there is
    // no picture for a detection box to draw over and no reason to keep the backend's CV demand warm
    // for it. `assetId` is passed whenever the tile has one, so an on-wall asset with `LiveStore`
    // already open gets the live SSE transport for free (D4).
    effect(() => {
      if (this.visible() && this.videoUp()) {
        this.detections.track(this.tile().streamId, this.tile().assetId);
      } else {
        this.detections.reset();
      }
    });

    // Follow's own tracks poll (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5, wave W5) — a fully
    // independent lifecycle from `track()`/`reset()` above (`DetectionsStore`'s own doc comment), so
    // it needs its own on/off-screen-and-video-up gate rather than inheriting the effect above's.
    // Off-screen or video-down always wins, matching the box-overlay feed's identical teardown.
    effect(() => {
      this.detections.followTracks(this.tile().streamId, this.visible() && this.videoUp() && this.wantsTracksPoll());
    });
  }
}
