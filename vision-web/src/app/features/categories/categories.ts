import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { CategoriesFacade } from './categories-facade';

/**
 * `/manage/categories` — docs/UI-REDESIGN-PLAN.md Wave 4's **SPLIT** "Asset categories": the
 * grouped/counted view (every defined category, joined against live per-category asset counts, each
 * linking into the filtered Assets page) is functional; creating/editing categories is not built
 * (only `GET /api/categories` exists server-side) — stated plainly via `<vision-notice>`, never a
 * fake "+ Add category" button.
 *
 * No role gate — browsing/filtering categories is not a management action (same openness as
 * `/assets`, which this page's own "View assets" links into).
 */
@Component({
  selector: 'vision-categories',
  imports: [FormsModule, RouterLink, Icon, SectionHeader, EmptyState, Notice],
  templateUrl: './categories.html',
  styleUrl: './categories.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CategoriesFacade],
})
export class CategoriesPage {
  protected readonly facade = inject(CategoriesFacade);
}
