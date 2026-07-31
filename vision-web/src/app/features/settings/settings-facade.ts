import { Injectable, computed, inject, signal } from '@angular/core';
import { BUILT_IN_PROFILES, SettingsStore } from '../../core/settings/settings-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';

/**
 * `SettingsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — orchestrates `SettingsStore`/`FleetStore`/
 * `ToastService`, exactly the three services the page used to inject directly. Every read-model and
 * command below is byte-for-byte what `SettingsPage` owned before this refactor; see
 * docs/CV-CONTROL-PLAN.md §4 for the model-picker background this page's own doc comment (now here)
 * still applies to.
 */
@Injectable()
export class SettingsFacade {
  readonly settings = inject(SettingsStore);
  readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);

  readonly newProfileName = signal('');

  readonly modelOptions = computed(() => this.fleet.models());

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

  /** Backend defaults, for the "what does this preset change" comparison. */
  readonly defaults = BUILT_IN_PROFILES[0];

  readonly deltaFromDefaults = computed(() => {
    const current = this.settings.effective();
    const changes: string[] = [];
    if (current.confidenceThreshold !== this.defaults.confidenceThreshold) {
      changes.push(
        `confidence ${this.defaults.confidenceThreshold} → ${current.confidenceThreshold}`,
      );
    }
    if (current.inferenceFps !== this.defaults.inferenceFps) {
      changes.push(`inference ${this.defaults.inferenceFps} → ${current.inferenceFps} fps`);
    }
    if (current.model !== this.defaults.model) {
      changes.push(`model ${this.modelLabel(this.defaults.model)} → ${this.modelLabel(current.model)}`);
    }
    return changes;
  });

  /** Also used by the template to show a profile's model in its compact values line — degrades to
   * the bare id when the roster hasn't loaded yet (or no longer lists it), never a blank/fabricated
   * label (docs/CV-CONTROL-PLAN.md Wave E — the roster replaced the old closed `DetectionModelId` set). */
  modelLabel(id: string): string {
    return this.modelOptions().find((option) => option.id === id)?.displayName ?? id;
  }

  readonly customProfiles = computed(() => this.settings.customProfiles());

  onProfileChange(id: string): void {
    this.settings.selectProfile(id);
  }

  onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }

  onModel(model: string): void {
    this.settings.adjust({ model });
  }

  saveAs(): void {
    this.settings.saveDraftAs(this.newProfileName());
    this.newProfileName.set('');
  }

  toggleAdvanced(checked: boolean): void {
    this.settings.advancedMode.set(checked);
  }

  setDensity(value: string): void {
    this.settings.wallDensity.set(Number(value));
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
