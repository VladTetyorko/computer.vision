import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { CvTraceStore } from '../../core/cv-trace/cv-trace-store';
import type { ActiveStream, FrameLedger, GateDecision } from '../../core/api/models';
import { clockTime, formatRecord } from './cv-inspector-logic';
import { CvInspectorFacade } from './cv-inspector-facade';

/**
 * `/manage/cv` — the engineer inspector (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.8, wave W5.3;
 * §9 decision 3: "`/manage/cv` page only" — no fly-drawer tab). Four panels over one picked, running
 * stream's {@link CvTraceStore}, plus the whole-process facts panel (stream-independent, see
 * {@link CvInspectorFacade}'s own doc comment).
 *
 * <h2>Acceptance — every §1.5 "why?" question, one lookup on this page</h2>
 * Transcribed from the plan's own "what the why table looks like afterwards" (§4.4) — the wave is
 * only done once every row below is a real lookup here, not a promise:
 *
 * | Question | Where on this page |
 * |---|---|
 * | Why no boxes? | **Gate ledger** panel's `reason` column, or the **Frame contributors** panel's `detect.*` row, or the **Process facts** panel's health/detail sentence — three distinct, honest answers, never conflated into one |
 * | Why is this box not drawn? | **Object evidence** panel's world-facet line — `render.tier` |
 * | Why did the detector not run this frame? | **Frame contributors** panel's header — `detectorReason` |
 * | Why did the id change / die? | **Object evidence** panel — the selected track's own `state.lifecycle`, joined with every claim recorded about it across the ring |
 * | Why did follow lose it? | **Object evidence** panel's world-facet line — `operator.follow` |
 * | Running with nobody watching? | **Gate ledger** panel's demand-snapshot columns (`detectionEnabled` / `viewerDemand` / `policyAlwaysOn`) |
 * | Ego-motion / each stage's cost? | **Frame contributors** panel's `entries` table — one row per contributor, its own `costMillis` |
 * | Healthy? | **Process facts** panel — the `cv-service` row of `/api/system/status`, health + one sentence |
 *
 * **Frontend-style law**: this is a data/table surface, not a video one — the dark-video-surface
 * rule does not apply here (`.claude/skills/frontend-style/SKILL.md`'s own scope note); the calm
 * side-panel/table rules do, and are followed via the shared `.card`/`table`/`.chip` primitives, the
 * same ones `/manage/system` (`features/system-status/**`) already renders diagnostics with.
 */
@Component({
  selector: 'vision-cv-inspector',
  imports: [EmptyState, PageBar],
  templateUrl: './cv-inspector.html',
  styleUrl: './cv-inspector.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CvTraceStore, CvInspectorFacade],
})
export class CvInspectorPage {
  protected readonly facade = inject(CvInspectorFacade);

  /** Pure lookups called directly from the template — not store/service state (`core/ui/architecture
   *  .spec.ts`'s own "injects only its facade" rule treats this the same as `system-status.ts`'s
   *  identical `healthLabel`/`healthSeverity` re-exports: this isn't an injection). */
  protected readonly formatRecord = formatRecord;
  protected readonly clockTime = clockTime;

  protected onStreamChange(event: Event): void {
    this.facade.selectStream((event.target as HTMLSelectElement).value);
  }

  protected onFrameChange(event: Event): void {
    this.facade.selectFrame(Number((event.target as HTMLSelectElement).value));
  }

  protected onTrackChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value !== '') {
      this.facade.selectTrack(Number(value));
    }
  }

  protected trackStream(_index: number, stream: ActiveStream): string {
    return stream.streamId;
  }

  protected trackGateRow(index: number, row: GateDecision): string {
    return `${row.frameSequence}-${index}`;
  }

  protected trackFrameOption(_index: number, frame: FrameLedger): number {
    return frame.sequence;
  }

  protected trackEntry(index: number, entry: { contributorId: string }): string {
    return `${entry.contributorId}-${index}`;
  }

  protected trackEvidenceRow(index: number, row: { contributorId: string; frameSequence: number }): string {
    return `${row.frameSequence}-${row.contributorId}-${index}`;
  }

  protected trackId(_index: number, id: number): number {
    return id;
  }
}
