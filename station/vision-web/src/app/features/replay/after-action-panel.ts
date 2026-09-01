import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import type { IconName } from '../../shared/ui/icon-registry';
import { Notice } from '../../shared/ui/notice';
import { pluralize } from '../../shared/ui/text-logic';
import { actorLabel } from '../../core/audit/summary-logic';
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

/**
 * The count badge's own noun, singular + plural — `shared/ui/text-logic.ts#pluralize` supplies the
 * rest. **Both forms are explicit** (docs/plans/active/OPERATOR-UX-6-PLAN.md finding E3): `passport`/`audit`
 * previously passed only the singular `'entry'`, so `pluralize`'s regular-plural default silently
 * produced `entrys` — the "Included · 200 entrys" bug — since `entry`'s plural is irregular.
 */
const PART_COUNT_NOUN: Record<AfterActionPart, readonly [singular: string, plural: string]> = {
  telemetry: ['point', 'points'],
  detections: ['detection', 'detections'],
  marks: ['mark', 'marks'],
  recording: ['recording', 'recordings'],
  passport: ['entry', 'entries'],
  audit: ['entry', 'entries'],
};

/**
 * `<vision-after-action-panel>` — the evidence-package card on `ReplayPage`
 * (docs/plans/done/AFTER-ACTION-PLAN.md, wave W2). Deliberately dumb, mirroring
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

  /**
   * Collapsed by default (docs/plans/active/OPERATOR-UX-6-PLAN.md finding E1) — the evidence package is the
   * last card on `ReplayPage` now, shown as just its header line + "Download package" until a viewer
   * opens it; the manifest detail (part rows, caveats) is one click away rather than the first thing
   * the page renders. Local, ephemeral view state — mirrors `shared/ui/preflight-checklist.ts`'s own
   * `collapsed` idiom, except this component owns the toggle itself (no host needs to react to it),
   * so it is a plain signal rather than an `input`/`output` pair.
   */
  protected readonly collapsed = signal(true);

  protected readonly view = computed(() => {
    const manifest = this.manifest();
    return manifest ? buildAfterActionView(manifest) : undefined;
  });

  /**
   * `Built for {{ scopedToLabel() }}` (docs/plans/active/OPERATOR-UX-6-PLAN.md finding E2) — reuses
   * `core/audit/summary-logic.ts#actorLabel`, the same "root principal → Station, else short id"
   * rule the audit trail already applies, rather than rendering `manifest.scopedTo`'s raw UUID.
   * `ReplayFacade` holds no user roster (no page here needs one for anything else), so this passes
   * an empty names map — `actorLabel` already degrades an unresolved id to its own honest 8-char
   * short form, same posture as every other "no roster known" fallback in this app; a future page
   * that does have a roster in hand could pass it in as an input without changing this call.
   */
  protected readonly scopedToLabel = computed(() => {
    const manifest = this.manifest();
    return manifest ? actorLabel(manifest.scopedTo, new Map()) : '';
  });

  protected toggleCollapsed(): void {
    this.collapsed.update((c) => !c);
  }

  protected iconFor(part: AfterActionPart): IconName {
    return PART_ICONS[part];
  }

  protected countLabel(row: AfterActionPartRow): string {
    const [singular, plural] = PART_COUNT_NOUN[row.part];
    return pluralize(row.count, singular, plural);
  }

  /** The state chip's own text — `"Included · 214 detections"` when a count is worth showing, else just the plain state word. Built here (not templated inline) so the template never mixes a bare interpolation with an inline `@if`. */
  protected badgeText(row: AfterActionPartRow): string {
    return row.showCount ? `${row.stateLabel} · ${this.countLabel(row)}` : row.stateLabel;
  }

  protected formatInstant(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
