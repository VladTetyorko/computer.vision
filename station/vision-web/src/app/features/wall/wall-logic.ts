import type { ActiveStream, AssetAttention, DetectionEvent, Device } from '../../core/api/models';
import {
  attentionAgeLabel,
  attentionReasons,
  batteryAttentionSeverity,
  type AttentionReason,
  type AttentionSeverity,
  type BatteryAttentionSeverity,
} from '../../core/fleet/attention-logic';
import type { GeofenceBreach } from '../../core/geofence/geofence-logic';

/**
 * Pure, Angular-free model behind `WallFacade`/`wall.ts`/`wall-tile.ts`/`wall-focus.ts`/
 * `wall-activity.ts` (docs/plans/active/WALL-FLOW-PLAN.md §3.4, frozen) — every rule the wall's
 * per-tile health/attention/pulse verdicts and its activity drawer's rows derive from, split out so
 * they're unit-testable without HTTP, a poller, or a component, mirroring every other feature's own
 * `*-logic.ts` split (`core/fleet/attention-logic.ts`, `features/command/command-logic.ts`, …).
 *
 * **The fleet-summary join replaces per-tile telemetry polling** (WALL-FLOW-PLAN.md §2.1 D4/D5,
 * accepted decision #3): a tile's identity/battery/telemetry-age/mode/armed/failsafe/open-event-count
 * all come from one `AssetAttention` row (`GET /api/fleet/summary`, already polled fleet-wide by
 * `WallFacade`) rather than a per-tile `TelemetryStore` poller. The join key is `AssetAttention.streamId`
 * (present only while `asset.streaming` is `true` — that field's own doc comment), matched against
 * `ActiveStream.streamId` — the same shape `features/command/command-logic.ts#buildEntityRows` reads,
 * applied to a stream-keyed tile instead of an asset-keyed row (the wall's tiles are *streams*, not
 * assets — a streaming device with no asset still gets a tile, `unlinked: true`).
 */

// --- Tile health (§3.2 A1) -----------------------------------------------------------------------

export type TileHealth =
  | 'live'
  | 'starting'
  | 'stalled'
  | 'reconnecting'
  | 'no-publisher'
  | 'pipeline-error'
  | 'unknown';

export interface TilePulse {
  readonly label: string;
  readonly count: number;
  readonly atIso: string;
}

export interface WallTileModel {
  readonly streamId: string;
  readonly deviceId: string;
  readonly assetId?: string;
  readonly title: string;
  readonly unlinked: boolean;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
  readonly health: TileHealth;
  readonly healthLabel: string | null;
  readonly severity: AttentionSeverity | 'ok';
  readonly reasons: readonly AttentionReason[];
  /** Whole-percent, formatted (`"58%"`) — never the raw float `AssetAttention.batteryPercent` carries
   *  (a live tile once rendered `"58.349999999999994%"`; rounded once here, at production, the same
   *  way {@link telemetryAgeLabel} is a finished label rather than a raw `telemetryAgeMs` the
   *  component would have to format itself). `null` when the join has no battery reading, same "—"
   *  contract as every other absent fact. */
  readonly batteryLabel: string | null;
  readonly batterySeverity: BatteryAttentionSeverity;
  readonly telemetryAgeLabel: string | null;
  readonly flightMode?: string;
  readonly armed?: boolean;
  readonly failsafe?: boolean;
  readonly pulse: TilePulse | null;
  readonly openEventCount: number;
}

export interface WallActivityRow {
  readonly event: DetectionEvent;
  readonly tile: WallTileModel;
  readonly sourceLabel: string;
}

export interface BuildWallTilesInput {
  readonly streams: readonly ActiveStream[];
  readonly devices: readonly Device[];
  readonly assets: readonly AssetAttention[];
  readonly events: readonly DetectionEvent[];
  readonly pipelineErrors: ReadonlyMap<string, string>;
  readonly breaches: readonly GeofenceBreach[];
  readonly nowMs: number;
  /** `true` once `WallFacade`'s fleet-summary poll has resolved at least once (success OR a failure
   *  that fell back to a stale-but-real summary) — `false` only for the brief window between mount
   *  and that first response landing. Distinguishes "no asset data yet" from "asset data arrived and
   *  confirms no link": `assets: []` means the former on a cold mount and the latter after a
   *  failed/forbidden poll with no prior summary, and only `unlinked` should tell them apart — a tile
   *  is never allowed to assert "Not linked to an asset" before the join has actually had a chance to
   *  run (the defect a live check caught: a real, matching asset existed, but the tile rendered
   *  unlinked because it painted before the first summary response arrived). */
  readonly summaryLoaded: boolean;
}

/**
 * `wallDensity` → the 3-stop segmented control (§3.1, replacing the old `Tiles per row` `<select>`,
 * D11) — `value` is the number `SettingsFacade.wallDensity` persists (unchanged key/type, so an
 * existing 2/3/4/5/6 value from before this wave still resolves via {@link tileMinPx}'s own clamp),
 * `tileMinPx` feeds `repeat(auto-fill, minmax(var(--tile-min), 1fr))` directly.
 */
export const DENSITY_STOPS: readonly { value: number; label: string; tileMinPx: number }[] = [
  { value: 2, label: 'Comfortable', tileMinPx: 480 },
  { value: 3, label: 'Compact', tileMinPx: 320 },
  { value: 4, label: 'Dense', tileMinPx: 240 },
];

/** `≥4` reads as `Dense`, `<2` as `Comfortable` — a stale/pre-wave persisted value (the old
 *  `<select>` allowed 2..6) degrades to the nearest real stop rather than an undefined `--tile-min`. */
export function tileMinPx(wallDensity: number): number {
  if (wallDensity <= DENSITY_STOPS[0].value) {
    return DENSITY_STOPS[0].tileMinPx;
  }
  if (wallDensity >= DENSITY_STOPS[2].value) {
    return DENSITY_STOPS[2].tileMinPx;
  }
  return DENSITY_STOPS[1].tileMinPx;
}

/**
 * `ActiveStream.state` + "no publisher URL" + an active `PIPELINE_ERROR` collapse into one verdict,
 * frozen precedence (§3.2 A1): `no-publisher` → `pipeline-error` → `stalled` → `reconnecting` →
 * `starting` → `live`/`unknown`. `live`/`unknown` both return a `null` label — the picture is the
 * message for `live`, and `unknown` (`UNOBSERVED`, or an absent `state` from an older backend) is the
 * honest "cannot judge" rather than a fabricated fault (`stream-state-logic.ts#videoNotice`'s own
 * rule, applied at tile scale).
 *
 * `pipelineErrorDetail` renders **verbatim** (CLAUDE.md rule: never paraphrase a server-supplied
 * failure reason) — this is the one health label with a variable tail; every other label is a fixed,
 * tile-scale phrase (two-to-four words, `wall-tile.html`'s own single HUD line — see
 * WALL-FLOW-PLAN.md §3.3's reuse-ledger note on why this doesn't reuse `stream-state-logic.ts`'s
 * cockpit-scale sentences).
 */
function deriveHealth(
  stream: ActiveStream,
  pipelineErrorDetail: string | undefined,
): { readonly health: TileHealth; readonly healthLabel: string | null } {
  if (!stream.viewUrl) {
    return { health: 'no-publisher', healthLabel: 'No publisher — nothing to watch.' };
  }
  if (pipelineErrorDetail) {
    return { health: 'pipeline-error', healthLabel: `Pipeline error — ${pipelineErrorDetail}` };
  }
  switch (stream.state) {
    case 'STALLED':
      return { health: 'stalled', healthLabel: 'No video arriving.' };
    case 'RECONNECTING':
      return { health: 'reconnecting', healthLabel: 'Reconnecting…' };
    case 'STARTING':
      return { health: 'starting', healthLabel: 'Starting…' };
    case 'LIVE':
      return { health: 'live', healthLabel: null };
    default:
      return { health: 'unknown', healthLabel: null };
  }
}

/**
 * The tile's pulse (§3.2 A3, `UX-DESIGN.md:171` finally built) — the *dominant* label among this
 * stream's active `OPEN` detection events within {@link PULSE_WINDOW_MS}: group by label, the
 * label with the most concurrently-open events wins (a `Person ×3` chip means three concurrently
 * open person-detections, not three historical ones — `count` only ever counts events still `OPEN`
 * and still inside the window), ties broken by whichever label's own most-recent event is more
 * recent. A single late arrival of a different label must not steal the chip from an
 * already-larger, still-open group — the dominant activity is the more honest "what does this tile
 * need me for" signal than whichever event merely arrived last. `label` is the raw wire label
 * (lowercase); the template capitalizes it via CSS, matching `shared/ui/event-row.css#.event-label`'s
 * own `text-transform: capitalize` convention rather than a second JS capitalizer.
 */
function tilePulse(events: readonly DetectionEvent[], streamId: string, nowMs: number): TilePulse | null {
  const cutoffMs = nowMs - PULSE_WINDOW_MS;
  const active = events.filter(
    (event) => event.streamId === streamId && event.state === 'OPEN' && Date.parse(event.lastSeen) >= cutoffMs,
  );
  if (active.length === 0) {
    return null;
  }

  const countByLabel = new Map<string, number>();
  const mostRecentByLabel = new Map<string, DetectionEvent>();
  for (const event of active) {
    countByLabel.set(event.label, (countByLabel.get(event.label) ?? 0) + 1);
    const current = mostRecentByLabel.get(event.label);
    if (!current || Date.parse(event.lastSeen) > Date.parse(current.lastSeen)) {
      mostRecentByLabel.set(event.label, event);
    }
  }

  let winner: DetectionEvent | undefined;
  let winnerCount = -1;
  for (const [label, count] of countByLabel) {
    const candidate = mostRecentByLabel.get(label)!;
    const candidateIsNewerTie = count === winnerCount && Date.parse(candidate.lastSeen) > Date.parse(winner!.lastSeen);
    if (count > winnerCount || candidateIsNewerTie) {
      winner = candidate;
      winnerCount = count;
    }
  }

  return { label: winner!.label, count: winnerCount, atIso: winner!.lastSeen };
}

/** How long an `OPEN` event keeps a tile pulsing after its own `lastSeen` (§3.2 A3). */
export const PULSE_WINDOW_MS = 60_000;

/** The activity drawer's own recency floor (§3.2 A4, D19) — older activity is `/monitor/alerts`' job. */
export const ACTIVITY_WINDOW_MS = 60 * 60 * 1000;

/**
 * The wall's tiles, one per currently-running stream, **stable-ordered** (§3, accepted decision #2):
 * by `title` (case-insensitive), then `streamId` as the deterministic tie-break — never
 * attention-sorted, so a watcher's spatial memory of the grid survives an alarm.
 *
 * Degrades honestly on a failed/forbidden fleet summary: `assets: []` leaves every tile `severity:
 * 'ok'`, `batterySeverity: 'unknown'`, title falling to the device name — never a fabricated fact
 * (§3.2's own framing, WALL-FLOW-PLAN.md wave W1 scope). `unlinked` itself only ever turns `true`
 * once {@link BuildWallTilesInput.summaryLoaded} is `true` — before the first summary response lands,
 * "no asset data yet" is not the same claim as "confirmed no asset," and only the latter earns the
 * "Not linked to an asset" note.
 */
export function buildWallTiles(input: BuildWallTilesInput): readonly WallTileModel[] {
  const { streams, devices, assets, events, pipelineErrors, breaches, nowMs, summaryLoaded } = input;

  const assetByStreamId = new Map<string, AssetAttention>();
  for (const asset of assets) {
    if (asset.streaming && asset.streamId) {
      assetByStreamId.set(asset.streamId, asset);
    }
  }

  const tiles: WallTileModel[] = streams.map((stream) => {
    const asset = assetByStreamId.get(stream.streamId);
    const device = devices.find((candidate) => candidate.id === stream.deviceId);
    const title = asset?.displayName ?? device?.name ?? stream.deviceId.slice(0, 8);
    const pipelineErrorDetail = pipelineErrors.get(stream.streamId);
    const { health, healthLabel } = deriveHealth(stream, pipelineErrorDetail);

    const assetBreaches = asset ? breaches.filter((breach) => breach.assetId === asset.assetId) : [];
    const rawReasons = asset ? attentionReasons(asset, undefined, assetBreaches, pipelineErrorDetail) : [];
    // Two anti-double-signal rules (§3.2 A2, frozen): `open-events` is dropped — the pulse (A3)
    // already says it, with the label; `pipeline-error` is dropped too — when it's this tile's
    // health, the health line already says it (once), and when something worse pre-empted it as the
    // health (`no-publisher`), a leftover pipeline-error chip would just be noise about a stream
    // that isn't watchable anyway.
    const reasons = rawReasons.filter((reason) => reason.kind !== 'open-events' && reason.kind !== 'pipeline-error');

    return {
      streamId: stream.streamId,
      deviceId: stream.deviceId,
      assetId: asset?.assetId,
      title,
      unlinked: asset === undefined && summaryLoaded,
      viewUrl: stream.viewUrl,
      whepUrl: stream.whepUrl,
      health,
      healthLabel,
      severity: reasons[0]?.severity ?? 'ok',
      reasons,
      batteryLabel: asset?.batteryPercent !== undefined ? `${Math.round(asset.batteryPercent)}%` : null,
      // Severity classification still reads the raw value — rounding only ever happens at the
      // display boundary, never before a threshold comparison.
      batterySeverity: batteryAttentionSeverity(asset?.batteryPercent),
      telemetryAgeLabel: asset?.telemetryAgeMs !== undefined ? attentionAgeLabel(asset) : null,
      flightMode: asset?.flightMode,
      armed: asset?.armed,
      failsafe: asset?.failsafe,
      pulse: tilePulse(events, stream.streamId, nowMs),
      openEventCount: asset?.openEventCount ?? 0,
    };
  });

  return [...tiles].sort((a, b) => {
    const byTitle = a.title.localeCompare(b.title, undefined, { sensitivity: 'base' });
    return byTitle !== 0 ? byTitle : a.streamId.localeCompare(b.streamId);
  });
}

// --- Video-on-request (§4 Wave C1/C2, ALWAYS-ON-FLOW-PLAN.md) ------------------------------------

/**
 * How many wall tiles may have a `<vision-player>` mounted at once, wall-wide. A tile's own picture
 * is real, continuous decode/render cost (WebRTC/HLS) — the exact "mediamtx can take many streams,
 * the UI cannot" asymmetry ALWAYS-ON-FLOW-PLAN.md opens with (§1). Before this wave `wall.ts` mounted
 * one player per running stream, uncapped (C2); the existing `IntersectionObserver` off-screen
 * suspension (`wall-tile.ts`'s `PREROLL_MARGIN`) does nothing for a grid that fits on one screen —
 * exactly the case that matters, and exactly what this cap now bounds instead.
 *
 * `6` is a **reasoned, not measured** default — no multi-stream decode load test backs this number
 * (report this honestly rather than implying it is tuned). It is chosen to comfortably cover the
 * `Comfortable` density stop (`DENSITY_STOPS[0]`, 480px tiles — a typical operator monitor fits
 * roughly 4-6 of those at once) so a wall viewed at its least-dense setting can go fully live from a
 * single "watch everything" gesture, while `Dense` (240px, potentially dozens of tiles) cannot and is
 * not meant to: dense mode is for triage-by-state across many assets, not simultaneous video.
 */
export const MAX_CONCURRENT_WALL_PLAYERS = 6;

/**
 * Raises `streamId`'s video. **Evicts the least-recently-raised tile rather than refusing** once the
 * cap ({@link MAX_CONCURRENT_WALL_PLAYERS}) is already full — the deliberate choice, not an
 * oversight: a wall exists so an operator can act on whatever just became relevant, and refusing a
 * fresh, explicit click in favor of a tile that has been sitting live and unattended for longest
 * would silently block the very gesture the wall promises to honor (CLAUDE.md's own "never silently
 * do nothing"). Eviction always frees exactly the tile that has waited longest since it was last
 * (re)raised — the standard LRU rule — so a burst of new requests never evicts something an operator
 * only just switched to. Re-raising an already-up tile moves it to the back (freshest) rather than
 * being a no-op, so re-clicking a live tile can never be the thing that evicts it a moment later.
 *
 * **Deliberately never auto-called for a `severity === 'critical'` tile.** ALWAYS-ON-FLOW-PLAN.md §6
 * is explicit: *"It does not remove viewer demand. Demand is correct — it is applied to the wrong
 * planes."* Video is the View plane; §1's own table says the View plane's governor is "genuine
 * viewer demand", full stop — a severity condition is a STATE-plane fact, and the wall already
 * surfaces it without pixels (the severity border colour, the health line, the reasons list, the
 * pulse chip all update with no player mounted at all). Auto-raising video on `critical` would (a)
 * spend real decode/network cost on a screen nobody may be looking at (the literal use case a video
 * *wall* implies), (b) let a burst of simultaneous alarms silently evict tiles an operator explicitly
 * chose to watch, competing for the same capped slots against their own deliberate clicks, and (c)
 * reintroduce exactly the "state driving the view plane automatically" coupling this whole wave
 * exists to remove. The severity dot and pulse chip are the escalation signal; raising the picture
 * stays the operator's own next click.
 */
export function requestWallVideo(
  raised: readonly string[],
  streamId: string,
  maxConcurrent: number = MAX_CONCURRENT_WALL_PLAYERS,
): readonly string[] {
  const next = [...raised.filter((id) => id !== streamId), streamId];
  return next.length > maxConcurrent ? next.slice(next.length - maxConcurrent) : next;
}

/** Lowers `streamId`'s video — a no-op (same array-shape-wise; a fresh array either way) if it wasn't raised. */
export function releaseWallVideo(raised: readonly string[], streamId: string): readonly string[] {
  return raised.filter((id) => id !== streamId);
}

/**
 * The activity drawer's rows (§3.2 A4, W4) — newest-first, scoped to *this wall's* tiles, inside
 * {@link ACTIVITY_WINDOW_MS}. Frozen matching rule: **by `assetId` when the event and a tile both
 * carry one, else by `streamId`** — so an asset whose stream restarted mid-session still shows its
 * earlier events against its current tile rather than falling off the wall (an asset's `assetId`
 * survives a stream restart; a bare `streamId` does not). An event matching neither is dropped —
 * this is deliberately *not* the fleet's full activity, only what this wall can honestly name (the
 * fix for D15/D16: a row's `sourceLabel` is always its matched tile's own `title`, never
 * `describeEventSource`'s `Removed device · …` fallback, by construction).
 */
export function wallActivityRows(
  events: readonly DetectionEvent[],
  tiles: readonly WallTileModel[],
  nowMs: number,
): readonly WallActivityRow[] {
  const tilesByAssetId = new Map<string, WallTileModel>();
  const tilesByStreamId = new Map<string, WallTileModel>();
  for (const tile of tiles) {
    if (tile.assetId) {
      tilesByAssetId.set(tile.assetId, tile);
    }
    tilesByStreamId.set(tile.streamId, tile);
  }

  const cutoffMs = nowMs - ACTIVITY_WINDOW_MS;
  const rows: WallActivityRow[] = [];
  for (const event of events) {
    if (Date.parse(event.lastSeen) < cutoffMs) {
      continue;
    }
    const tile = (event.assetId ? tilesByAssetId.get(event.assetId) : undefined) ?? tilesByStreamId.get(event.streamId);
    if (!tile) {
      continue;
    }
    rows.push({ event, tile, sourceLabel: tile.title });
  }
  return rows.sort((a, b) => Date.parse(b.event.lastSeen) - Date.parse(a.event.lastSeen));
}
