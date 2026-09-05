import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { DiscoveryCandidate } from '../../core/api/models';
import { candidateActions, candidateAgeLabel, discoveryMethodLabel } from '../../core/discovery/discovery-inbox-logic';

/**
 * One Found-devices card (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11, wave Z2d) — a
 * dumb, `OnPush` presentational component; every derivation (`candidateActions`/`candidateAgeLabel`/
 * `discoveryMethodLabel`) lives in `core/discovery/discovery-inbox-logic.ts`, unit-tested there.
 *
 * **Stale-not-live**: never renders "online" — only "last heard <age> ago" (`now` is threaded in
 * by the parent's own clock tick rather than read here via `Date.now()`, keeping this component's
 * render pure/testable and matching `fly-osd`/the picker cards' own convention).
 *
 * **Status-aware body**, mirrored from §11's own contract: `NEW` shows the action row; `REGISTERED`
 * shows a quiet link to the asset it became instead of any button; `DISMISSED` shows a Restore
 * button (W3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §11) — `DiscoveryInboxStore#restore`
 * puts it back to `NEW`, undoing a `dismiss` that has no other way back.
 */
@Component({
  selector: 'vision-found-device-card',
  imports: [RouterLink],
  templateUrl: './found-device-card.html',
  styleUrl: './found-device-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FoundDeviceCard {
  readonly candidate = input.required<DiscoveryCandidate>();
  readonly busy = input(false);
  /** The parent's own clock tick (ms) — see class doc for why this isn't `Date.now()` in here. */
  readonly now = input.required<number>();

  readonly add = output<void>();
  readonly attachTo = output<void>();
  readonly dismiss = output<void>();
  readonly restore = output<void>();

  protected readonly methodLabel = computed(() => discoveryMethodLabel(this.candidate().method));
  protected readonly ageLabel = computed(() => candidateAgeLabel(this.candidate().lastSeen, this.now()));
  protected readonly actions = computed(() => candidateActions(this.candidate()));
}
