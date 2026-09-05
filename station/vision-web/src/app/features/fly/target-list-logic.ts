import { ageSeconds, humanAge } from '../../core/telemetry/telemetry-logic';
import type { StreamTrack, TrackState } from '../../core/api/models';

/**
 * Pure ordering/presentation for `<vision-target-list>` (docs/plans/active/TRACK-FOLLOW-PLAN.md
 * §3.4, wave W5) — "the second door" onto Follow: one row per live track, ordered **followed
 * first, then most-recently-seen**, so the operator's current lock is always the top row and
 * everything else reads newest-to-oldest beneath it.
 */

/** Mirrors `follow-hud/follow-logic.ts#FollowTone` (`'live' | 'warn' | 'danger'`) — the same three
 *  status hues, reused rather than inventing a fourth palette for one more component's dot. */
export type TargetStateTone = 'live' | 'warn' | 'danger';

/**
 * One row of the target list. `label`/`age` are rendered as-is by the template (an empty `label`
 * renders the table's own faint `—`, per frontend-style §5 — this stays a template-level `||`
 * rather than baking a fallback string into the pure row, so the component's only job is markup).
 */
export interface TargetRow {
  readonly trackId: number;
  readonly label: string;
  readonly stateLabel: string;
  readonly stateTone: TargetStateTone;
  readonly age: string;
  /** `true` for the one row that is the currently-held lock (`CvControlPanel#lockedTrackId`) — the
   *  host's template gates the one selection language (§4) on this flag, never on any local click
   *  state. */
  readonly followed: boolean;
}

/** `TrackState` → the dot colour the row's own state column uses, never a chip (§3.4: "the
 *  followed row already spends the row's one chip"). `TENTATIVE`/`COASTING` share the same "not yet
 *  settled" amber as every other in-flight state elsewhere in this app; `LOST` is defensive — the
 *  server-side `TrackBook` drops a lost track from `tracks[]` at once (docs/plans/active/
 *  TRACK-FOLLOW-PLAN.md §2.2 D3), so this case is expected to be unreachable in practice, not the
 *  frozen `lastBox` placeholder the follow HUD draws for an actually-lost lock. */
function targetStateTone(state: TrackState): TargetStateTone {
  switch (state) {
    case 'CONFIRMED':
      return 'live';
    case 'TENTATIVE':
    case 'COASTING':
      return 'warn';
    case 'LOST':
      return 'danger';
  }
}

/** Sentence-case, plain text — one short word, no punctuation, matching every other quiet state
 *  reading in this app (frontend-style §10). */
function targetStateLabel(state: TrackState): string {
  switch (state) {
    case 'CONFIRMED':
      return 'Confirmed';
    case 'TENTATIVE':
      return 'Tentative';
    case 'COASTING':
      return 'Coasting';
    case 'LOST':
      return 'Lost';
  }
}

/** `true` iff `trackId` is the id currently locked — `followedTrackId === 0` means "no lock held"
 *  (the wire's own sentinel, `StreamTracksResponse.lockedTrackId`'s own doc comment), so it can never
 *  spuriously match a real track id (backend track ids are never `0` — `track_id == 0` decodes as
 *  untracked before any other field is read). */
function isFollowedTrack(trackId: number, followedTrackId: number): boolean {
  return followedTrackId !== 0 && trackId === followedTrackId;
}

/**
 * The one entry point `target-list.ts` calls. Sorts a copy of `tracks` — followed first (there is at
 * most one), then descending by `lastSeen` (most-recently-seen first) — then maps each to its row
 * presentation. `nowMs` is read once by the caller (mirrors `follow-logic.ts#followPresentation`'s
 * identical `nowMs` parameter) rather than this function reaching for a clock itself, so it stays
 * pure and trivially testable with fixed instants.
 */
export function targetRows(
  tracks: readonly StreamTrack[],
  followedTrackId: number,
  nowMs: number,
): readonly TargetRow[] {
  const sorted = [...tracks].sort((a, b) => {
    const aFollowed = isFollowedTrack(a.trackId, followedTrackId);
    const bFollowed = isFollowedTrack(b.trackId, followedTrackId);
    if (aFollowed !== bFollowed) {
      return aFollowed ? -1 : 1;
    }
    return Date.parse(b.lastSeen) - Date.parse(a.lastSeen);
  });
  return sorted.map((track) => ({
    trackId: track.trackId,
    label: track.label,
    stateLabel: targetStateLabel(track.state),
    stateTone: targetStateTone(track.state),
    age: humanAge(ageSeconds(track.lastSeen, nowMs) ?? 0),
    followed: isFollowedTrack(track.trackId, followedTrackId),
  }));
}
