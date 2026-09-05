import { ageSeconds, humanAge } from '../../../core/telemetry/telemetry-logic';
import type { FollowState, FollowStatus } from '../../../core/api/models';

/**
 * Frozen (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.3, wave W4) — the follow-hud's own state colour
 * vocabulary. `'live'` mirrors the app's existing "actually happening now" green (`.dot.live`,
 * `--color-live`); `'warn'`/`'danger'` mirror `.chip.warn`/`.chip.danger`'s own severity tiers —
 * never a bespoke fourth colour for this one component.
 */
export type FollowTone = 'live' | 'warn' | 'danger';

/**
 * What `<vision-follow-hud>` renders, derived once per `follow` read — frozen shape
 * (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.3). The component itself owns only markup; every
 * decision about words/colour/gating lives here so it stays unit-testable without Angular.
 */
export interface FollowPresentation {
  /** `"<label> #<trackId>"`, or bare `"#<trackId>"` when `label` is `""` (the tracker never
   *  classified the target) — never blank, and never the id alone with a dangling label. */
  readonly title: string;
  /** One short phrase, never a bare spinner (§3.2's own rule: "state text — one short phrase,
   *  never a spinner without words"). */
  readonly detail: string;
  /** `HOLDING → 'live'` · `REQUESTING`/`COASTING → 'warn'` · `LOST → 'danger'` (frozen mapping,
   *  §3.3). `RELEASED` is not part of the frozen table — it is a one-poll tail state before the
   *  wire omits `follow` entirely (`FollowStatus`'s own doc comment) — mapped to `'warn'` here as
   *  the same "transitional, not a fault" bucket as `REQUESTING`/`COASTING`, since a release is an
   *  operator's own deliberate exit, never something gone wrong. */
  readonly tone: FollowTone;
  /** Gates the HUD's Re-acquire action — `true` only for `state === 'LOST' && reacquirable`
   *  (§3.2: "rendered only when state == 'LOST' && reacquirable"). */
  readonly showReacquire: boolean;
  /**
   * Gates F2's own "Zoom ×2" toggle (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1 item 4, wave W6) —
   * `true` for every state except `LOST`. Crop-follow is meaningless without *some* lock (hence the
   * whole HUD's own top-level `follow !== null` gate already keeps it off the glass entirely when
   * there is none), but during `LOST` specifically the toggle must vanish rather than merely gray
   * out: re-framing on the frozen `lastBox` — the only box left to zoom toward — would be a lie about
   * where the camera is actually looking, the same honesty rule `player.ts`'s own crop-follow step
   * enforces one layer down (it drops to identity in the same states, `null` target box included).
   */
  readonly showCropToggle: boolean;
}

function followTitle(follow: FollowStatus): string {
  return follow.label === '' ? `#${follow.trackId}` : `${follow.label} #${follow.trackId}`;
}

function followTone(state: FollowState): FollowTone {
  switch (state) {
    case 'HOLDING':
      return 'live';
    case 'LOST':
      return 'danger';
    case 'REQUESTING':
    case 'COASTING':
    case 'RELEASED':
      return 'warn';
  }
}

/** `LOST`'s own detail text needs the age of the last confirmed sighting — `ageSeconds`/`humanAge`
 *  are the one shared duration vocabulary the rest of the app already uses for this
 *  (`cockpit-facade.ts`'s own `notStreamingLastSeen`), so a "how long ago" phrase never invents a
 *  second format. `null` when `lastSeenAt` itself is `null` (no confirmed sighting yet, e.g. a lock
 *  that never got past `REQUESTING` before it expired) — the detail then reads a plain "Lost". */
function followDetail(follow: FollowStatus, nowMs: number): string {
  switch (follow.state) {
    case 'REQUESTING':
      return 'Acquiring…';
    case 'HOLDING':
      return 'Following';
    case 'COASTING':
      return 'Coasting';
    case 'RELEASED':
      return 'Released';
    case 'LOST': {
      const age = ageSeconds(follow.lastSeenAt ?? undefined, nowMs);
      return age === undefined ? 'Lost' : `Lost — last seen ${humanAge(age)} ago`;
    }
  }
}

/**
 * The one entry point `follow-hud.ts` calls (frozen signature, §3.3). Pure and total over every
 * {@link FollowState} — callers gate on `follow !== null` themselves (render nothing) before
 * calling this; there is no "absent" case inside the function itself.
 */
export function followPresentation(follow: FollowStatus, nowMs: number): FollowPresentation {
  return {
    title: followTitle(follow),
    detail: followDetail(follow, nowMs),
    tone: followTone(follow.state),
    showReacquire: follow.state === 'LOST' && follow.reacquirable,
    showCropToggle: follow.state !== 'LOST',
  };
}
