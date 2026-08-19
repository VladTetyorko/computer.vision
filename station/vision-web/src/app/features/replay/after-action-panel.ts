import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import type { IconName } from '../../shared/ui/icon-registry';
import { Notice } from '../../shared/ui/notice';
import { pluralize } from '../../shared/ui/page-bar/page-bar';
import type { AfterActionManifest, AfterActionPart } from '../../core/api/models';
import { buildAfterActionView, type AfterActionPartRow } from '../../core/after-action/after-action-logic';

const PART_ICONS: Record<AfterActionPart, IconName> = {
  telemetry: 'gauge',
  detections: 'scan',
  marks: 'target',
  recording: 'source',
  passport: 'report',
  audit: 'shield',
};

/** The count badge's own noun, singular — `shared/ui/page-bar/page-bar.ts#pluralize` supplies the rest. */
const PART_COUNT_NOUN: Record<AfterActionPart, string> = {
  telemetry: 'point',
  detections: 'detection',
  marks: 'mark',
  recording: 'recording',
  passport: 'entry',
  audit: 'entry',
};

/**
 * `<vision-after-action-panel>` — the evidence-package card on `ReplayPage`
 * (docs/plans/active/AFTER-ACTION-PLAN.md, wave W2). Deliberately dumb, mirroring
 * `shared/ui/preflight-checklist.ts`'s own "items in, no store/DI" shape: everything it renders is
 * `manifest` transformed through the pure `core/after-action/after-action-logic.ts`, plus the three
 * plain load-state inputs `ReplayFacade` already tracks the same way it tracks the recording pane
 * (`loading`/`errorMessage`) — a failed after-action read is an enrichment-read failure, never a
 * blocked page (this app's own "degrade honestly" rule): it only ever shrinks this one card to a
 * quiet inline message, the rest of `ReplayPage` is unaffected either way.
 *
 * **Rendered for both a finished flight and a still-open one** (§5 hazard 4 — "a still-open usage is
 * a valid input, do not 404 it"): unlike the timeline/scrub cockpit above it, which redirects an open
 * usage to a "Watch live" notice instead of rendering, the evidence package is genuinely servable
 * mid-flight, so `replay.html` mounts this panel in both the `usageOpen` and the loaded-timeline
 * branches — a referee can see the evidence build up before the flight even ends.
 *
 * **The tone rule this whole wave exists to get right**: `TRUNCATED`/`FORBIDDEN` render with the
 * exact same neutral, unstyled `.chip` as `ABSENT` — only `PRESENT` earns the green `.chip.ok` — per
 * `after-action-logic.ts#partTone`'s own doc comment. The distinction between the three calm states
 * lives entirely in `stateLabel`'s words ("Not available" / "Thinned" / "Not visible to you"), never
 * in color, and never behind a disclosure toggle — every row's own note renders inline, always.
 *
 * **"Download package" is a plain `<a href download>`** (rule 3 of this wave's brief) — `archiveUrl`
 * is `VisionApi#afterActionArchiveUrl`'s own plain URL builder, never fetched through `HttpClient`
 * into memory; the browser owns the download exactly like the existing "Download clip" control two
 * cards up in `replay.html` already does.
 */
@Component({
  selector: 'vision-after-action-panel',
  imports: [Icon, Notice],
  templateUrl: './after-action-panel.html',
  styleUrl: './after-action-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AfterActionPanel {
  readonly manifest = input<AfterActionManifest | undefined>(undefined);
  readonly loading = input(false);
  readonly errorMessage = input<string | undefined>(undefined);
  /** `undefined` hides the download control entirely — never a dead link (mirrors `downloadClipUrl`'s own convention in `replay-facade.ts`). */
  readonly archiveUrl = input<string | undefined>(undefined);

  protected readonly view = computed(() => {
    const manifest = this.manifest();
    return manifest ? buildAfterActionView(manifest) : undefined;
  });

  protected iconFor(part: AfterActionPart): IconName {
    return PART_ICONS[part];
  }

  protected countLabel(row: AfterActionPartRow): string {
    return pluralize(row.count, PART_COUNT_NOUN[row.part]);
  }

  /** The state chip's own text — `"Included · 214 detections"` when a count is worth showing, else just the plain state word. Built here (not templated inline) so the template never mixes a bare interpolation with an inline `@if`. */
  protected badgeText(row: AfterActionPartRow): string {
    return row.showCount ? `${row.stateLabel} · ${this.countLabel(row)}` : row.stateLabel;
  }

  protected formatInstant(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
