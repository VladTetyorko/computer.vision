import type { SeatHolderResponse } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `CrewSeatPage`/`CrewFacade` (docs/plans/active/CREW-CONTROL-PLAN.md §3.4)
 * — the crew seat surface's own stage/dock derivation, split out so it is unit-testable without HTTP
 * or the router, mirroring every other page's own `*-logic.ts` split (`features/fly/fly-logic.ts`'s
 * identical `FlyStage`/`flyStage` pair is this file's direct model).
 */

/**
 * `CrewSeatPage`'s own four-stage read of "where is this crew station right now" (§3.4's table) —
 * deliberately four instead of the cockpit's five `FlyStage`s: this page never commands flight, so
 * there is no `'engaged'` counterpart. `C0` no video, `C1` starting (a Start-video click in flight,
 * pre-first-frame), `C2` watching (live, but the camera seat is held by someone else), `C3` working
 * (live, and this crew member holds the camera seat — or nobody does, the single-operator case).
 */
export type CrewStage = 'C0' | 'C1' | 'C2' | 'C3';

/** {@link crewStage}'s own input — mirrors `fly-logic.ts#FlyStageInput`'s naming convention. */
export interface CrewStageInput {
  readonly live: boolean;
  readonly busy: boolean;
  /** See {@link cameraHeldByOther}'s own doc comment — never re-derive this as `!camera.mine`. */
  readonly cameraHeldByOther: boolean;
}

/**
 * §3.4's stage computation, `live` → `busy` → `C0` in that priority order, mirroring
 * `fly-logic.ts#flyStage`'s identical "the more advanced fact wins" ordering.
 */
export function crewStage(input: CrewStageInput): CrewStage {
  if (input.live) {
    return input.cameraHeldByOther ? 'C2' : 'C3';
  }
  if (input.busy) {
    return 'C1';
  }
  return 'C0';
}

/**
 * Whether the camera seat is held by somebody who is not this caller — the honest discriminator
 * between "genuinely free" and "somebody else's" (docs/plans/active/CREW-CONTROL-PLAN.md §3.2 rule
 * 2: "free means free"). **Deliberately not `!camera.mine`**: a free seat also reads `mine: false`
 * (§3.6's wire contract — a free seat is four explicit nulls, `mine` included), so gating on `mine`
 * alone would render C2 ("Pilot has the camera") for a seat nobody holds at all, and would block
 * the single-operator/feature-off fallback (§3.8) from ever reaching C3. `holderUserId !== null` is
 * the actual "somebody holds this" fact; `!mine` narrows it to "and it isn't me".
 */
export function cameraHeldByOther(camera: SeatHolderResponse): boolean {
  return camera.holderUserId !== null && !camera.mine;
}

/** `CrewDock`'s own bottom-centre content (§3.4's table, copy verbatim) — `text` always renders;
 * `actionLabel` is present only for the one clickable affordance this page ever offers (C0's Start
 * video, and only while nothing blocks it); `reason` is present only when C0 has something blocking
 * Start instead. Never both `actionLabel` and `reason` at once. */
export interface CrewDock {
  readonly stage: CrewStage;
  readonly text: string;
  readonly actionLabel: string | null;
  readonly reason: string | null;
}

/** A held camera's display name, falling back to a generic phrase if the wire ever violated its own
 * contract (§3.6: `holderDisplayName` should already resolve to a string, id-string fallback
 * included, whenever `holderUserId` is non-null) — degrades honestly rather than rendering `null`. */
export function cameraHolderLabel(camera: SeatHolderResponse): string {
  return camera.holderDisplayName ?? 'another operator';
}

/**
 * The header's own seat chip (§3.4's "header (asset identity · seat chip)") — a persistent,
 * always-visible readout distinct from the dock's stage text (which only speaks at `C2`/`C0`'s
 * blocked branch). Legible at every stage, including before video starts, mirroring §3.5's own
 * "redundant by construction; it exists so the state is legible" reasoning for the take-camera
 * button.
 */
export function cameraSeatChipLabel(camera: SeatHolderResponse): string {
  if (camera.mine) {
    return 'You';
  }
  if (camera.holderUserId === null) {
    return 'Free';
  }
  return cameraHolderLabel(camera);
}

/**
 * §3.4's table, verbatim. `camera` is only consulted for `C0` (whether Start video is offered or a
 * reason is shown instead) — `C2`'s "Pilot has the camera" line is deliberately a fixed phrase, not
 * interpolated with a name (§3.5: "one line on the existing dock", no crew-visible avatar/name
 * cluster leaking onto this surface either).
 */
export function crewDock(stage: CrewStage, camera: SeatHolderResponse): CrewDock {
  switch (stage) {
    case 'C0': {
      const blocked = cameraHeldByOther(camera);
      return {
        stage,
        text: 'Not streaming — the pilot has not started video',
        actionLabel: blocked ? null : 'Start video',
        reason: blocked ? `Camera held by ${cameraHolderLabel(camera)}` : null,
      };
    }
    case 'C1':
      return { stage, text: 'Waiting for the first frame', actionLabel: null, reason: null };
    case 'C2':
      return { stage, text: 'Pilot has the camera', actionLabel: null, reason: null };
    case 'C3':
      return { stage, text: 'You have the camera', actionLabel: null, reason: null };
  }
}
