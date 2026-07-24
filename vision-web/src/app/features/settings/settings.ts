import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import {
  BUILT_IN_PROFILES,
  DETECTION_MODEL_OPTIONS,
  SettingsStore,
  type DetectionModelId,
} from '../../core/settings/settings-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';

/**
 * docs/CV-MODELS-PLAN.md item 4 — the model picker persists a choice (`SettingsStore`, same
 * profile/draft/custom semantics as confidence/fps) that now reaches a running stream end to end:
 * `StartStreamRequest` (`core/api/models.ts`) declares the `model` field mirroring vision-api's own
 * DTO (`StartStreamRequest.java`/`StartAssetStreamRequest.java`, whose `mergeOntoDefaults()` builds
 * a `ModelRef` from it — landed concurrently with this UI, same field name, same "raw
 * possibly-composite string, verbatim" contract), and `fleet.start()` posts `settings.effective()`
 * straight through with no destructuring in between, so the value was already riding in the JSON
 * body even before the DTO declared it. No further frontend wiring needed for this to take effect
 * on the next stream start.
 */
@Component({
  selector: 'vision-settings',
  templateUrl: './settings.html',
  styleUrl: './settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsPage {
  protected readonly settings = inject(SettingsStore);
  protected readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);

  protected readonly newProfileName = signal('');

  protected readonly modelOptions = DETECTION_MODEL_OPTIONS;

  /**
   * The browser's own grant, read once per render rather than tracked as a signal — this app has
   * no precedent for polling `Notification.permission` for external changes (a user revoking the
   * grant from browser chrome mid-session is a rare, refresh-recoverable edge case, not worth a
   * new polling concern), and `'unsupported'` covers a browser/context with no `Notification`
   * global at all (some embedded webviews, most notably).
   */
  protected notificationPermission(): NotificationPermission | 'unsupported' {
    return typeof Notification === 'undefined' ? 'unsupported' : Notification.permission;
  }

  /** Backend defaults, for the "what does this preset change" comparison. */
  protected readonly defaults = BUILT_IN_PROFILES[0];

  protected readonly deltaFromDefaults = computed(() => {
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

  /** Also used by the template to show a profile's model in its compact values line. */
  protected modelLabel(id: DetectionModelId): string {
    return this.modelOptions.find((option) => option.id === id)?.label ?? id;
  }

  protected readonly customProfiles = computed(() => this.settings.customProfiles());

  protected onProfileChange(id: string): void {
    this.settings.selectProfile(id);
  }

  protected onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }

  protected onModel(model: DetectionModelId): void {
    this.settings.adjust({ model });
  }

  protected saveAs(): void {
    this.settings.saveDraftAs(this.newProfileName());
    this.newProfileName.set('');
  }

  protected toggleAdvanced(checked: boolean): void {
    this.settings.advancedMode.set(checked);
  }

  protected setDensity(value: string): void {
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
  protected async toggleEventNotifications(checked: boolean): Promise<void> {
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
