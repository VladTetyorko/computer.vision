import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import { FleetFacade } from '../../core/fleet/fleet-facade';
import { HIDDEN_CLASS_TRUTH, toggleLabelDeny } from '../../core/detections/detections-logic';
import { worldObjectsByTrackId } from './detection-overlay-logic';
import { stripChips, STRIP_CHIP_CAP, type StripChip } from './detections-strip-logic';

/**
 * The detections strip: the last ~8 distinct labels seen, each with a live count, plus a subtle "CV"
 * status dot. Two modes, driven by one input:
 *
 * **Read-only (no {@link streamId} bound)** — `features/live/live.html`'s own long-standing usage,
 * unchanged since this component's original `MVP1-PLAN.md §C8` shape: plain chips, no hover/click
 * wiring at all. Live page has no per-device pipeline-config write path in this app, so nothing here
 * pretends otherwise.
 *
 * **Interactive (`streamId` bound) — the class-level remote control** (docs/plans/active/
 * CV-CLEAN-FEED-PLAN.md D-3, research §3.5), `features/fly/cockpit.html`'s own usage inside the
 * merged Vision drawer:
 * - **Hover** a chip emits {@link hoveredClassChange} — the host (`CockpitFacade`) relays it into
 *   `<vision-player [hoveredClass]>`, which temporarily promotes every box of that class to T1
 *   (`shared/player/detection-overlay-logic.ts#detectionTiers`). Pure client-side, never PATCHes.
 * - **Click** a chip toggles it in {@link labelDenyFilter} (`CockpitFacade#resolvedCvConfig`, wave
 *   W7 — no more `SettingsFacade` draft) and PATCHes `FleetStore.patchStreamConfig` immediately, then
 *   emits {@link configChanged} on success so the host re-reads the wire (H6) — the honest, one-time disclosure
 *   ({@link hiddenClassTruth}) is shown once, at rest, whenever this mode is active. **Never** touches
 *   `labelFilter` (the allowlist) — see `toggleLabelDeny`'s own doc comment
 *   (`core/detections/detections-logic.ts`) for why a deny-list needs no staging the way the
 *   allowlist does. `chip.label` is now (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6, wave W3.2)
 *   the matching {@link WorldObject}'s own wire-elected label for a tracked class, not necessarily
 *   cv-service's raw per-frame label — the PATCH deliberately sends that **displayed** string, since
 *   it is what the operator is actually pointing at when they click. A track with no world object yet
 *   (the transient gap before its first `tracks:` arrival) still shows its raw label, which can
 *   transiently under-suppress a still-flipping track for that same beat — an accepted, honestly-
 *   scoped gap, resolving itself once the world object arrives (see
 *   `shared/player/detections-strip-logic.ts#stripChips`'s own doc comment).
 *
 * A denied label can never reappear in `DetectionsStore.results()` at all — `StreamPipeline`'s single
 * drop site runs pre-fan-out (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2) — so the candidate set
 * ({@link chips}, `shared/player/detections-strip-logic.ts#stripChips`) unions {@link labelDenyFilter}
 * in on top of `results`, the same reasoning `features/fly/cv-control-panel-logic.ts#chipCandidates`
 * already applies to its own checklist.
 *
 * Purely presentational otherwise: injects the same `DetectionsStore` instance from its host page's
 * DI (the host lists it in its own `providers`, mirroring `TelemetryOsd`/`TelemetryStore`) rather
 * than polling independently.
 */
@Component({
  selector: 'vision-detections-strip',
  imports: [],
  templateUrl: './detections-strip.html',
  styleUrl: './detections-strip.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetectionsStrip {
  protected readonly store = inject(DetectionsFacade);
  private readonly fleet = inject(FleetFacade);

  /** The running stream's id — binding this switches the strip into its interactive mode (see class
   *  doc). Left unbound, the strip stays exactly the plain read-only list it has always been. */
  readonly streamId = input<string | undefined>(undefined);
  protected readonly interactive = computed(() => !!this.streamId());

  /** The stream's own currently-resolved deny-list (`CockpitFacade#resolvedCvConfig()?.
   *  labelDenyFilter`, wave W7 — no more `SettingsFacade` draft) — only meaningful in
   *  {@link interactive} mode; the host simply never binds it in read-only mode (see class doc's
   *  own "Read-only" paragraph), same as {@link streamId}. */
  readonly labelDenyFilter = input<readonly string[]>([]);

  /** Emitted on hover-enter/leave while {@link interactive} — `null` on leave. Never emitted in
   *  read-only mode (there is nothing bound to receive it, and no visual affordance invites hover
   *  there — see `detections-strip.html`'s two chip render branches). */
  readonly hoveredClassChange = output<string | null>();

  /** Emitted after a successful click-to-hide PATCH — the host (`CockpitFacade#refreshStreamConfig`)
   *  re-reads `GET .../config` so {@link labelDenyFilter} always renders the wire (H6). */
  readonly configChanged = output<void>();

  /** The honest, once-shown disclosure for the click-to-hide affordance — identical wording to
   *  `CvControlPanel`'s own class-chip checklist (`core/detections/detections-logic.ts`), so the two
   *  hide surfaces in the merged Vision drawer never say something different. */
  protected readonly hiddenClassTruth = HIDDEN_CLASS_TRUTH;

  /** The strip's own candidate set — recency-ordered, capped, deny-list-aware only in
   *  {@link interactive} mode (read-only mode has no deny-list context of its own to be honest
   *  about — see `stripChips`'s own doc comment for why `[]` is the honest default there). */
  protected readonly chips = computed<readonly StripChip[]>(() =>
    stripChips(
      this.store.results(),
      worldObjectsByTrackId(this.store.worldObjects()),
      this.interactive() ? this.labelDenyFilter() : [],
      STRIP_CHIP_CAP,
    ),
  );

  protected onChipEnter(label: string): void {
    this.hoveredClassChange.emit(label);
  }

  protected onChipLeave(): void {
    this.hoveredClassChange.emit(null);
  }

  /** Immediate, un-staged — see class doc's own "Click" bullet for why a deny-list toggle needs no
   *  Submit step the way the allowlist checklist's bulk edits do. */
  protected onChipClick(chip: StripChip): void {
    const streamId = this.streamId();
    if (!streamId) {
      return; // defensive — the template only renders a clickable chip once streamId is set
    }
    const next = toggleLabelDeny(this.labelDenyFilter(), chip.label);
    void this.fleet.patchStreamConfig(streamId, { labelDenyFilter: next }).then((result) => {
      if (result) {
        this.configChanged.emit();
      }
    });
  }
}
