import { ActiveDescendantKeyManager, type Highlightable } from '@angular/cdk/a11y';
import { ChangeDetectionStrategy, Component, Injector, computed, inject, input, output, signal } from '@angular/core';
import { IconButton } from '../../shared/ui/icon-button';
import type { DebugEndpoint } from './debug-endpoints';
import { statusFamily, type DebugHistoryEntry } from './debug-history';

/** One row of the rail's single combined listbox — a catalog endpoint or a history entry, never both. */
interface RailOption extends Highlightable {
  readonly id: string;
  readonly kind: 'endpoint' | 'history';
  readonly endpoint?: DebugEndpoint;
  readonly entry?: DebugHistoryEntry;
}

/**
 * The `/debug` console's rail (docs/plans/active/fly-debug-redesign/PLAN.md §1.1) — one recall
 * surface, two labeled groups: the fixed 13-endpoint catalog, then request history (most recent
 * first). A dumb, OnPush, fully-inputs/outputs component; `DebugPage` owns every signal this rail
 * reads or produces (endpoints/history/selection), it never fetches or mutates anything itself.
 *
 * **Keyboard nav sources from `cdk/a11y`'s `ActiveDescendantKeyManager`** (§1.4 — `@angular/aria`'s
 * Listbox isn't installed; see `.claude/skills/frontend-style/SKILL.md` §11 and this plan's own
 * header note). Catalog + history render as one flat `role="listbox"` (two `role="group"` sections
 * inside it, the ARIA APG's own "grouped listbox" shape) so arrow keys move across the whole rail,
 * not two separate lists — matching the plan's "one recall surface" framing. The container itself
 * holds DOM focus (`tabindex="0"`); `aria-activedescendant` points at whichever row is highlighted,
 * and each row's own highlight styling is driven by `activeOptionId`, which `Highlightable`'s
 * `setActiveStyles`/`setInactiveStyles` callbacks (wired per option below) update — that highlight
 * is deliberately a different visual (`.active`, a plain hover-tint) from `.selected`'s 2px
 * `--color-info` inset bar (frontend-style §4), which marks only the catalog entry currently loaded
 * into the request bar, never a history row and never mere keyboard position.
 *
 * Enter/Space aren't navigation keys, so `ListKeyManager.onKeydown` doesn't handle them itself —
 * `onRailKeydown` below intercepts those two first and activates whatever's current, then delegates
 * everything else (arrows, Home/End) to the key manager. That's the entire state machine; nothing
 * here hand-rolls ARIA beyond wiring the two callbacks the library's own `Highlightable` interface
 * requires.
 */
@Component({
  selector: 'vision-debug-rail',
  imports: [IconButton],
  templateUrl: './debug-rail.html',
  styleUrl: './debug-rail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DebugRail {
  private readonly injector = inject(Injector);

  readonly endpoints = input.required<readonly DebugEndpoint[]>();
  readonly history = input.required<readonly DebugHistoryEntry[]>();
  readonly historyLimit = input(0);
  readonly selectedEndpointId = input('');
  /** Guards the per-row "send again" button against a second fire while a request is in flight. */
  readonly sending = input(false);

  /** Picking a catalog entry only prefills the request bar (Swagger's "Try it out" opt-in, R1 §2) —
   *  it never sends. */
  readonly endpointPicked = output<string>();
  /** A history row's own click/Enter — silent refill, the row's *only* built-in interaction. */
  readonly historyRefilled = output<DebugHistoryEntry>();
  /** The hover/focus-revealed icon button — the one and only thing that re-sends a past request. */
  readonly historySent = output<DebugHistoryEntry>();

  protected readonly statusFamily = statusFamily;

  /** Which row is currently keyboard-highlighted (by this rail's own generated row id), or `null`
   *  before the rail has ever been navigated — drives both the `.active` class and
   *  `aria-activedescendant`. */
  protected readonly activeOptionId = signal<string | null>(null);

  protected readonly railOptions = computed<RailOption[]>(() => [
    ...this.endpoints().map((endpoint) => this.buildOption(`rail-opt-endpoint-${endpoint.id}`, 'endpoint', endpoint, undefined)),
    ...this.history().map((entry, index) => this.buildOption(`rail-opt-history-${index}`, 'history', undefined, entry)),
  ]);

  private readonly keyManager = new ActiveDescendantKeyManager<RailOption>(this.railOptions, this.injector)
    .withVerticalOrientation(true)
    .withWrap(true)
    .withHomeAndEnd(true);

  private buildOption(
    id: string,
    kind: 'endpoint' | 'history',
    endpoint: DebugEndpoint | undefined,
    entry: DebugHistoryEntry | undefined,
  ): RailOption {
    const active = this.activeOptionId;
    return {
      id,
      kind,
      endpoint,
      entry,
      setActiveStyles: () => active.set(id),
      setInactiveStyles: () => {
        if (active() === id) {
          active.set(null);
        }
      },
    };
  }

  /** Focusing the rail (Tab, or a click that lands on the container rather than a row) highlights
   *  something immediately, per the ARIA APG listbox pattern, rather than leaving the first arrow
   *  press to do it. */
  protected onRailFocus(): void {
    if (!this.keyManager.activeItem) {
      this.keyManager.setFirstItemActive();
    }
  }

  protected onRailKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.activate(this.keyManager.activeItem);
      return;
    }
    this.keyManager.onKeydown(event);
  }

  protected onOptionClick(index: number, option: RailOption): void {
    this.keyManager.setActiveItem(index);
    this.activate(option);
  }

  /** The template also binds a plain `(click)="$event.stopPropagation()"` on the icon button itself
   *  — its native click still bubbles through the row's own `(click)` (silent refill) otherwise,
   *  firing both actions from one press. */
  protected onSendAgain(entry: DebugHistoryEntry): void {
    if (this.sending()) {
      return;
    }
    this.historySent.emit(entry);
  }

  private activate(option: RailOption | null): void {
    if (!option) {
      return;
    }
    if (option.kind === 'endpoint' && option.endpoint) {
      this.endpointPicked.emit(option.endpoint.id);
    } else if (option.kind === 'history' && option.entry) {
      this.historyRefilled.emit(option.entry);
    }
  }
}
