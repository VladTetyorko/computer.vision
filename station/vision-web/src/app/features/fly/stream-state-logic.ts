import type { StreamState } from '../../core/api/models';

/**
 * The one rule of docs/plans/active/STREAM-STATE-PLAN.md §3.1 — **where a detection control reads its
 * position from**, applied identically by the Detect switch (`cv-control-panel.html`), the tool
 * rail's off-dot and the video-surface "Turn on" chip (`cockpit.html`).
 *
 * Before this plan every one of those rendered `SettingsStore`'s own draft, which is this browser's
 * localStorage — so switching drone, reloading, or opening a second tab could show a switch position
 * that was simply false for the stream in front of the operator (`STREAM-STATE-CONTEXT.md` D1). The
 * draft is not deleted, it is demoted to its honest job: **the default the next `Start` will post**.
 *
 * @param streamValue `ActiveStream.detectionEnabled` — the running stream's own server-side intent,
 *                    or `undefined` when nothing is running (there is no stream to be truthful
 *                    about) or the backend predates this field. Both degrade to the draft, which is
 *                    exactly today's behaviour — the same "an absent field means today" rule
 *                    `detection-overlay-logic.ts#resolveBurnedIn` already follows
 * @param draft       `settings.effective().detectionEnabled`
 */
export function resolveDetectionEnabled(streamValue: boolean | undefined, draft: boolean): boolean {
  return streamValue ?? draft;
}

/** A stage-overlay message about the **video** — never about detection, which is a separate axis. */
export interface VideoNotice {
  /** `warn` earns the amber `.notice` chrome; `neutral` is a plain HUD pill. */
  readonly tone: 'warn' | 'neutral';
  readonly text: string;
}

/**
 * Turns `ActiveStream.state` into what the operator is told over the video, or `null` for "say
 * nothing" (docs/plans/active/STREAM-STATE-PLAN.md §2.3).
 *
 * Two of the five states deliberately produce no message. `LIVE` needs none — the video is the
 * message. `UNOBSERVED` is the honest "cannot judge": a proxied source runs no `VideoSourcePort`
 * inside the JVM, so the backend counts zero frames forever and inventing a notice from that would
 * report a fault that was never measured. An absent state (an older backend) is the same case.
 *
 * Not to be confused with `fly-logic.ts#streamStateLabel`, which words an **asset's**
 * `AssetStatus` ("Streaming"/"Offline") on the picker card — a different axis with a similar name.
 *
 * `STALLED` and `RECONNECTING` are both genuine faults and both amber; they are kept apart because
 * only one of them is already being worked on — a reconnect is the supervisor recovering by itself,
 * and telling an operator that is the difference between waiting and power-cycling a camera.
 */
export function videoNotice(live: boolean, state: StreamState | undefined): VideoNotice | null {
  if (!live) {
    return null;
  }
  switch (state) {
    case 'STARTING':
      return { tone: 'neutral', text: 'Waiting for the first frame…' };
    case 'STALLED':
      return { tone: 'warn', text: 'No video arriving — the source stopped sending.' };
    case 'RECONNECTING':
      return { tone: 'warn', text: 'Reconnecting to the video source…' };
    default:
      return null;
  }
}
