import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ToastService } from '../../../core/toast.service';
import { Icon } from '../../../shared/ui/icon';
import { DemoApi, type DemoSeedResult, type DemoStatus } from '../demo-api';

/**
 * `<vision-demo-button>` — the green "Fill demo data" button in the sidebar foot: one click and an
 * empty platform has a fleet, a roster, assignments, geofence zones and marks to look at.
 *
 * **Renders nothing unless the backend says so.** It probes `GET /api/demo` once on construction;
 * with `vision.demo.enabled=false` that route 404s (the whole demo package is absent from the
 * Spring context) and this component stays invisible for the rest of the session — the same "hide
 * entirely rather than show a dead control" rule `shared/ui/identity-chip.ts` follows while the
 * session is still loading.
 *
 * **Deliberately not a facade + store pair.** `core/ui/architecture.spec.ts`'s
 * Component→Facade→Store→Service rule is scoped to *routed* feature pages (see that suite's own
 * doc); this is a non-routed shell affordance with one action, one boolean and no derived state, so
 * a facade would be pure ceremony — the same carve-out `shared/ui/return-home-button.ts` already
 * sits in.
 *
 * **Slow on purpose.** Seeding starts real video streams, so a press can take seconds: the button
 * disables itself and swaps its label while in flight rather than firing again.
 */
@Component({
  selector: 'vision-demo-button',
  imports: [Icon],
  templateUrl: './demo-button.html',
  styleUrl: './demo-button.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DemoButton {
  private readonly api = inject(DemoApi);
  private readonly toast = inject(ToastService);

  /** Backend probe result — `null` until it answers, and permanently `null` when it 404s. */
  private readonly status = signal<DemoStatus | null>(null);

  /** In-flight guard: one press at a time, and the label says so. */
  protected readonly busy = signal(false);

  protected readonly available = computed(() => this.status()?.enabled === true);

  protected readonly tooltip = computed(() => {
    const videos = this.status()?.videos ?? [];
    const source = videos.length > 0
      ? `${videos.length} video(s) from ${this.status()?.videosDirectory}`
      : `no videos found in ${this.status()?.videosDirectory} — the fleet will be fully synthetic`;
    return `Create 10 simulated assets, 10 users, assignments, geofences and marks (${source})`;
  });

  constructor() {
    this.api.status().then((status) => this.status.set(status)).catch(() => this.status.set(null));
  }

  protected async fill(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      this.report(await this.api.seed());
    } catch {
      this.toast.error('Could not fill demo data — see the backend log.');
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Seeding is fault-tolerant rather than atomic, so a run can succeed *and* carry problems (a
   * stream that would not open, a user whose name was taken). Both facts are reported: what landed,
   * and — as a separate, longer-lived warning — what did not.
   */
  private report(result: DemoSeedResult): void {
    this.toast.ok(
      `Demo data ready: ${result.assets} assets, ${result.users} users, ${result.assignments} assignments, `
        + `${result.zones} zones, ${result.marks} marks, ${result.streamsStarted} live.`,
      // Fleet/marks arrive over SSE on their own; geofences and the roster have no live topic, so
      // offer the one action that is guaranteed to show everything at once.
      { label: 'Reload', onClick: () => window.location.reload() },
    );
    if (result.problems.length > 0) {
      this.toast.warn(`${result.problems.length} demo step(s) failed: ${result.problems[0]}`);
    }
  }
}
