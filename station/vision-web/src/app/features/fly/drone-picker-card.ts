import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { lastSeenLabel, positionLabel, streamStateLabel } from './fly-logic';
import { offlineLabel } from './drone-picker-logic';
import type { AssetSummary } from '../../core/api/models';

/**
 * One `/fly` picker card (docs/plans/active/OPERATOR-UX-3-PLAN.md wave T1) — split out of
 * `DronePickerPage` so the same markup renders under both of `drone-picker.html`'s groups ("Your
 * vehicles" / "Simulated") without duplicating it, mirroring this app's existing "dumb presentational
 * child" precedent (`shared/ui/event-row.ts`, `shared/ui/stat.ts`) rather than a second `@for` copy
 * or an `NgTemplateOutlet` context — a plain `[asset]` input keeps this a three-file component with
 * no wiring risk (docs/plans/done/UI-ARCHITECTURE-PLAN.md: "component specs only for wiring bugs a
 * pure spec cannot reach" — there is none here, every derivation is a pure function already covered
 * by `fly-logic.spec.ts`/`drone-picker-logic.spec.ts`).
 *
 * Card facts (last seen, position) are unchanged from the pre-T1 card (docs/plans/done/UX-REWORK-PLAN.md
 * §U-a2 §3) — only the status chip's text changes: streaming keeps `streamStateLabel`'s bare
 * "Streaming" + the live dot, but an offline card now reads its honest age via
 * `drone-picker-logic.ts#offlineLabel` ("Offline · 2h 29m" / "Offline · 6d" / "Never seen") instead
 * of the bare word "Offline" every card used to show regardless of how stale it was (T1's own
 * finding: "17 identical 'Offline' cards").
 */
@Component({
  selector: 'vision-drone-picker-card',
  imports: [RouterLink],
  templateUrl: './drone-picker-card.html',
  styleUrl: './drone-picker-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DronePickerCard {
  readonly asset = input.required<AssetSummary>();

  protected statusLabel(): string {
    const a = this.asset();
    return a.status === 'STREAMING' ? streamStateLabel(a.status) : offlineLabel(a, Date.now());
  }

  protected lastSeen(): string | undefined {
    return lastSeenLabel(this.asset().lastUsedAt, Date.now());
  }

  protected position(): string | undefined {
    return positionLabel(this.asset().lastKnownPosition);
  }
}
