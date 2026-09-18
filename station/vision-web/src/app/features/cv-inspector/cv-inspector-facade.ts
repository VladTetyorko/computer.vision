import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { CvTraceFacade } from '../../core/cv-trace/cv-trace-facade';
import { FleetStore } from '../../core/fleet/fleet-store';
import { healthLabel, healthSeverity } from '../../core/system-status/system-status-logic';
import { SystemStatusStore } from '../../core/system-status/system-status-store';
import type { CvTrace } from '../../core/api/models';
import {
  allTrackIds,
  assetIdForStream,
  cvSubsystemRow,
  evidenceRowsFor,
  latestFrame,
  serializeTrace,
  traceFileName,
  worldObjectFor,
} from './cv-inspector-logic';

/**
 * `/manage/cv`'s facade (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8, wave W5.3) — the one
 * seam the routed page injects (`core/ui/architecture.spec.ts`'s layering guard). Three collaborators:
 *
 * - {@link CvTraceStore} — page-provided (this page's own `providers`, alongside this facade), the
 *   `gate`/`frame`/`world` ledgers for whichever stream is currently picked.
 * - {@link FleetStore} — `providedIn: 'root'`, already polling; `streams()` fills the stream picker
 *   with **running** streams only, per the plan's own §4.8 instruction.
 * - {@link SystemStatusStore} — `providedIn: 'root'`, already polling app-wide; the process-facts
 *   panel is a second reader of the same `cv-service` row `/manage/system` renders, exactly like
 *   `SystemStatusFacade` itself reads it (see that facade's own class doc for the same precedent).
 *
 * **Live subscription (wave W5.7)** — the stream picker only ever hands `CvTraceStore.track()` a
 * `streamId` (`ActiveStream` carries a `deviceId`, not an `assetId`, and neither `FleetStore` nor
 * any other app-wide store holds a device/stream → asset index — `fleet-store.ts` says so in its
 * own "assets aren't tracked by a `FleetStore` signal" comment), so {@link selectStream} resolves
 * the owning asset itself before calling `track()`: one `VisionApi#fleetSummary()` fetch, then
 * {@link assetIdForStream} (`cv-inspector-logic.ts`) — the same `AssetAttention.streamId` join
 * `features/wall/wall-logic.ts` already uses to attribute a stream to an asset — picks the row
 * naming this stream. This is the "make one `GET` per pick" fallback named in the plan's own W5
 * acceptance line ("a saved trace replays through trackeval" is W5.5's separate, unresolved
 * question — this paragraph is only about the live topic): an engineer picks a stream rarely, so
 * one extra request per pick is cheap, and no already-loaded structure could answer this for free.
 * A `token` counter drops the resolution if a later pick supersedes this one mid-flight (the same
 * "compare a captured generation before applying" guard `CvTraceStore.pollOnce` itself uses for the
 * identical race), and the lookup degrades to poll-only (`assetId` left `undefined`) rather than blocking the
 * inspector on a failed fetch — `CvTraceStore`'s own 3s `POLL_INTERVAL_MS` poll is *always*
 * authoritative regardless of whether the live topic is also wired (that store's own class doc), so
 * a failed resolution costs a few seconds of extra `frame` latency, never a wrong answer.
 *
 * **Process facts are stream-independent** — the `cv-service` row is a whole-process fact (queue
 * depth, gate occupancy, capacity), not scoped to any one stream (§4.8's own audience table lists it
 * under "Ops", not per-asset) — {@link cvRow} renders regardless of whether a stream is picked at all.
 *
 * **Save trace (wave W5.4)** — {@link saveTrace} captures exactly the `CvTrace` shape `GET
 * .../cv/trace` itself would return for the currently-picked stream (`{streamId, gate, frame,
 * world}`, built from this store's own live signals rather than issuing a fresh request — a second
 * network round trip could observe a different moment than what the engineer is actually looking
 * at), pretty-printed, via the app's existing client-side Blob-download convention
 * (`features/onboarding/onboarding-facade.ts#downloadBlock`'s same `createObjectURL` → synthetic
 * `<a download>` → `revokeObjectURL` idiom — not reused directly since that helper is typed around
 * `ConfigBlock`, a different shape). The saved file is wave W5.5's own replay fixture.
 */
@Injectable()
export class CvInspectorFacade {
  private readonly api = inject(VisionApi);
  private readonly trace = inject(CvTraceFacade);
  private readonly fleet = inject(FleetStore);
  private readonly statusStore = inject(SystemStatusStore);

  /** Running streams only — the plan's own §4.8 instruction; a stream that already stopped has
   *  nothing left to trace live, and its last-known trace stays reachable by re-picking it if it
   *  restarts under the same id. */
  readonly streams = this.fleet.streams;

  private readonly selectedStreamIdSignal = signal<string | undefined>(undefined);
  readonly selectedStreamId = this.selectedStreamIdSignal.asReadonly();

  readonly gate = this.trace.gate;
  readonly frame = this.trace.frame;
  readonly world = this.trace.world;

  /** The newest frame in the ring — the frame selector below defaults here until an engineer picks
   *  an older one to look back at. */
  readonly latestFrame = computed(() => latestFrame(this.frame()));

  private readonly selectedFrameSequenceSignal = signal<number | undefined>(undefined);
  readonly selectedFrameSequence = this.selectedFrameSequenceSignal.asReadonly();

  /** The contributor-list panel's own subject — the explicitly selected frame if it is still in the
   *  ring, else the latest one (covers both "never picked one" and "picked one that has since aged
   *  out of the ring"). */
  readonly selectedFrame = computed(() => {
    const sequence = this.selectedFrameSequenceSignal();
    if (sequence !== undefined) {
      const found = this.frame().find((candidate) => candidate.sequence === sequence);
      if (found !== undefined) {
        return found;
      }
    }
    return this.latestFrame();
  });

  /** Every track id the ring or the world fold currently knows about — the object-evidence panel's
   *  own picker list. */
  readonly trackIds = computed(() => allTrackIds(this.frame(), this.world()));

  private readonly selectedTrackIdSignal = signal<number | undefined>(undefined);
  readonly selectedTrackId = this.selectedTrackIdSignal.asReadonly();

  readonly evidenceRows = computed(() => {
    const trackId = this.selectedTrackIdSignal();
    return trackId === undefined ? [] : evidenceRowsFor(this.frame(), trackId);
  });

  readonly selectedWorldObject = computed(() => {
    const trackId = this.selectedTrackIdSignal();
    return trackId === undefined ? undefined : worldObjectFor(this.world(), trackId);
  });

  /** The `cv-service` row — see class doc's "process facts are stream-independent" note. */
  readonly cvRow = computed(() => cvSubsystemRow(this.statusStore.status()));
  readonly healthLabel = healthLabel;
  readonly healthSeverity = healthSeverity;

  constructor() {
    // Picks up the process-facts row even if this page opened before `SystemStatusStore`'s own
    // first poll landed — mirrors `SystemStatusFacade`'s identical constructor-time `refresh()` call.
    void this.statusStore.refresh();
  }

  /** Bumped on every {@link selectStream} call (including the `''` deselect) so a slow, since-
   *  superseded `fleetSummary()` fetch can tell it no longer owns the picker and must not call
   *  `track()` with stale data — see class doc's "Live subscription" paragraph. */
  private assetResolutionToken = 0;

  /** Picking a stream (or `''`, the picker's own "— pick a stream —" placeholder option) starts or
   *  stops `CvTraceStore` tracking it, and clears whichever frame/track was selected under the
   *  previous stream — a `sequence`/track id from one stream has no meaning under another.
   *  Resolving the picked stream's owning asset (class doc's "Live subscription" paragraph) is
   *  asynchronous, so `track()` itself is only called once that resolution settles. */
  selectStream(streamId: string): void {
    this.selectedFrameSequenceSignal.set(undefined);
    this.selectedTrackIdSignal.set(undefined);
    this.assetResolutionToken++;
    const token = this.assetResolutionToken;

    if (streamId === '') {
      this.selectedStreamIdSignal.set(undefined);
      this.trace.reset();
      return;
    }
    this.selectedStreamIdSignal.set(streamId);
    void this.trackWithResolvedAsset(streamId, token);
  }

  /** The async half of {@link selectStream} — resolving `streamId`'s owning asset before ever
   *  calling `CvTraceStore.track()`, so the live `cv-trace:<assetId>` subscription (when the lookup
   *  succeeds) starts in the very same `track()` call as the poll, never as a second, retargeting
   *  call that would flash the ring empty a moment after the first. */
  private async trackWithResolvedAsset(streamId: string, token: number): Promise<void> {
    let assetId: string | undefined;
    try {
      assetId = assetIdForStream(await this.api.fleetSummary(), streamId);
    } catch {
      // Degrades to poll-only, the same silent-degrade idiom `CvTraceStore.pollOnce` itself uses —
      // a failed asset lookup must not stop the inspector's authoritative poll from starting.
    }
    if (token !== this.assetResolutionToken) {
      return; // superseded by a later pick (or a deselect) while this GET was in flight
    }
    this.trace.track(streamId, assetId);
  }

  selectFrame(sequence: number): void {
    this.selectedFrameSequenceSignal.set(sequence);
  }

  selectTrack(trackId: number): void {
    this.selectedTrackIdSignal.set(trackId);
  }

  /** `undefined` selectedStreamId means nothing to save yet — the page's own Save-trace button
   *  binds `[disabled]` to `!canSaveTrace()` rather than hiding the action outright, so an engineer
   *  who has picked a stream but sees an empty ring still gets an honest, if empty, file. */
  readonly canSaveTrace = computed(() => this.selectedStreamIdSignal() !== undefined);

  /** See class doc's "Save trace" paragraph. No-ops (rather than throwing) when no stream is picked —
   *  the page's own button is `[disabled]` in that state, but this stays defensive against a future
   *  caller that doesn't check {@link canSaveTrace} first. */
  saveTrace(): void {
    const streamId = this.selectedStreamIdSignal();
    if (streamId === undefined) {
      return;
    }
    const trace: CvTrace = { streamId, gate: this.gate(), frame: this.frame(), world: this.world() };
    const url = URL.createObjectURL(new Blob([serializeTrace(trace)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = traceFileName(streamId, Date.now());
    link.click();
    URL.revokeObjectURL(url);
  }
}
