import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import type { UpdateStreamConfigRequest } from '../../core/api/models';
import type { BoxesMode } from '../../shared/player/player';
import { DECLUTTER_LEVELS, DEFAULT_DECLUTTER_LEVEL, declutterLevelLabel } from '../../shared/player/detection-overlay-logic';
import {
  DETECTION_LAG_BUDGET_MILLIS,
  buildReleaseLockPatch,
  classesOnScreenCount,
  detectionStatus,
  findModel,
  isCapabilityDowngraded,
  isDetectionLagOverBudget,
  latestFrameTracking,
  modelCostWord,
} from './cv-control-panel-logic';

/**
 * The Fly cockpit's **fly-time Vision surface** (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P1, §1
 * "Surface 1 — flying: seconds matter, one glance") — the seven controls an operator needs while
 * actually flying, top to bottom: the Detect hero switch with an honest status line, a "Looking
 * for" summary row (name + cost word, with a "Change…" door into the setup modal), the Boxes
 * declutter control, the "Following #N — release" lock chip, the two conditional honesty notices
 * (capability downgrade / lag-over-budget — surfaced only while they actually fire), and a
 * "Detection setup…" door at the bottom. Every *decision* control — the model intent cards,
 * confidence, the class checklist, tracking mode, and the collapsed Expert tier — moved to
 * {@link CvSetupModal}, opened by {@link setupRequested} (`cockpit.ts` wires this to
 * `dialog.open('cv-setup')`, its own transient dialog `UiStore` group).
 *
 * **Split history**: this component used to be the single 474-line, 12-section CV control panel
 * (docs/plans/done/CV-CONTROL-PLAN.md Wave E through docs/plans/active/CV-CLEAN-FEED-PLAN.md wave
 * W5's merge into the one "Vision" drawer). docs/plans/active/CV-PANEL-SPLIT-PLAN.md P1 split it
 * into this fly-time panel and the calm-hands `CvSetupModal` — see that plan's §1 for the full
 * "what lives where" rationale (CV-UX-RESEARCH.md §2's task ranking is the underlying *why*). This
 * component keeps the shared pure logic in `cv-control-panel-logic.ts` alongside the modal (both
 * import from it) rather than forking a second copy.
 *
 * **Body-only, no drawer shell of its own** (wave W5, docs/plans/active/CV-CLEAN-FEED-PLAN.md D-3,
 * unchanged by the P1 split): `cockpit.html` owns the one `<vision-side-panel>` shell for the
 * merged Vision drawer, mounting this component and `<vision-detections-strip>` as siblings inside
 * it; mounting this component *is* opening it.
 *
 * **The tracks poll (`GET .../tracks`) is still owned here** (`DetectionsStore#trackTracks`/
 * `untrackTracks`, constructor `effect`/`DestroyRef` below, unchanged from before the split) —
 * `CvSetupModal` deliberately does **not** call either: this panel stays mounted for the whole time
 * the Vision drawer is open, a strictly *longer* window than the modal's own (nested, shorter)
 * open/close cycle, so keeping the poll's lifecycle here is what keeps "poll only while the drawer
 * is open" true regardless of whether the modal happens to be open too. See `CvSetupModal`'s own
 * class doc comment for the reasoning from the modal's side.
 *
 * **`detectionEnabled` is the one knob with a live wire readback** (docs/plans/active/STREAM-STATE-
 * PLAN.md §3.1) — see this class's own {@link onDetectionEnabledToggle} for why the hero switch
 * renders server truth, never optimistic local intent.
 *
 * **The "Following #N — release" chip is wire-confirmed only** — never a click's own optimistic
 * guess (docs/extracts/TRACKING-ORCHESTRATION.md §3.3): {@link lockedTrackId} only ever reads
 * `DetectionsStore#tracks()`'s own echoed `lockedTrackId`, unchanged by the split.
 */
@Component({
  selector: 'vision-cv-control-panel',
  imports: [],
  templateUrl: './cv-control-panel.html',
  styleUrl: './cv-control-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CvControlPanel {
  /** The primary device's currently-running stream id, or `undefined` before the first Start —
   * gates whether the follow-lock's release action also PATCHes live. */
  readonly streamId = input<string | undefined>(undefined);

  /** The running stream's own server-side detect intent (`CockpitFacade`'s `facade.detectionOn()`,
   * docs/plans/active/STREAM-STATE-PLAN.md §3.1) — already resolved by the facade against the draft, so
   * this component renders it rather than re-deciding the rule. See {@link onDetectionEnabledToggle}
   * for why the switch never renders local intent. */
  readonly detectionEnabled = input<boolean>(false);

  /** True while the host's Detect on/off request is in flight — see {@link onDetectionEnabledToggle}. */
  readonly detectionPending = input<boolean>(false);

  /** Emitted by the Detect switch; the host (`CockpitFacade#setDetection`) owns the actual write. */
  readonly detectionEnabledChange = output<boolean>();

  /** Emitted by the "Change…" and "Detection setup…" buttons alike — the host (`cockpit.ts`) owns
   *  the actual `dialog.open('cv-setup')` call, mirroring `flight-command-panel.ts`'s own
   *  "component only reports the request, host owns the `UiStore` write" shape for its dialogs. */
  readonly setupRequested = output<void>();

  /** The detection-boxes declutter level (`FlyPage`'s own `facade.boxesMode`) — a client-side
   * rendering preference, not part of `PipelineSettings`/the wire contract, so it round-trips via a
   * plain input/output pair rather than `SettingsStore`. */
  readonly boxesMode = input<BoxesMode>(DEFAULT_DECLUTTER_LEVEL);
  readonly boxesModeChange = output<BoxesMode>();
  /** The four declutter levels, in cycle order — the segmented control's own `@for` source. */
  protected readonly declutterLevels = DECLUTTER_LEVELS;
  protected boxesModeLabel(mode: BoxesMode): string {
    return declutterLevelLabel(mode);
  }

  private readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  /** Recent detection results and the tracks poll (`GET .../tracks`) alike — host-provided
   *  (`cockpit.ts`'s own `providers`), the same instance `CvSetupModal`, the detections strip, and
   *  `CockpitFacade` all share. See class doc's own "The tracks poll is still owned here" paragraph. */
  protected readonly detections = inject(DetectionsStore);

  // --- "Looking for" summary row (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P1 §1.1 item 2) -------

  protected readonly models = computed(() => this.fleet.models());
  protected readonly selectedModel = computed(() => findModel(this.models(), this.settings.effective().model));
  /** The summary row's own cost word — shared with the setup modal's intent cards via
   *  `cv-control-panel-logic.ts#modelCostWord`, so the two surfaces can never disagree. */
  protected costWord(openVocab: boolean): string {
    return modelCostWord(openVocab);
  }

  // --- Capability ladder + detection lag (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md, wave J4) --
  // Duplicated (not shared via an output) from `CvSetupModal`'s identical computed pair — both read
  // the same `DetectionsStore#results()` directly, so there is nothing to keep in sync by wiring;
  // this panel needs only the two *booleans* for its conditional notices below, never the modal's
  // own quiet "Serving"/lag-value readout.

  private readonly frameTracking = computed(() => latestFrameTracking(this.detections.results()));
  /** The downgrade notice's own reason text (`servedCapability()?.reason`) — the server's own
   *  `reason` field, never a local comparison (invariant B5). */
  protected readonly servedCapability = computed(() => this.frameTracking()?.capability);
  protected readonly capabilityDowngraded = computed(() => isCapabilityDowngraded(this.servedCapability()));
  protected readonly detectionLagBudgetMillis = DETECTION_LAG_BUDGET_MILLIS;
  protected readonly detectionLagOverBudget = computed(() =>
    isDetectionLagOverBudget(this.frameTracking()?.detectionLagMillis ?? 0),
  );

  // --- Click-to-follow lock chip (docs/plans/done/TRACKING-PLAN.md, wave T7; docs/plans/active/
  // CV-FLY-INTERACTION-RESEARCH.md §3.2, wave W4) — stays in this panel per the split plan: the
  // real Follow interaction is clicking a box in the video, not a control in either surface, and
  // the chip's own honesty rule (wire-confirmed only) is unrelated to which controls live where.

  /** `0` = no lock held. **This is the one and only signal the "Following #N — release" chip reads**
   * — never the trackId just clicked, never an optimistic local flag. */
  protected readonly lockedTrackId = computed(() => this.detections.tracks()?.lockedTrackId ?? 0);

  /** Mirrors {@link lockedTrackId} out to the host, for `shared/player/player.ts`'s `lockedTrackId`
   * input (wave W4) — the overlay's T0 tier needs the same honest, wire-confirmed-only lock id. */
  readonly lockedTrackIdChange = output<number>();

  // --- Detection status line (docs/plans/active/CV-UX-RESEARCH.md §1.2/§3/§9.2, waves U3+U5) -----

  protected readonly hasStream = computed(() => !!this.streamId());
  private readonly latestResult = computed(() => this.detections.results()[0]);
  protected readonly classesOnScreen = computed(() =>
    classesOnScreenCount(this.latestResult(), this.settings.effective().labelFilter),
  );
  protected readonly detectionStatusInfo = computed(() =>
    detectionStatus(
      this.detectionEnabled(),
      this.hasStream(),
      this.detections.tracks()?.detectionState,
      this.detections.tracks()?.rate,
      this.classesOnScreen(),
    ),
  );
  protected readonly detectionStatusText = computed(() => this.detectionStatusInfo().text);

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.detections.untrackTracks();
    });

    // A stream change (including "stream stopped", `undefined`) starts/stops the tracks poll this
    // component owns the lifetime of (`DetectionsStore#trackTracks`/`untrackTracks`, wave W5) — that
    // call itself clears any stale lock/flow-strip reading immediately, so a *previous* stream's
    // response can never linger into the next.
    effect(() => {
      const streamId = this.streamId();
      if (streamId) {
        this.detections.trackTracks(streamId);
      } else {
        this.detections.untrackTracks();
      }
    });

    // See {@link lockedTrackIdChange}'s own doc comment — re-emits on every change, including back
    // to `0` the instant a poll confirms the lock was released.
    effect(() => this.lockedTrackIdChange.emit(this.lockedTrackId()));
  }

  /** The release chip's own action — drops the lock, falls back to the mode's own policy. Every
   *  tracking PATCH is sent immediately, never debounced. */
  protected releaseLock(): void {
    const streamId = this.streamId();
    if (streamId) {
      void this.fleet.patchStreamConfig(streamId, buildReleaseLockPatch() as UpdateStreamConfigRequest);
    }
  }

  /**
   * **Not** a debounced edit (docs/plans/active/STREAM-STATE-PLAN.md §3.1) — a single explicit
   * click is not a gesture that might still be mid-drag, and it does not write what the operator
   * clicked into the rendered value: {@link detectionEnabled} is server truth, so the switch moves
   * once the wire says it moved. Emitting rather than self-applying is what keeps that single rule
   * in one place (`CockpitFacade`) instead of two.
   */
  protected onDetectionEnabledToggle(checked: boolean): void {
    this.detectionEnabledChange.emit(checked);
  }
}
