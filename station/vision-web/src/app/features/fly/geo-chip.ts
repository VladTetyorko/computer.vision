import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { GeoStore } from '../../core/geo/geo-store';
import { geoChipLabel, geoChipTone, geoDetailRows } from '../../core/geo/geo-logic';
import { Icon } from '../../shared/ui/icon';

/**
 * The Fly cockpit's visual-geolocation divergence chip (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8,
 * wave H6) — embedded in `fly-osd.ts`'s Nav cluster, DI-sharing whichever {@link GeoStore} instance
 * `CockpitPage` provided, the exact same "second consumer → a shared, dumb, DI-sharing component"
 * idiom `shared/ui/weather-chip.ts` already established for the Env cluster's own chip. Takes no
 * data inputs at all.
 *
 * **Chip label/tone come straight from `geo-logic.ts`** (already unit-tested there): `'GEO ok'`
 * (ok tone) while a fix exists and isn't divergent, `'GEO Δ 84 m'` (warn tone) while divergent —
 * **never** a `'danger'` tone, even then (§3.8's own "never red for PROBABLE", extended to
 * divergence generally — `GeoChipTone` has no `'danger'` member at all, so this can't regress by
 * accident) — and `'GEO —'` (dim) whenever `latest()` is `undefined`, the ordinary "no fix
 * computed yet for this asset" case.
 *
 * **Absent, not dim, while the feature is off.** `GeoStore.disabled()` is the one case that must
 * render *nothing at all* rather than a `'GEO —'` chip — VISUAL-GEO-V2-PLAN.md §3.8's own "Off
 * state: every geo surface is absent, not empty" row. `latest() === undefined` alone cannot tell
 * "no fix yet" apart from "the flag is off"; `disabled()` is the store's own answer to exactly that
 * (set once a poll has actually observed the D9 409 envelope).
 *
 * **Detail popover** — click the chip to reveal every §3.8-listed fact (`geoDetailRows`): status,
 * separation, radius, inliers/ratio, sequence spread, region, and — only on a `NO_FIX` row — the
 * refusal string **verbatim**, never a friendly paraphrase (§5 H6's own test pin). A second click
 * (or clicking the chip again) closes it — the same plain toggle-button idiom
 * `features/fly/diagnostics-card.ts`'s own disclosure chevron already uses in this same OSD strip,
 * not a full click-outside/Escape overlay (this is a small, low-stakes popover, not a shell menu —
 * `core/ui/overlay-store.ts#GlobalOverlayStore` is reserved for shell-level chrome, per that class's
 * own doc comment).
 */
@Component({
  selector: 'vision-geo-chip',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './geo-chip.html',
  styleUrl: './geo-chip.css',
})
export class GeoChip {
  private readonly geo = inject(GeoStore);

  protected readonly disabled = computed(() => this.geo.disabled());
  protected readonly latest = computed(() => this.geo.latest());
  protected readonly label = computed(() => geoChipLabel(this.latest()));
  protected readonly tone = computed(() => geoChipTone(this.latest()));
  /** `[]` (not a call with a fabricated correction) until this asset actually has one — `geo-chip.html`'s own "No correction computed yet" branch covers that case. */
  protected readonly rows = computed(() => {
    const correction = this.latest();
    return correction ? geoDetailRows(correction) : [];
  });

  protected readonly expanded = signal(false);

  toggle(): void {
    this.expanded.update((open) => !open);
  }
}
