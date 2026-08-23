import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { DetectionsStore } from '../../core/detections/detections-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { HIDDEN_CLASS_TRUTH, toggleLabelDeny } from '../../core/detections/detections-logic';
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
 * - **Click** a chip toggles it in `SettingsStore`'s `labelDenyFilter` and PATCHes
 *   `FleetStore.patchStreamConfig` immediately — the honest, one-time disclosure
 *   ({@link hiddenClassTruth}) is shown once, at rest, whenever this mode is active. **Never** touches
 *   `labelFilter` (the allowlist) — see `toggleLabelDeny`'s own doc comment
 *   (`core/detections/detections-logic.ts`) for why a deny-list needs no staging the way the
 *   allowlist does. `chip.label` is now (docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 2) the
 *   sticky/elected label for a tracked class, not necessarily cv-service's raw per-frame label — the
 *   PATCH deliberately sends that **displayed** string, since it is what the operator is actually
 *   pointing at when they click. Until cv-service's own L1 election ships, this can transiently
 *   under-suppress a still-flipping track (a raw label the operator never saw as a chip keeps slipping
 *   through the server-side deny filter for a beat) — an accepted, honestly-scoped gap of a
 *   client-side stopgap, closed for free once L1 lands (see `detection-overlay-logic.ts`'s own sticky-
 *   label section header for why the two converge).
 *
 * A denied label can never reappear in `DetectionsStore.results()` at all — `StreamPipeline`'s single
 * drop site runs pre-fan-out (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2) — so the candidate set
 * ({@link chips}, `shared/player/detections-strip-logic.ts#stripChips`) unions the operator's own
 * deny-list in from `SettingsStore` on top of `results`, the same reasoning
 * `features/fly/cv-control-panel-logic.ts#chipCandidates` already applies to its own checklist.
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
  protected readonly store = inject(DetectionsStore);
  private readonly settings = inject(SettingsStore);
  private readonly fleet = inject(FleetStore);

  /** The running stream's id — binding this switches the strip into its interactive mode (see class
   *  doc). Left unbound, the strip stays exactly the plain read-only list it has always been. */
  readonly streamId = input<string | undefined>(undefined);
  protected readonly interactive = computed(() => !!this.streamId());

  /** Emitted on hover-enter/leave while {@link interactive} — `null` on leave. Never emitted in
   *  read-only mode (there is nothing bound to receive it, and no visual affordance invites hover
   *  there — see `detections-strip.html`'s two chip render branches). */
  readonly hoveredClassChange = output<string | null>();

  /** The honest, once-shown disclosure for the click-to-hide affordance — identical wording to
   *  `CvControlPanel`'s own class-chip checklist (`core/detections/detections-logic.ts`), so the two
   *  hide surfaces in the merged Vision drawer never say something different. */
  protected readonly hiddenClassTruth = HIDDEN_CLASS_TRUTH;

  /** The strip's own candidate set — recency-ordered, capped, deny-list-aware only in
   *  {@link interactive} mode (read-only mode has no deny-list context of its own to be honest
   *  about — see `stripChips`'s own doc comment for why `[]` is the honest default there). */
  protected readonly chips = computed<readonly StripChip[]>(() =>
    stripChips(this.store.results(), this.interactive() ? this.settings.effective().labelDenyFilter : [], STRIP_CHIP_CAP),
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
    const next = toggleLabelDeny(this.settings.effective().labelDenyFilter, chip.label);
    this.settings.adjust({ labelDenyFilter: next });
    void this.fleet.patchStreamConfig(streamId, { labelDenyFilter: next });
  }
}
