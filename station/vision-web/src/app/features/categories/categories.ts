import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { CategoriesFacade } from './categories-facade';

/**
 * `/manage/categories` — docs/plans/done/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Asset categories": the
 * grouped/counted view (every defined category, joined against live per-category asset counts, each
 * linking into the filtered Assets page) is functional; creating/editing categories is not built
 * (only `GET /api/categories` exists server-side) — stated plainly via `<vision-notice>`, never a
 * fake "+ Add category" button.
 *
 * No role gate — browsing/filtering categories is not a management action (same openness as
 * `/assets`, which this page's own "View assets" links into).
 *
 * **`page-head` → `vision-page-bar`** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/14-categories.md):
 * unlike Assets/Devices/Activity/Alerts, the old subtitle ("Every category assets are grouped by,
 * with live counts.") carries real instruction — it's what tells a reader the numbers are live, not
 * static — so it moves to the bar's `hint` disclosure instead of being deleted. The search input
 * (only rendered once there's data to search) moves into `[pageBarFilters]`. No bar count chip: the
 * card's own `vision-section-header` already shows "Categories / N shown" immediately below the bar,
 * and a second identical number in the bar itself would be pure duplication rather than new
 * information (contrast `AssetsPage`, where the bar count and the body's "N of M" line carry two
 * different numbers). The empty-category grouping and column drop docs/extracts/design/14-categories.md
 * proposes are Wave 3+ (gated on whether create/edit endpoints exist) — left untouched.
 */
@Component({
  selector: 'vision-categories',
  imports: [FormsModule, RouterLink, PageBar, SectionHeader, EmptyState, Notice],
  templateUrl: './categories.html',
  styleUrl: './categories.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CategoriesFacade],
})
export class CategoriesPage {
  protected readonly facade = inject(CategoriesFacade);
}
