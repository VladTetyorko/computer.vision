import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { PreflightChecklist } from '../fly/preflight-checklist';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { PreflightFacade } from './preflight-facade';

/**
 * `/operate/preflight` — docs/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Pre-flight checklist": pick
 * any drone and see its live status card (`<vision-preflight-checklist>`, unmodified — the exact
 * component the Fly cockpit itself mounts), fed by `derivePreflight`'s same 5 always-present rows
 * (Video feed, Telemetry link, GPS fix, Battery, Armable). Saved, editable checklist templates are
 * not built (named follow-up: a checklist-template entity + CRUD endpoint, zero backend hits today)
 * — stated plainly via `<vision-notice>`, never a fake "Save as template" button.
 *
 * No role gate — reading a drone's own status is not a management action (same openness as `/fly`).
 *
 * **Header** (docs/NAV-IA-REDESIGN-PLAN.md §2.2): `page-head` is now `<vision-page-bar>`; the old
 * paragraph carried real instruction so it moved behind the bar's `?` hint instead of being
 * dropped. The `DRONE` select rides `[pageBarFilters]`, gated on the same loaded/has-assets
 * condition the body's own `@else` branch uses.
 *
 * **This page itself stays on the app theme** (docs/VISUAL-REFRESH-PLAN.md F3/W4) — it's a plain
 * data page (page-bar, a notice, a `.card` frame), never full-bleed video, so it never carries
 * `.surface-dark`. The one thing on it that must always look dark — the embedded
 * `<vision-preflight-checklist>`, kept deliberately cockpit-styled here too (see `preflight.html`'s
 * own comment) — now owns that itself; see that component's doc comment.
 */
@Component({
  selector: 'vision-preflight',
  imports: [FormsModule, RouterLink, EmptyState, Notice, PreflightChecklist, PageBar],
  templateUrl: './preflight.html',
  styleUrl: './preflight.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore, PreflightFacade],
})
export class PreflightPage {
  protected readonly facade = inject(PreflightFacade);
}
