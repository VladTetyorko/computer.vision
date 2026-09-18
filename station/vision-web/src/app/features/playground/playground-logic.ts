/**
 * Pure form logic behind `/playground` (docs/plans/active/LINK-PAIRING-PLAN.md §4 row L4, wave L4) —
 * the one page a simulated asset can be created from now that the onboarding wizard's "Use a test
 * source" tile and per-row "Test source" tile are both gone (`features/onboarding/source-step.ts`'s
 * own class doc comment). Framework-free, mirrors every other `*-logic.ts` in this app.
 *
 * Three modes only, not the wizard's historical four (`core/fleet/simulation-logic.ts#SimulateMode`)
 * — `synthetic` is deliberately left out: unlike `direct`/`rtsp`/`testDrone`, it never actually hit
 * `POST /api/simulations` even in the wizard (`OnboardingStore#createViaSimulation`'s own doc
 * comment — it goes through `POST /api/assets` instead, needs a category/identity step this page has
 * no equivalent of). "The ONLY place a simulated asset is created" is scoped to what the plan itself
 * names — the `POST /api/simulations` entry — so leaving `synthetic` off this page's picker is
 * consistent with that wording, not a gap in it.
 */
export type PlaygroundMode = 'direct' | 'rtsp' | 'testDrone';

export const PLAYGROUND_MODES: readonly PlaygroundMode[] = ['testDrone', 'direct', 'rtsp'];

export const PLAYGROUND_MODE_LABELS: Readonly<Record<PlaygroundMode, string>> = {
  testDrone: 'Test drone — moving, no file',
  direct: 'Play a file directly',
  rtsp: 'Transmit a file over RTSP',
};

export const PLAYGROUND_MODE_HINTS: Readonly<Record<PlaygroundMode, string>> = {
  testDrone: 'A synthetic vehicle with live telemetry, flying a route immediately — no file, no camera.',
  direct: 'Feeds a server-side video file straight into the pipeline, as if it were a live camera.',
  rtsp: "Transmits that same file over RTSP first, exercising the real ingest path a camera would use.",
};

/** Only `direct`/`rtsp` play an actual file — `testDrone` is fully synthetic. */
export function playgroundModeNeedsVideoPath(mode: PlaygroundMode): boolean {
  return mode === 'direct' || mode === 'rtsp';
}

export interface PlaygroundForm {
  readonly mode: PlaygroundMode;
  readonly videoPath: string;
}

/** Whether the create form has enough to submit — the only required field is a server-side video
 *  path, and only for the two file-based modes. */
export function canSubmitPlayground(form: PlaygroundForm): boolean {
  return !playgroundModeNeedsVideoPath(form.mode) || form.videoPath.trim().length > 0;
}
