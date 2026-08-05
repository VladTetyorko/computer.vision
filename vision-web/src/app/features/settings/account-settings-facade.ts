import { Injectable, inject } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ThemeStore } from '../../core/shell/theme-store';
import { ToastService } from '../../core/toast.service';

/**
 * `AccountSettingsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — the **per-account** half of what
 * used to be one combined `SettingsFacade`/`SettingsPage` (docs/NAV-IA-REDESIGN-PLAN.md §2.5,
 * docs/design/11-settings.md, Wave 4's F7 split): Interface (Advanced mode) and Notifications, plus
 * the read-only System status card. Detection profile/model/the raw confidence-fps knobs — the
 * **fleet-wide** half, applied to every stream anyone starts — moved to `DetectionSettingsFacade`
 * (`detection-settings-facade.ts`): a pilot flipping their own "advanced mode" and a manager changing
 * the fleet's detection model are different acts with different blast radii (F7's own framing), so
 * they no longer share one facade any more than they now share one page/route.
 *
 * **System stays here, not on the detection page** — the design doc's own suggested layout
 * (docs/design/11-settings.md) doesn't mention this card at all, so its home isn't spec'd either way.
 * It is read-only fleet status (backend reachability, device/stream counts, a phase blurb), has zero
 * blast radius of its own, and reads more like "how's my session doing" than a setting — closer in
 * spirit to Interface/Notifications than to a fleet-wide default, so it stays on the account page.
 *
 * **Tiles-per-row is gone from here entirely** (docs/design/11-settings.md's own "delete, don't
 * duplicate" call, task 5) — `SettingsStore.wallDensity` is still the one signal both this app and
 * the Wall read/write; only this page's second `<select>` control is deleted.
 * `features/wall/wall.html`'s own `[pageBarFilters]` control (`WallFacade.setDensity`) is untouched
 * and is now the *only* place that writes it.
 */
@Injectable()
export class AccountSettingsFacade {
  readonly settings = inject(SettingsStore);
  readonly fleet = inject(FleetStore);
  /** Backs the page's own "Appearance" section (docs/VISUAL-REFRESH-PLAN.md F3/Wave 1) — the same
   *  `ThemeStore` the sidebar-footer switch calls directly, injected here instead because
   *  `AccountSettingsPage` **is** a routed page (`core/ui/architecture.spec.ts`'s "injects only its
   *  facade, never a bare `*Store`" guard scans every `ROUTED_PAGES` entry, and this route is one of
   *  them) — `account-settings.html` reads/writes it as `facade.theme.theme()`/
   *  `facade.theme.setTheme(...)`, the same direct-field idiom `facade.settings`/`facade.fleet`
   *  already use above rather than this class growing passthrough wrapper methods. */
  readonly theme = inject(ThemeStore);
  private readonly toasts = inject(ToastService);

  /**
   * The browser's own grant, read once per render rather than tracked as a signal — this app has
   * no precedent for polling `Notification.permission` for external changes (a user revoking the
   * grant from browser chrome mid-session is a rare, refresh-recoverable edge case, not worth a
   * new polling concern), and `'unsupported'` covers a browser/context with no `Notification`
   * global at all (some embedded webviews, most notably).
   */
  notificationPermission(): NotificationPermission | 'unsupported' {
    return typeof Notification === 'undefined' ? 'unsupported' : Notification.permission;
  }

  toggleAdvanced(checked: boolean): void {
    this.settings.advancedMode.set(checked);
  }

  /**
   * Turning the toggle **off** never touches the browser permission — just this app's own opt-in.
   * Turning it **on** is the one and only place `Notification.requestPermission()` is called
   * (docs/MVP2-PLAN.md §E, E-b bullet 4): most browsers require a direct user gesture to show the
   * permission prompt at all, and a settings checkbox click is exactly that — `core/events/events-store.ts`
   * never calls `requestPermission()` itself, only reads whatever `Notification.permission` already
   * is. A `'default'` grant prompts; `'denied'`/a post-prompt refusal leaves the setting off with an
   * explanatory toast rather than silently flipping the checkbox back with no reason given.
   */
  async toggleEventNotifications(checked: boolean): Promise<void> {
    if (!checked) {
      this.settings.eventNotifications.set(false);
      return;
    }
    if (typeof Notification === 'undefined') {
      this.toasts.error('This browser does not support notifications.');
      return;
    }
    let permission = Notification.permission;
    if (permission === 'default') {
      permission = await Notification.requestPermission();
    }
    if (permission === 'granted') {
      this.settings.eventNotifications.set(true);
    } else {
      this.toasts.error('Notifications are blocked for this site — allow them in your browser settings first.');
    }
  }
}
