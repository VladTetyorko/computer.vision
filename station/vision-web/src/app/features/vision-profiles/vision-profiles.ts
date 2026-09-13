import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { SectionHeader } from '../../shared/ui/section-header';
import type { BindingScope, CvCoverageRow, CvProfile } from '../../core/api/models';
import {
  DETECTION_SELECT_INHERIT,
  DETECTION_SELECT_OFF,
  DETECTION_SELECT_ON,
  ENGINE_SELECT_DEPLOYMENT_DEFAULT,
  ENGINE_SELECT_INHERIT,
  TRACKING_MODE_SELECT_INHERIT,
  describeCoverageFilters,
  describeCoverageSource,
  describeDetectionCardState,
  describeOptionalModel,
  describeOptionalNumber,
  detectionSelectValue,
  engineSelectValue,
  isModelMissingOnWorker,
  parseDetectionSelectValue,
  parseEngineSelectValue,
  parseTrackingModeSelectValue,
  trackingModeSelectValue,
} from './vision-profiles-logic';
import { VisionProfilesFacade } from './vision-profiles-facade';

/**
 * `/vision/profiles` — **Profiles** (docs/plans/active/CV-SETTINGS-PLAN.md §4, wave W6): the
 * per-stream CV config (model, thresholds, allow/deny label filters, tracking, the start-time-only
 * event rule) as a named, reusable, bindable thing, replacing the old single-org "detection defaults"
 * page (`/settings/detection`, now a redirect here — `settings.routes.ts`).
 *
 * Four sections, top to bottom: **Profiles** (cards — built-in ones marked and not editable, "Fork"
 * copies any profile into an editable one), the **editor** (opens inline below the cards when
 * creating/editing/forking), **Bindings** (attach a profile at ORGANIZATION/CATEGORY/ASSET scope),
 * and **Coverage** (`GET /api/cv/coverage`'s fleet-wide resolved-profile table). Route-guarded
 * (`orgGuard`, same as `/manage/training/models`) — reading is manager-only here (unlike the model
 * registry), since a profile's bindings can reveal another team's fleet composition; every write
 * action additionally hides client-side for a non-manager (`VisionProfilesFacade.canManage`,
 * `canManageOrg`). **Dev parity**: `vision.auth.enabled=false` resolves to `ADMIN`/unbounded, so this
 * page behaves exactly as it does for a real admin.
 *
 * **Start-time only, stated once**: a profile resolves at stream start and a running stream keeps
 * whatever it started with (§3.1) — the page-bar's `hint` carries this note rather than a permanent
 * banner, `vision-page-bar`'s own "instructional text worth keeping" slot.
 *
 * Degrades honestly (§3.5): the whole page is one `Promise.all` read (`VisionProfilesFacade.load`) —
 * any failure renders one `vision-notice` + Try again, never a page assembled from some real rows and
 * some fabricated ones; a model whose `availability` reads `'MISSING'` shows "Missing on worker" in
 * the editor's model picker rather than silently falling back to another model.
 */
@Component({
  selector: 'vision-profiles-page',
  imports: [FormsModule, EmptyState, Icon, Notice, PageBar, SectionHeader],
  templateUrl: './vision-profiles.html',
  styleUrl: './vision-profiles.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [VisionProfilesFacade],
})
export class VisionProfilesPage {
  protected readonly facade = inject(VisionProfilesFacade);
  protected readonly scopeKinds: readonly BindingScope[] = ['ORGANIZATION', 'CATEGORY', 'ASSET'];
  protected readonly describeCoverageSource = describeCoverageSource;
  protected readonly describeCoverageFilters = describeCoverageFilters;
  protected readonly isModelMissingOnWorker = isModelMissingOnWorker;
  protected readonly describeOptionalModel = describeOptionalModel;
  protected readonly describeOptionalNumber = describeOptionalNumber;
  protected readonly describeDetectionCardState = describeDetectionCardState;

  // --- Editor tri-state `<select>` sentinels/conversions (wave W7, decision E22) — see
  // `vision-profiles-logic.ts`'s own doc comments for why each knob below needs one. ---------------
  protected readonly trackingModeSelectValue = trackingModeSelectValue;
  protected readonly parseTrackingModeSelectValue = parseTrackingModeSelectValue;
  protected readonly trackingModeInherit = TRACKING_MODE_SELECT_INHERIT;
  protected readonly engineSelectValue = engineSelectValue;
  protected readonly parseEngineSelectValue = parseEngineSelectValue;
  protected readonly engineInherit = ENGINE_SELECT_INHERIT;
  protected readonly engineDeploymentDefault = ENGINE_SELECT_DEPLOYMENT_DEFAULT;
  protected readonly detectionSelectValue = detectionSelectValue;
  protected readonly parseDetectionSelectValue = parseDetectionSelectValue;
  protected readonly detectionInherit = DETECTION_SELECT_INHERIT;
  protected readonly detectionOn = DETECTION_SELECT_ON;
  protected readonly detectionOff = DETECTION_SELECT_OFF;

  protected trackByProfileId(_index: number, profile: CvProfile): string {
    return profile.id;
  }

  protected trackByCoverageRow(_index: number, row: CvCoverageRow): string {
    return row.assetId;
  }

  protected scopeKindLabel(kind: BindingScope): string {
    switch (kind) {
      case 'ORGANIZATION':
        return 'Organization';
      case 'CATEGORY':
        return 'Category';
      case 'ASSET':
        return 'Asset';
    }
  }
}
