/**
 * Wire types, mirroring `com.drones.vision.api.dto` one-for-one.
 *
 * Keep this file in lockstep with the Java DTOs — it is the single place the
 * frontend encodes the REST contract.
 */

/**
 * Mirrors `domain.model.Capability`. Drives which panels a device renders.
 *
 * Sent case-insensitively; `CapabilityParsing` on the backend accepts any casing, but
 * responses always echo the enum's `name()`, hence the fixed casing here.
 */
export type Capability = 'VIDEO' | 'TELEMETRY' | 'PTZ' | 'AUDIO';

/**
 * Mirrors `domain.model.LifecycleState` as surfaced by `dto.DeviceResponse#state` /
 * `dto.AssetSummaryResponse#lifecycle` (docs/main/CYCLES-PLAN.md §8) — one axis shared by both
 * devices and assets. A deactivated device/asset refuses to stream; `DELETED` is a soft
 * delete/archive (hidden from default listings, recoverable via restore — see
 * `SettableLifecycleState`).
 */
export type LifecycleState = 'ACTIVE' | 'DEACTIVATED' | 'DELETED';

/**
 * The only two states a client may request via `POST .../state` (docs/main/CYCLES-PLAN.md §8's
 * pinned contract) — `DELETED` is reached only via the `DELETE` (archive) endpoints, and
 * `DEACTIVATED` on an already-`DELETED` thing is how the contract spells "restore" (there is no
 * direct `DELETED` → `ACTIVE` transition).
 */
export type SettableLifecycleState = Exclude<LifecycleState, 'DELETED'>;

/** Mirrors the `{state}` body every `POST .../state` endpoint takes (docs/main/CYCLES-PLAN.md §8). */
export interface SetLifecycleStateRequest {
  readonly state: SettableLifecycleState;
}

/**
 * Mirrors `dto.DeviceResponse`.
 *
 * There is no `type` field: the `DeviceType` enum was removed server-side in favor of the
 * data-driven category model, which applies to `Asset`s, not raw devices (see `Category`,
 * `AssetSummary`). `state` can now be `DELETED` too (docs/main/CYCLES-PLAN.md §8 — a device can be
 * archived, e.g. as the last source of an asset, without the asset itself going away).
 */
export interface Device {
  readonly id: string;
  readonly name: string;
  readonly capabilities: readonly Capability[];
  readonly protocol: string;
  readonly uri: string;
  readonly options: Record<string, string>;
  readonly state: LifecycleState;
}

/**
 * Mirrors `PATCH /api/devices/{id}`'s body (docs/main/CYCLES-PLAN.md §8's pinned contract): every
 * field optional, `@JsonInclude(NON_NULL)`-style — send only what actually changed. The backend
 * requires `protocol`+`uri` together whenever either (or `options`) is present; the request
 * builder in `features/devices/devices-page-logic.ts` only ever populates `name` today (the UI's
 * "Rename" action), but the type mirrors the full pinned shape for API-layer completeness.
 */
export interface DeviceEdit {
  readonly name?: string;
  readonly protocol?: string;
  readonly uri?: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
}

/**
 * Mirrors `dto.RegisterDeviceRequest`. `capabilities` is optional; a missing/empty value
 * defaults server-side to `[VIDEO]`. No `type` field — see `Device`.
 */
export interface RegisterDeviceRequest {
  readonly name: string;
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
}

/**
 * Mirrors `dto.ActiveStreamResponse`. `viewUrl`/`whepUrl` are each independently absent when the
 * active publisher has no such viewing endpoint wired.
 *
 * `whepUrl` (docs/plans/done/MVP2-PLAN.md §L, L-a) is mediamtx's own **absolute origin URL** for the WebRTC
 * (WHEP) viewing endpoint — unlike `viewUrl`, which can be app-relative (`HlsProxyController`
 * reverse-proxies HLS byte fetches), WHEP is a POST/SDP + ICE exchange a stateless proxy cannot
 * forward, so this is never app-relative and must never be proxied — POST straight to it.
 */
export interface ActiveStream {
  readonly streamId: string;
  readonly deviceId: string;
  readonly startedAt: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
}

/**
 * Mirrors `dto.StartStreamRequest` — every field falls back to `PipelineConfig.defaults()`.
 * `model` (docs/plans/done/CV-CONTROL-PLAN.md, extending docs/plans/done/CV-MODELS-PLAN.md item 4 — the detection-model
 * picker, now data-driven from `GET /api/cv/models`, see `CvModel`/`CvModelsResponse` below) is the
 * raw model id string verbatim, never split here — it may be a comma-composite
 * (`"yolo11n.pt,orion12l.pt"`) that only `cv-service`'s own registry parses; the resulting
 * `ModelRef`'s version always stays the backend default (there is no per-stream version override,
 * only a model-id one).
 *
 * `labelFilter`/`detectionEnabled` (docs/plans/done/CV-CONTROL-PLAN.md §2's frozen contract) are this cycle's
 * own additions, both optional — an absent `labelFilter` keeps today's "empty = all labels"
 * semantics, an absent `detectionEnabled` defaults `true` server-side (`PipelineConfig`'s own
 * `DEFAULT_DETECTION_ENABLED`). Both are also PATCH-able live afterward — see
 * `UpdateStreamConfigRequest`.
 */
export interface StartStreamRequest {
  readonly confidenceThreshold?: number;
  readonly inferenceFps?: number;
  readonly model?: string;
  readonly labelFilter?: readonly string[];
  readonly detectionEnabled?: boolean;
}

// --- Live per-stream CV control (docs/plans/done/CV-CONTROL-PLAN.md §2-4's frozen contract) ----------------
// Gives the Fly cockpit's CV control panel (`features/fly/cv-control-panel.ts`) live control of a
// *running* stream's detection pipeline, plus a data-driven model roster for the picker (replacing
// the old hardcoded `DETECTION_MODEL_OPTIONS`/`DetectionModelId` union — see
// `core/settings/settings-store.ts`'s own doc comment for that migration).

/**
 * Mirrors the body of `PATCH /api/streams/{streamId}/config` — every field independently optional;
 * **only present fields change, absent fields are left exactly as they are** (a partial patch, not
 * a full replace — unlike `AssetEdit`/`DeviceEdit`, which happen to send every field their own
 * pages ever populate, this DTO is deliberately built one-or-a-few-fields-at-a-time by the CV panel:
 * a hot-knob edit sends `confidenceThreshold`/`inferenceFps`/`labelFilter`/`detectionEnabled` and
 * never `model`; a model change sends `model` alone — see `features/fly/cv-control-panel-logic.ts#buildHotKnobPatch`/
 * `#buildModelChangePatch`, which keep those two families of change from ever mixing in one call so
 * a hot-knob drag can never accidentally trigger a model re-arm).
 *
 * `maxInFlightInferences`/`overlayTelemetry`/`overlayBurnIn`/`eventRule`/the model **version** are
 * deliberately **not** fields here — not PATCH-able in v1 (docs/plans/done/CV-CONTROL-PLAN.md's own non-goals:
 * `overlayBurnIn` changes the encode path, `eventRule` is bound into the event engine at stream
 * start). Start-time only, via `StartStreamRequest` above.
 *
 * `tracking` (docs/plans/done/TRACKING-PLAN.md §4.D, wave T7) is a **third**, independent family of change
 * alongside the hot knobs above and `model` — never coalesced with either
 * (`cv-control-panel-logic.ts#buildTrackingModePatch`/`buildTrackingEnginePatch`/
 * `buildTrackingCadencePatch`/`buildFollowLockPatch`/`buildReleaseLockPatch` each build a
 * `tracking`-only patch). See {@link TrackingConfigRequest}'s own doc comment for the one-of-three
 * `lock` rule.
 */
export interface UpdateStreamConfigRequest {
  readonly confidenceThreshold?: number;
  readonly inferenceFps?: number;
  readonly labelFilter?: readonly string[];
  readonly detectionEnabled?: boolean;
  readonly model?: string;
  readonly tracking?: TrackingConfigRequest;
}

/**
 * Mirrors the `200` body of `PATCH /api/streams/{streamId}/config`. `modelReArmed` is `true` **only**
 * when the request's `model` field was present and differed from the stream's running model — every
 * other knob is hot and never re-arms. A brief detection gap happens in that case (video is never
 * interrupted, per docs/plans/done/CV-CONTROL-PLAN.md §A) — `cv-control-panel-logic.ts#reArmHint` is the one
 * place this app turns that into operator-facing copy; `404`/`400`/`409` never reach this type at
 * all (an `HttpErrorResponse`, decoded by the caller via `describeHttpError`).
 *
 * `trackingChanged` (docs/plans/done/TRACKING-PLAN.md §4.D, wave T7) is `true` iff the request's `tracking`
 * object was present **and** produced a different `TrackingConfig` than the one already running — a
 * mode/engine change never re-arms the detector, tracking is a hot knob throughout exactly like
 * confidence/fps. Typed **optional** rather than required, unlike `modelReArmed`: this field is added
 * server-side by docs/plans/done/TRACKING-PLAN.md wave T6, which lands after this one — a backend this app talks
 * to before T6 ships simply omits it, and no reader here should assume a missing key means `false`.
 * The Fly cockpit's own "Following #N" chip does **not** read this flag at all — see
 * `StreamTracksResponse#lockedTrackId`'s own doc comment for why a lock's confirmation comes from a
 * different, polled response instead (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule).
 */
export interface PatchStreamConfigResponse {
  readonly streamId: string;
  readonly modelReArmed: boolean;
  readonly trackingChanged?: boolean;
}

/**
 * Mirrors one entry of `dto.CvModelsResponse#models` (`GET /api/cv/models`) — one row of the
 * detection-model picker's roster, replacing the old hardcoded `DETECTION_MODEL_OPTIONS` array.
 * `id` is the exact checkpoint filename `StartStreamRequest#model`/`UpdateStreamConfigRequest#model`
 * forward verbatim (what cv-service's own registry routes on) — composite ids (`"a.pt,b.pt"`) are
 * still valid values of those fields, but the roster itself lists atomic models only. `kind` is a
 * free-form UI hint string (`"general"`/`"specialized"`/`"open-vocab"` today, not a closed union on
 * the wire — a display concern, not a domain enum). `displayName` already carries the "what it
 * detects, what it costs" hint verbatim (e.g. `"Everything (incl. buildings, slower)"`) — there is
 * no separate hint field, unlike the old `DetectionModelOption#hint`.
 *
 * `openVocab` drives whether the CV panel leads with the class-filter chips for this model
 * (docs/plans/done/CV-CONTROL-PLAN.md §E: "labelFilter is the primary UX control for the open-vocab model").
 * `defaultLabelFilter` is the class set a *closed-set* model's own picker pre-seeds
 * (`cv-control-panel-logic.ts#seedLabelFilterForModel`); an **open-vocab** model's own
 * `defaultLabelFilter` is deliberately **ignored** by that same seeding function — always seeds
 * `[]` ("show every class") instead, regardless of what this field says, because a prompt-free
 * open-vocab model's real vocabulary (~4585 classes, cv-service Wave A's own measurement) emits many
 * synonym/scene labels for one real-world thing (`"building"`/`"skyscraper"`/`"office building"`/
 * `"downtown"`/a named landmark, all for what a person would call "a building") — a fixed preset
 * filter would silently drop most real detections rather than usefully narrowing them. See that
 * function's own doc comment for the full reasoning, and `cv-control-panel-logic.ts#PEOPLE_VEHICLES_BUILDINGS_PRESET`
 * for the opt-in, one-click (never silently-applied) alternative this panel offers instead.
 */
export interface CvModel {
  readonly id: string;
  readonly displayName: string;
  readonly kind: string;
  readonly openVocab: boolean;
  readonly defaultLabelFilter: readonly string[];
}

/** Mirrors `GET /api/cv/models`'s `200` body — `yolo26n.pt` (the fast closed-set default) listed
 * first, per docs/plans/done/CV-CONTROL-PLAN.md §4. Never errors server-side; `FleetStore.models` degrades to
 * an empty list on any transport failure instead (silent, background-enrichment read — see that
 * class's own doc comment). */
export interface CvModelsResponse {
  readonly models: readonly CvModel[];
}

// --- Tracking engine (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) -----------------
// Two perception loops per stream (`ASSOCIATE`: every detection gets a stable id that survives a
// brief occlusion; `FOLLOW`: one locked target, the detector duty-cycled to a periodic verify pass)
// — `features/fly/cv-control-panel.ts`'s new Tracking section + flow strip, `shared/player/player.ts`'s
// track-aware box label/color/dashed-COASTING/trails/click-to-follow. **The backend for this wire
// contract had not shipped when this wave landed** (docs/plans/done/TRACKING-PLAN.md §7: T0-T6 land concurrently,
// T7 integrates last against the frozen contract with nothing live to test against) — every reader
// below treats every field on this page as possibly absent (an old server, or no server at all yet)
// and degrades to "hidden"/"—", exactly this file's own long-standing convention, doubly load-bearing
// here since it was written ahead of the thing it describes.

/** Mirrors `domain.model.TrackingMode` — `OFF` (today's behavior, byte-identical, the default until
 *  docs/plans/done/TRACKING-PLAN.md wave T8 flips it), `ASSOCIATE` (every detection gets a stable id),
 *  `FOLLOW` (one locked target; the detector duty-cycles down to a periodic verify pass). */
export type TrackingMode = 'OFF' | 'ASSOCIATE' | 'FOLLOW';

/**
 * Mirrors `domain.model.TrackState` — the track lifecycle (docs/plans/done/TRACKING-PLAN.md §3.2):
 * `TENTATIVE` (born, below `minHits` detector confirmations — not yet a stable identity) →
 * `CONFIRMED` (a real, confirmed object) → `COASTING` (tracker-predicted only; the detector hasn't
 * re-confirmed it on the most recent pass — renders as a **dashed** box, the system visibly saying
 * "I am extrapolating, not seeing") → `LOST` (unmatched past `maxAgeFrames`, kept briefly so a
 * re-appearance after an occlusion recovers the same id).
 */
export type TrackState = 'TENTATIVE' | 'CONFIRMED' | 'COASTING' | 'LOST';

/** Mirrors `domain.model.DetectionSource` — which loop produced this particular box on this particular frame. */
export type DetectionSource = 'DETECTOR' | 'TRACKER';

/**
 * Mirrors `domain.model.DetectorReason` — *why* a detector pass was spent on a frame, paired with
 * `FrameTracking#detectorRan` (which only says *whether*) — docs/extracts/TRACKING-ORCHESTRATION.md §5.1;
 * without this, "why is the detector still running in FOLLOW mode?" is answerable only by reading
 * cv-service logs on whichever box it happens to run on. The wire's `DETECTOR_REASON_UNSPECIFIED`
 * sentinel (old server, or no pass ran this particular frame) never reaches here as a seventh member
 * — same absent-not-guessed convention as every other sentinel in this file.
 */
export type DetectorReason =
  | 'ALWAYS'
  | 'CADENCE'
  | 'TRACKER_FAILED'
  | 'NO_LOCK'
  | 'BOX_INVALID'
  | 'COASTED_OUT';

/**
 * Mirrors the nested `"track"` object on `dto.DetectionResponse` (docs/plans/done/TRACKING-PLAN.md §4.G,
 * docs/extracts/TRACKING-ORCHESTRATION.md §5.3) — **grouped, not five flat fields**, so a single
 * `detection.track?.id` check gates all track-aware rendering (the `#id` label prefix, a per-track
 * box color, the dashed `COASTING` stroke, trails, click-to-follow) rather than several fields that
 * could disagree with each other. Absent entirely for an untracked detection — mode `OFF`, or a
 * detection `ASSOCIATE`/`FOLLOW` hasn't (yet) assigned a `TENTATIVE`-or-above id to; the wire's
 * `track_id == 0` sentinel never reaches here as `id: 0` (mirrors `LayerGrant`/every other
 * absent-not-zero rule in this file). `ageFrames` deliberately doesn't ride here — it's
 * `StreamTrack`'s own book-keeping, not something a box needs six times a second (§4.G's own note).
 */
export interface DetectionTrack {
  readonly id: number;
  readonly state: TrackState;
  readonly source: DetectionSource;
  readonly velocityX: number;
  readonly velocityY: number;
}

/**
 * Mirrors the nested `"tracking"` object on `dto.DetectionResultResponse` (docs/plans/done/TRACKING-PLAN.md
 * §4.G) — one frame's duty-cycle facts, riding beside `detections` rather than flattened onto
 * `DetectionResult` (docs/extracts/TRACKING-ORCHESTRATION.md §5.2's own gap fix: `DetectionResult` had
 * nowhere to carry `detectorRan` before `TrackingTelemetry` existed). Absent entirely while tracking
 * is off for this stream, or on an old server — never a batch of zeroed-out fields.
 */
export interface FrameTracking {
  readonly detectorRan: boolean;
  readonly detectorReason: DetectorReason;
  readonly trackerMillis: number;
  readonly engineId: string;
  readonly lockedTrackId: number;
}

/**
 * Mirrors the `lock` object nested inside `tracking` on `PATCH .../config` (docs/plans/done/TRACKING-PLAN.md
 * §4.D) — exactly one of `trackId` / (`pointX` + `pointY`) / `release` may be present; sending two
 * of the three is a server-validated **400** (not re-checked client-side — every builder in
 * `cv-control-panel-logic.ts` only ever populates one form at a time). `lockSeq` is never sent by a
 * client at all — the server allocates it per stream (`DefaultStreamService`'s own monotonic
 * counter), so unlike the domain's own `TargetLock` this type has no such field.
 */
export interface TargetLockRequest {
  readonly trackId?: number;
  readonly pointX?: number;
  readonly pointY?: number;
  readonly release?: boolean;
}

/**
 * Mirrors the `tracking` object accepted by `PATCH /api/streams/{streamId}/config` (docs/plans/done/TRACKING-PLAN.md
 * §4.D) — every field independently optional, the same partial-patch convention
 * `UpdateStreamConfigRequest` itself already follows: an absent field leaves that knob exactly as it
 * is. `redetectIouPercent` is an `int` percent (0-100), not a fraction — matches the domain's own
 * `TrackingConfig` (§4.B); converted to the proto's `float` fraction server-side, never here.
 */
export interface TrackingConfigRequest {
  readonly mode?: TrackingMode;
  readonly engineId?: string;
  readonly verifyEveryMillis?: number;
  readonly followFps?: number;
  readonly redetectIouPercent?: number;
  readonly maxAgeFrames?: number;
  readonly minHits?: number;
  readonly lock?: TargetLockRequest;
}

/**
 * Mirrors the `"stats"` object of `GET /api/streams/{streamId}/tracks`'s 200 body (docs/plans/done/TRACKING-PLAN.md
 * §4.E) — computed **Java-side** by `TrackingStatsWindow` from responses already flowing through the
 * pipeline (no new wire field, no cv-service read-model concern — invariant P3). Backs the Fly
 * cockpit's flow strip (docs/extracts/TRACKING-ORCHESTRATION.md §7, the plan's own "visible flow" deliverable)
 * — the one place this app turns the "the detector stopped running, the tracker took over" claim
 * into something read off the screen. **Absent entirely** (not a zeroed object) whenever the endpoint
 * has nothing to report yet — mode `OFF` with no tracking session ever configured, or an old/absent
 * server — so the flow strip hides itself rather than showing a strip of zeros
 * (`cv-control-panel-logic.ts#formatFlowStrip`'s caller checks this before ever calling it).
 *
 * `engineId` is the engine **actually serving** this stream right now, not necessarily the one the
 * operator last requested (docs/plans/done/TRACKING-PLAN.md R11 — a requested engine can fail to construct and
 * fall back to the mode's default). The flow strip, and the Tracking section's own engine-picker
 * selected-state, both read this field for exactly that reason — never the locally-drafted request.
 */
export interface TrackStats {
  readonly mode: TrackingMode;
  readonly engineId: string;
  readonly windowSeconds: number;
  readonly detectorPasses: number;
  readonly trackerFrames: number;
  readonly dutyRatio: number;
  readonly trackerMillisP50: number;
  readonly trackerMillisP95: number;
  readonly lastDetectorReason: DetectorReason;
  readonly byState: Readonly<Record<TrackState, number>>;
}

/**
 * One row of `GET /api/streams/{streamId}/tracks`'s `"tracks"` array (docs/plans/done/TRACKING-PLAN.md §4.E) —
 * the application layer's track-book entry (`TrackedObject`, made live by this plan after sitting as
 * a dead type — see that record's own Java doc comment), ordered by `trackId` ascending server-side.
 * The exact backend DTO class name isn't pinned yet (T6 lands after this wave, docs/plans/done/TRACKING-PLAN.md
 * §7) — this mirrors the *JSON shape* §4.E froze, not a specific Java type name.
 */
export interface StreamTrack {
  readonly trackId: number;
  readonly label: string;
  readonly confidence: number;
  readonly box: BoundingBox;
  readonly state: TrackState;
  readonly source: DetectionSource;
  readonly velocityX: number;
  readonly velocityY: number;
  readonly ageFrames: number;
  readonly firstSeen: string;
  readonly lastSeen: string;
}

/**
 * Mirrors `GET /api/streams/{streamId}/tracks`'s 200 body (docs/plans/done/TRACKING-PLAN.md §4.E) — never
 * errors server-side; an unknown/stopped stream returns an empty `tracks` list and `lockedTrackId:
 * 0` (the same forgiving idiom `GET .../detections` already uses). A **transport failure** (this
 * endpoint not existing yet on an old/absent server, or a genuine network error) is therefore the
 * only "nothing to show" case a reader needs to handle — see `FleetStore.getStreamTracks`'s own doc
 * comment for how that degrades (silently, no toast — a background enrichment poll).
 *
 * `lockedTrackId` is `0` when no lock is held — the wire's own "not this" sentinel, never `null`/
 * absent, mirroring the domain's own `TargetLock`/`lockedTrackId` convention. **This is the field
 * the Fly cockpit's "Following #N — release" chip gates on**: the chip renders only once this
 * response confirms a lock, never from local click intent (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's
 * honesty rule) — see `cv-control-panel.ts`'s own doc comment.
 */
export interface StreamTracksResponse {
  readonly streamId: string;
  readonly lockedTrackId: number;
  readonly tracks: readonly StreamTrack[];
  readonly stats?: TrackStats;
}

/**
 * Mirrors one entry of `GET /api/cv/trackers`'s roster (docs/plans/done/TRACKING-PLAN.md §4.F) — the Tracking
 * section's engine picker, filtered to whichever `modes` include the currently-selected
 * `TrackingMode` (`cv-control-panel-logic.ts#engineOptionsForMode`). Config-backed and static, like
 * `CvModel`'s own roster — "changes at deploy time, not runtime" (docs/plans/done/CV-CONTROL-PLAN.md §D's frozen
 * decision, mirrored here for trackers) — fetched once, never re-polled. `needsAssets` is unused by
 * every one of this wave's three built-in engines (`bytetrack`/`lk`/`ncc`, all `false`) but mirrored
 * from the wire for the deferred ONNX engines (docs/plans/done/TRACKING-PLAN.md §5.B) that will eventually need it.
 */
export interface CvTracker {
  readonly id: string;
  readonly displayName: string;
  readonly modes: readonly TrackingMode[];
  readonly needsAssets: boolean;
  readonly costHint: string;
}

/** Mirrors `GET /api/cv/trackers`'s 200 body — never errors server-side, always at least the
 *  built-in roster (docs/plans/done/TRACKING-PLAN.md §4.F), the same "wrapped list" shape as `CvModelsResponse`. */
export interface CvTrackersResponse {
  readonly trackers: readonly CvTracker[];
}

/** Mirrors `dto.StartStreamResponse`. `whepUrl` follows the same absolute-origin rule as `ActiveStream#whepUrl`. */
export interface StartStreamResult {
  readonly streamId: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
}

/**
 * Mirrors `dto.DiscoveredDeviceResponse`. A candidate, not yet a device.
 *
 * `suggestedCategory` is a category slug (e.g. `"drone"`), not a `DeviceType` — categories
 * apply to `Asset`s (see `Device`'s doc comment), but discovery still offers a best-guess one
 * as a hint for whichever asset the user eventually files this device under.
 */
export interface DiscoveredDevice {
  readonly method: string;
  readonly name: string;
  readonly address: string;
  readonly suggestedCategory?: string;
  readonly protocol?: string;
  readonly uri?: string;
  readonly details: Record<string, string>;
}

/** Mirrors `dto.ScanRequestDto`. */
export interface ScanRequest {
  readonly timeoutMs?: number;
  readonly methods?: string[];
}

/** Mirrors `dto.ScanResultResponse`. `failedMethods` is surfaced, never swallowed. */
export interface ScanResult {
  readonly devices: readonly DiscoveredDevice[];
  readonly failedMethods: readonly string[];
}

// --- Device probe (docs/plans/done/UX-REWORK-PLAN.md §U-d — the onboarding wizard's Test step) -----------
// `POST /api/devices/probe`: the pinned "test-before-save" contract (UX-DESIGN §5.1) — connects to
// a candidate device/URI without registering anything, decodes exactly one frame, and reports back
// what it saw. This is what lets the wizard refuse to advance to Create on a connection that can't
// actually produce video (`features/onboarding/onboarding-logic.ts#canAdvanceFromTest`). Coded
// against the plan's pinned shape ahead of the backend half landing — see that plan section's own
// "code defensively" note; a 422 with a specific message is the documented failure path
// (`describeHttpError` already surfaces a backend-supplied `ErrorResponse.message` for any non-2xx
// status, 422 included, with no dedicated case needed).

/** Mirrors the probe endpoint's request body — the same shape a register/discover candidate already carries. */
export interface ProbeDeviceRequest {
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
}

/**
 * Mirrors the probe endpoint's 200 response — one decoded frame plus the facts the Test step shows
 * (WxH, codec, fps, whether telemetry was detected alongside the video) and any non-fatal warnings.
 * `codec`/`fps` are the two fields the pinned contract itself marks optional (not every source
 * reports them); everything else is always present on a 200 — a probe that can't produce a frame at
 * all is the documented 422 path instead (an `HttpErrorResponse`, not a `{ok: false}` body).
 *
 * **`warnings` is typed optional despite `dto.ProbeDeviceResponse`'s own Javadoc claiming "always
 * present, possibly empty"** — verified live against the actual running backend (§U-d's backend
 * half landed concurrently with this file): a probe against a `sim`/`sim://demo` device returned a
 * 200 body with the key entirely absent, not `[]` (`{"ok":true,"widthPx":640,"heightPx":480,
 * "codec":"mjpeg","telemetryDetected":true,"frameJpegBase64":"…"}` — no `warnings` key at all).
 * Every reader here (`features/onboarding/onboarding.html`) defaults a missing value to `[]` rather
 * than trusting the doc comment over the observed wire behavior.
 */
export interface ProbeDeviceResult {
  readonly ok: boolean;
  readonly widthPx: number;
  readonly heightPx: number;
  readonly codec?: string;
  readonly fps?: number;
  readonly telemetryDetected: boolean;
  readonly frameJpegBase64: string;
  readonly warnings?: readonly string[];
}

/** Mirrors `dto.ErrorResponse`, produced by `ApiExceptionHandler`. */
export interface ApiErrorBody {
  readonly error: string;
  readonly message: string;
}

/** Mirrors `application.AssetStatus`, as surfaced by `dto.AssetSummaryResponse#status`. */
export type AssetStatus = 'OFFLINE' | 'STREAMING';

/**
 * Mirrors `dto.GeoPositionResponse`. `altitudeMeters` is absent when the position carries no
 * altitude reading.
 */
export interface GeoPosition {
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
}

/**
 * Mirrors `dto.CategoryResponse`. `parent` is absent for a top-level category.
 * `attributeHints` are UI suggestions, not a rigid schema.
 */
export interface Category {
  readonly slug: string;
  readonly name: string;
  readonly parent?: string;
  readonly attributeHints: readonly string[];
}

/**
 * Mirrors `domain.model.FlightState`, surfaced as `dto.TelemetrySampleResponse#flightState`
 * (docs/plans/done/FC-INTEGRATIONS-PLAN.md — the frozen wire contract; F-a/F-b land the backend half in
 * parallel with this UI cycle). Flight-controller state merged from MAVLink telemetry
 * (ArduPilot/INAV/Betaflight, decoded by `adapter-mavlink`) — every field independently absent
 * ("null unknown" in the Java record's own doc comment) except `armingBlockers`, which the
 * backend only omits when empty (never sends `null`); every reader here treats a missing
 * `armingBlockers` as "no blockers known", identical to an empty array.
 *
 * `mode` is a human-readable name (`"RTL"`, `"Loiter"`, `"Angle"` — see
 * `core/telemetry/flight-state-logic.ts`'s own doc comment for the exact RTL/landing mode-name
 * sets it matches against), not a numeric `custom_mode`. `gpsFixType` is the raw
 * `GPS_FIX_TYPE` ordinal 0–6 (`core/telemetry/flight-state-logic.ts#gpsFixLabel`/`gpsSeverity`
 * are the only place this app decodes it into a label/severity).
 */
export interface FlightState {
  readonly firmware?: string;
  readonly mode?: string;
  readonly armed?: boolean;
  readonly failsafe?: boolean;
  readonly gpsFixType?: number;
  readonly satellites?: number;
  readonly hdop?: number;
  readonly rssiPercent?: number;
  readonly armingBlockers?: readonly string[];
}

/**
 * Mirrors `dto.TelemetrySampleResponse`. Every field except `deviceId`/`at` is absent when the
 * underlying sample did not carry that reading — not every device reports every field.
 *
 * `deviceId` (docs/main/CYCLES-PLAN.md §11, CD-a) is never absent — every `Telemetry` sample carries
 * the telemetry device it came from, which is what makes multi-telemetry grouping possible (an
 * asset's usage can mix samples from more than one TELEMETRY-capable device; see
 * `features/asset-detail/asset-detail-logic.ts#groupTelemetryByDevice`).
 *
 * `flightState`/`extra` (docs/plans/done/FC-INTEGRATIONS-PLAN.md, frozen wire contract) are this cycle's own
 * additions: `flightState` is absent when the sample's device never emitted a `HEARTBEAT` yet
 * (never TELEMETRY-MAVLink-capable at all, or the very first sample or two); `extra` is the
 * previously-dropped-at-this-DTO `Telemetry.extra` map, now surfaced — `groundspeedMps` is the one
 * key `features/fly/fly-osd.ts` reads today (closing that component's own previously-documented
 * "no speed reading" gap), `batteryVoltage`/`vxMps` etc. ride along unread. Absent (not `{}`) when
 * empty, same `@JsonInclude(NON_NULL)`-adjacent convention as everything else in this file.
 */
export interface TelemetrySample {
  readonly deviceId: string;
  readonly at: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly altitudeMeters?: number;
  readonly headingDegrees?: number;
  readonly batteryPercent?: number;
  readonly flightState?: FlightState;
  readonly extra?: Record<string, number>;
}

/**
 * Mirrors `dto.AssetUsageResponse`, embedded in `AssetDetails#recentUsages`. `endedAt` absent
 * means the usage is still open — this is how the telemetry store finds the usage to poll.
 */
export interface AssetUsage {
  readonly usageId: string;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly startPosition?: GeoPosition;
  readonly lastPosition?: GeoPosition;
  readonly sampleCount: number;
}

/**
 * Mirrors `dto.AssetSummaryResponse`, the shared field set `AssetDetails` extends. `lastUsedAt`
 * and `lastKnownPosition` are absent for an asset that has never been used.
 *
 * `lifecycle` mirrors `AssetSummaryResponse#lifecycle` (docs/main/CYCLES-PLAN.md §8's pinned contract
 * — CW-a exposes the asset's lifecycle state under this name, alongside the derived streaming
 * `status`). It is typed optional rather than required: CW-a lands this field server-side in
 * parallel with this UI, so a backend this app talks to before that ships simply omits it —
 * every reader here treats an absent `lifecycle` as `'ACTIVE'` (see
 * `features/devices/devices-page-logic.ts`), never as a crash.
 *
 * `hasImage` (docs/plans/done/UX-REWORK-PLAN.md §U-d — the asset image endpoint pair) is optional for the
 * identical reason: a backend that predates the `PUT/GET/DELETE /api/assets/{id}/image` endpoints
 * simply omits the field. Every reader treats an absent value as "no photo" — see
 * `features/asset-detail/asset-detail.ts`'s own image-loading guard, which is the one place this
 * matters (the onboarding wizard's own Create step never reads it; it knows whether it just
 * uploaded a photo).
 */
export interface AssetSummary {
  readonly assetId: string;
  readonly displayName: string;
  readonly category: string;
  readonly categoryName: string;
  readonly owner: string;
  readonly status: AssetStatus;
  readonly lifecycle?: LifecycleState;
  readonly lastUsedAt?: string;
  readonly lastKnownPosition?: GeoPosition;
  readonly attributes: Record<string, string>;
  readonly hasImage?: boolean;
}

/**
 * Mirrors `dto.AssetDetailsResponse`: `AssetSummary`'s fields plus the asset's resolved devices
 * and recent usage history (newest first).
 */
export interface AssetDetails extends AssetSummary {
  readonly devices: readonly Device[];
  readonly recentUsages: readonly AssetUsage[];
}

/**
 * Mirrors `dto.AssetStatsResponse` (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave A's frozen wire
 * contract) — `GET /api/assets/{assetId}/stats`, the manager page's KPI tile row
 * (`core/fleet/asset-stats-logic.ts#kpiTiles`). `totalFlightSeconds`/`flightCount` are always
 * present (0 for an asset with no usages fetched); `firstFlownAt`/`lastFlownAt`/
 * `avgFlightSeconds`/`lastKnownBatteryPercent` are absent — never a fabricated zero/null literal
 * on the wire, `@JsonInclude(NON_NULL)` server-side — exactly when the underlying value is
 * honestly unavailable (no flights, no closed flights, or no telemetry ever reported,
 * respectively). `avgFlightSeconds` is over *closed* flights only; an asset with only an open
 * flight has no average yet even though `flightCount` is 1.
 */
export interface AssetStats {
  readonly totalFlightSeconds: number;
  readonly flightCount: number;
  readonly firstFlownAt?: string;
  readonly lastFlownAt?: string;
  readonly avgFlightSeconds?: number;
  readonly lastKnownBatteryPercent?: number;
  readonly flightInProgress: boolean;
}

/**
 * Mirrors `dto.CreateAssetRequest.DeviceSpec` (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2 — the "Create asset
 * from this device" quick action) — one device to register **alongside** the new asset. This is
 * always a **new** device registration (`name`/`protocol`/`uri`), never a reference to an existing
 * `Device` by id: `CreateAssetRequest`/`AssetSpec` carry no such field (verified by reading
 * `AssetController#create`/`CreateAssetRequest.java`/`AssetSpec.java` — `AssetService#create` calls
 * `deviceService.register(...)` for every entry, unconditionally). `options`/`capabilities` are
 * optional, `@JsonInclude(NON_NULL)`-style like every other request DTO here — omit rather than
 * send `undefined`/empty.
 */
export interface CreateAssetDeviceSpec {
  readonly name: string;
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
}

/**
 * Mirrors `dto.CreateAssetRequest`, the body of `POST /api/assets` (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2;
 * `deviceIds` added by docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch). `attributes` omitted
 * rather than sent as `{}`/`null`; `devices`/`deviceIds` may be combined freely, but at least one
 * device between the two is required (`AssetSpec`'s own validation, not re-checked here) — the
 * response is a full `AssetDetails` (`AssetDetailsResponse`, 201).
 *
 * **`deviceIds`** is the "promote to asset" flow: existing, currently-unowned device ids (canonical
 * UUID strings) to assign to the new asset in the same call — validated exactly like `POST
 * /api/assets/{id}/devices` (must exist, must not be soft-deleted, must not already belong to
 * another asset). This is what let `features/devices/devices-page-logic.ts#buildCreateAssetRequestForDevice`
 * drop its own re-register-then-archive workaround: promoting a device to its own asset no longer
 * creates a second `Device` row at all, see that function's own doc comment.
 */
export interface CreateAssetRequest {
  readonly displayName: string;
  readonly category: string;
  readonly attributes?: Record<string, string>;
  readonly devices?: readonly CreateAssetDeviceSpec[];
  readonly deviceIds?: readonly string[];
}

/**
 * Mirrors `PATCH /api/assets/{id}`'s body (docs/main/CYCLES-PLAN.md §8's pinned contract): every field
 * optional — send only what actually changed. Built by
 * `features/devices/devices-page-logic.ts#buildAssetEdit`.
 */
export interface AssetEdit {
  readonly displayName?: string;
  readonly category?: string;
  readonly attributes?: Record<string, string>;
}

/** Mirrors `POST /api/assets/{id}/devices`'s body (docs/main/CYCLES-PLAN.md §8's pinned contract). */
export interface AssignDeviceRequest {
  readonly deviceId: string;
}

/**
 * Mirrors `dto.AssetDeletionResponse` (docs/main/CYCLES-PLAN.md §8's pinned contract), the body of
 * `DELETE /api/assets/{id}` — from `application.AssetDeletion`. Told to the user verbatim so an
 * archive confirmation says what survived, not just that the asset is gone.
 */
export interface AssetDeletionResponse {
  readonly assetId: string;
  readonly displayName: string;
  readonly devicesDeleted: number;
  readonly usagesRetained: number;
  readonly streamsStopped: number;
}

/**
 * Mirrors `dto.StartSimulationRequest.RouteMode` values (docs/main/CYCLES-PLAN.md §7, CT-a's
 * `application.RouteMode`), matched case-insensitively server-side but always sent lowercase here.
 * `loop` (default, end→start closing leg) / `bounce` (retrace backwards) / `once` (hold at the end
 * waypoint, still emitting).
 */
export type RouteMode = 'loop' | 'bounce' | 'once';

/**
 * Mirrors `dto.StartSimulationRequest.WaypointRequest` — one checkpoint on a flight plan's route.
 * `altitudeMeters` omitted (not `null`) when this checkpoint carries none.
 */
export interface WaypointRequest {
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
}

/**
 * Mirrors `dto.StartSimulationRequest.TelemetryRequest` (docs/main/CYCLES-PLAN.md §7, CT-a's pinned
 * contract) — a configurable flight plan replacing the bare circular home-point track. `route`
 * must carry at least 2 waypoints (`shared/map/flight-plan-logic.ts#canSavePlan`/`buildTelemetryRequest`
 * enforce this client-side before a request is ever built); `speedMps`/`routeMode` omitted defer
 * to the adapter's own defaults (`12.0`/`"loop"`).
 */
export interface TelemetryPlanRequest {
  readonly speedMps?: number;
  readonly routeMode?: RouteMode;
  readonly route: readonly WaypointRequest[];
}

/**
 * Mirrors `dto.StartSimulationRequest` (docs/main/CYCLES-PLAN.md §1c, §3, §7, §9). Optional fields are
 * omitted, never sent as `null`, matching every other request DTO here; the backend's own
 * defaults then apply (`autoStart` → `true`, `transport` → `"direct"`).
 *
 * `videoPath` is optional (docs/main/CYCLES-PLAN.md §9, CU-a): omitting it entirely registers a fully
 * synthetic VIDEO+TELEMETRY device instead of a `file`-backed one — a moving test drone with no
 * video file at all (`core/fleet/simulation-logic.ts#buildTestDroneRequest`). A `null`/absent
 * `videoPath` requires `transport` to stay `"direct"` (the default) — the backend 400s otherwise,
 * since `"rtsp"`/`"mjpeg"` have no in-process renderer output to push over the wire.
 *
 * `transport` is matched case-insensitively server-side, but this app always sends the fixed
 * lowercase values: `"direct"` (in-process playback) or `"rtsp"` (pushed over the wire and
 * ingested back, docs/main/CYCLES-PLAN.md §3 — rehearses the full protocol path).
 *
 * `telemetry` (docs/main/CYCLES-PLAN.md §7, CT-a/CT-b) is an optional flight plan replacing the bare
 * `latitude`/`longitude` circular home-point track; when present, `latitude`/`longitude` are
 * still accepted but ignored server-side (`StartSimulationRequest`'s own Javadoc) —
 * `shared/map/flight-plan-dialog.ts` is the UI that builds this field.
 */
export interface StartSimulationRequest {
  readonly displayName?: string;
  readonly videoPath?: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly autoStart?: boolean;
  readonly transport?: 'direct' | 'rtsp';
  readonly telemetry?: TelemetryPlanRequest;
}

/**
 * Mirrors `dto.SimulationResponse`. `streamId`/`viewUrl`/`whepUrl` are absent when the simulation
 * was not auto-started, or — for `viewUrl`/`whepUrl`, each independently — when the active
 * publisher has no such viewing endpoint (see `ActiveStream#whepUrl`'s doc comment for the
 * absolute-origin/never-proxied rule `whepUrl` follows here too).
 */
export interface SimulationResponse {
  readonly assetId: string;
  readonly streamId?: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
}

/** Mirrors `dto.BoundingBoxResponse`. Each component is normalized [0,1] against frame dimensions. */
export interface BoundingBox {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/**
 * Mirrors `dto.DetectionResponse`, embedded in `DetectionResult#detections`. `track` is this
 * cycle's own addition (docs/plans/done/TRACKING-PLAN.md §4.G, see {@link DetectionTrack}'s own doc comment) —
 * absent for an untracked detection, which is every detection today and every detection on a
 * pre-tracking server; `shared/player/player.ts`'s box label/color/dashed-stroke/trails/click-to-
 * follow all gate on this one field.
 */
export interface Detection {
  readonly label: string;
  readonly confidence: number;
  readonly box: BoundingBox;
  readonly modelId: string;
  readonly modelVersion: string;
  readonly track?: DetectionTrack;
}

/**
 * Mirrors `dto.DetectionResultResponse`, the body element of `GET
 * /api/streams/{streamId}/detections` (docs/plans/done/MVP1-PLAN.md §C8 bullet 3) — one completed inference
 * result. Backs the Live page's detections strip (`core/detections/detections-store.ts`) and, via
 * the same store, the Fly cockpit's player overlay + trails. `tracking` is this cycle's own addition
 * (docs/plans/done/TRACKING-PLAN.md §4.G, see {@link FrameTracking}'s own doc comment) — absent while tracking
 * is off for this stream, or on a pre-tracking server.
 */
export interface DetectionResult {
  readonly streamId: string;
  readonly frameSequence: number;
  readonly capturedAt: string;
  readonly inferenceMillis: number;
  readonly detections: readonly Detection[];
  readonly tracking?: FrameTracking;
}

/**
 * Mirrors `dto.UsageTimelineResponse`, the body of `GET /api/usages/{usageId}/timeline`
 * (docs/plans/done/MVP2-PLAN.md §R, R-a/R-a2 — flight replay). No `NON_NULL`-style optionality: `from`/`to`
 * are always resolved server-side (defaulted from the usage's own `startedAt`/`endedAt`, or "now"
 * for an open usage's `to`), and both list fields are always present, possibly empty.
 *
 * `telemetry` is ascending by `at`, downsampled to the request's `maxPoints` (equidistant
 * thinning that always keeps the first/last sample — `ReplayService`, vision-application).
 * `detections` is ascending by `capturedAt`; real for usages opened after R-a2's
 * `AssetUsage.streamId` link, honestly `[]` for a legacy or streamless usage — see
 * `features/replay/replay.ts`'s empty-state handling.
 */
export interface UsageTimeline {
  readonly usage: AssetUsage;
  readonly from: string;
  readonly to: string;
  readonly telemetry: readonly TelemetrySample[];
  readonly detections: readonly DetectionResult[];
}

/**
 * Mirrors `dto.UsageSummaryResponse` (docs/extracts/design/10-replay.md's frozen wire contract, Wave 4 —
 * `GET /api/usages?limit=&assetId=`), the fleet-wide flight list behind the replay library
 * (`features/replay/replay-library.ts`). Newest first. `assetName` is resolved server-side for
 * display — `''` when the owning asset is gone (deleted past recovery), never a dropped row: a
 * usage outlives its asset. `endedAt`/`durationSeconds` are each independently absent (not
 * `null`) while the flight is still open — the same `@JsonInclude(NON_NULL)` convention as
 * `AssetUsage.endedAt` above; `features/replay/replay-library-logic.ts#formatUsageDuration` is
 * the one place that turns an open flight into an honest "Flying now" rather than a negative or
 * blank duration.
 */
export interface UsageSummary {
  readonly usageId: string;
  readonly assetId: string;
  readonly assetName: string;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly durationSeconds?: number;
  readonly sampleCount: number;
}

/**
 * Mirrors `dto.CategoryCountsResponse`, one row of `FleetSummary#categories` (docs/plans/done/MVP3-PLAN.md
 * C-a) — per-category asset counts, lifecycle crossed with currently-streaming. Every field is
 * always present (no `NON_NULL`-style optionality — nothing here is nullable server-side).
 */
export interface CategoryCounts {
  readonly categoryId: string;
  readonly categoryName: string;
  readonly total: number;
  readonly active: number;
  readonly deactivated: number;
  /** Only non-zero when the request asked for `includeArchived=true`. */
  readonly deleted: number;
  readonly streaming: number;
}

/**
 * Mirrors `dto.AssetAttentionResponse`, one row of `FleetSummary#assets` (docs/plans/done/MVP3-PLAN.md C-a) —
 * one asset's attention-relevant facts, everything the Command dashboard's attention queue and
 * live strip need without a second poll per asset (docs/plans/done/MVP3-PLAN.md §C-c).
 *
 * `streamId`/`batteryPercent`/`telemetryAgeMs` are absent (never `null`) exactly when the backend
 * DTO's own doc comment says so — check for key presence (`!== undefined`), never `!== null`, this
 * app's own `@JsonInclude(NON_NULL)` convention (see this file's own top doc comment): `streamId`
 * absent whenever `streaming` is `false`; `batteryPercent`/`telemetryAgeMs` absent when the asset
 * has never reported telemetry at all — deliberately still reported once the asset stops streaming
 * (the Java doc comment's own "staleness is exactly how long since we last heard from this asset"),
 * which is exactly the signal `features/command/command-logic.ts`'s attention rules read.
 *
 * Unlike `AssetSummary#lifecycle`, this field is **required**, not optional — this is a brand-new
 * endpoint with no pre-CW-a backend to stay compatible with, and the Java DTO never omits it.
 *
 * **No `sourceState` field** — the backend deliberately doesn't invent a "reconnecting"/"degraded"
 * read (see the DTO's own doc comment); `features/command/command-logic.ts`'s attention rules key off
 * `batteryPercent`/`telemetryAgeMs`/`openEventCount` only, never a fabricated fourth signal.
 *
 * `flightMode`/`armed`/`failsafe` (docs/plans/done/FC-INTEGRATIONS-PLAN.md, frozen wire contract) are this
 * cycle's own additions — the same "latest-telemetry derivation as `batteryPercent`" the backend
 * DTO's own doc comment describes, so they follow the identical absence rule: absent whenever the
 * asset has never reported a `FlightState`-carrying sample, not `null`. `features/command/command-logic.ts`'s
 * new `failsafe` attention reason reads `failsafe` directly; `gpsFixType` (GPS fix quality) is
 * deliberately **not** among these three — the wire contract doesn't surface it at the fleet-summary
 * level, only per-sample (`TelemetrySample.flightState.gpsFixType`) — see
 * `core/map/map-logic.ts#FleetMarker`'s own doc comment for where a GPS reading is sourced from
 * instead (the map's own live telemetry snapshot, not this DTO).
 */
export interface AssetAttention {
  readonly assetId: string;
  readonly displayName: string;
  readonly categoryId: string;
  readonly categoryName: string;
  readonly lifecycle: LifecycleState;
  readonly streaming: boolean;
  readonly streamId?: string;
  readonly batteryPercent?: number;
  readonly telemetryAgeMs?: number;
  readonly openEventCount: number;
  readonly flightMode?: string;
  readonly armed?: boolean;
  readonly failsafe?: boolean;
}

/**
 * Mirrors `dto.FleetSummaryResponse`, the body of `GET /api/fleet/summary` (docs/plans/done/MVP3-PLAN.md C-a)
 * — the Command dashboard's one aggregated poll (docs/plans/done/MVP3-PLAN.md §C-c), driving the attention
 * queue, the live strip's membership, and the warehouse readiness tiles all from one response.
 * Every field is always present.
 *
 * `assets` is capped server-side at 500 (`DefaultFleetSummaryService.MAX_ASSETS_IN_SUMMARY`) and
 * sorted by `displayName`; compare `assets.length` to `totalAssets` to detect truncation.
 * `categories` is **never** capped — always the true per-category picture regardless of `assets`'
 * own cap, which is what lets the readiness tiles and the empty state's own "N assets, M streaming"
 * count stay accurate even past that cap.
 */
export interface FleetSummary {
  readonly categories: readonly CategoryCounts[];
  readonly assets: readonly AssetAttention[];
  readonly totalAssets: number;
}

/** Mirrors `domain.model.DetectionEventState` as surfaced by `dto.DetectionEventResponse#state`. */
export type DetectionEventState = 'OPEN' | 'CLOSED';

/**
 * Mirrors `dto.DetectionEventResponse`, the body element of `GET /api/events` /
 * `GET /api/streams/{streamId}/events` (docs/plans/done/MVP2-PLAN.md §E, E-a/E-b) — one debounced detection
 * event ("a person was seen for a while"), distinct from the raw per-frame `DetectionResult` the
 * Live page's chip strip already reads.
 *
 * `assetId` is absent when the owning asset couldn't be resolved (a device with no asset, or one
 * whose asset had no open usage yet at open time — see the Java doc comment this mirrors).
 * `position` is absent when no telemetry was available at open time; it is stamped once, at open,
 * and never updated afterward even if the event stays open for a while (again mirroring the
 * backend's own documented behavior) — a marker plotted from it is "where it started", not "where
 * it is now".
 *
 * **No pipeline-error events here, still.** `EventPublisherPort`'s generic `Event`s
 * (`PIPELINE_ERROR` etc., the docs/plans/done/MVP2-PLAN.md §U-info ask) are still not exposed by *this* API —
 * `core/events/events-store.ts` and every page reading it are detection events only, labeled as such; the
 * player state chip (docs/main/CYCLES-PLAN.md §11 item 5 / docs/plans/done/MVP2-PLAN.md §V, V-b) stays the
 * connectivity surface. docs/plans/done/REALTIME-PLAN.md §4 (Phase R-c) **does** now expose the generic
 * `Event` for the first time, but only over the new `GET /api/live` SSE `event` topic, as the
 * unrelated {@link LiveEvent} shape below — not this one, and not as a `DetectionEvent`. The two
 * are genuinely different domain concepts (see `LiveEvent`'s own doc comment) — `core/live/live-store.ts`
 * exposes `LiveEvent`s on its own `liveEvents` signal, unconsumed by `EventsStore` this cycle.
 *
 * **Update, docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch**: this shape is now *also* the
 * payload of the `detection-events` `GET /api/live` topic (always-on, FIFO, snapshot-on-connect
 * oldest-first — see {@link LiveEnvelope}) — the "still no SSE topic" claim two paragraphs up (about
 * `LiveEvent`, the *generic*-domain-`Event` topic) never applied to this interface; this is the one
 * that gained a live source. `core/events/events-store.ts#EventsStore` projects it exactly like
 * `TelemetryStore`/`DetectionsStore` project their own topics — live when `LiveStore` is open, the
 * existing `GET /api/events` poll otherwise.
 */
export interface DetectionEvent {
  readonly id: string;
  readonly streamId: string;
  readonly assetId?: string;
  readonly label: string;
  readonly peakConfidence: number;
  readonly firstSeen: string;
  readonly lastSeen: string;
  readonly state: DetectionEventState;
  readonly position?: GeoPosition;
}

// --- Live updates (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c — GET /api/live SSE) -------------------
// The first wire shapes in this app that do not arrive through `VisionApi`/`HttpClient` at all:
// `LiveConnected`/`LiveEnvelope` are read straight off a raw `EventSource` by `core/live/live-store.ts`,
// never `JSON`-decoded by Angular's `HttpClient` pipeline — still mirrored here 1:1 with their Java
// DTOs per this file's own top doc comment, since they are still exactly as much "the wire contract"
// as anything fetched the usual way. `UpdateLiveTopicsRequest`/`LiveSubscription` *do* go through
// `VisionApi.updateLiveTopics` (a plain `PATCH`), so those two follow the normal path.

/**
 * Mirrors `dto.LiveConnectedResponse` — the payload of the `connection`-named SSE event sent once,
 * first, on every new `GET /api/live` connection (docs/plans/done/REALTIME-PLAN.md §4, item 2). Not wrapped in
 * a {@link LiveEnvelope} (no `seq`, never replayed on resume) — handshake metadata only.
 */
export interface LiveConnected {
  readonly connectionId: string;
  readonly topics: readonly string[];
}

/**
 * Mirrors `dto.EventResponse` — the payload of a {@link LiveEnvelope} whose `type` is `'event'`.
 *
 * **Not the same thing as {@link DetectionEvent}, despite the similar name.** This mirrors the
 * domain's generic `Event`/`EventType` (`DEVICE_ONLINE`/`DEVICE_OFFLINE`/`STREAM_STARTED`/
 * `STREAM_STOPPED`/`PIPELINE_ERROR`/`DETECTION`/`TRAINING`) — a different, older domain concept
 * than the debounced, tracked-over-time `DetectionEvent` (`OPEN`/`CLOSED`, `peakConfidence`,
 * `label`) that `/api/events` and `core/events/events-store.ts` serve. **`DetectionEvent` gained its own
 * `detection-events` SSE topic** (docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch — see that
 * interface's own doc comment); this `event` topic/`LiveEvent` shape remains the one with no read
 * side beyond this live feed — still no REST endpoint and no consumer in this app. `type` is the
 * domain `EventType` enum's `name()` (e.g. `"STREAM_STARTED"`), not one of this file's own
 * `AssetStatus`/`LifecycleState`-style unions — left as a plain `string` rather than an enumerated
 * union that would need to track the Java enum by hand for a signal nothing in this app renders yet.
 */
export interface LiveEvent {
  readonly id: string;
  readonly streamId?: string;
  readonly at: string;
  readonly type: string;
  readonly message: string;
  readonly attributes: Record<string, string>;
}

/**
 * Mirrors `dto.DevicesSnapshotResponse` — the payload of the always-on `devices` `GET /api/live`
 * topic (docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch, extending the R-c channel beyond its
 * original scope). The combined device-list + active-stream-list snapshot `core/fleet/fleet-store.ts#FleetStore`
 * otherwise polls via `GET /api/devices`+`GET /api/streams` every 5s — both lists travel in one
 * envelope under the channel's one shared `seq` deliberately, so a consumer can never observe a
 * device list and an active-stream list snapshotted at different moments. Always a full snapshot,
 * like `fleet`'s own payload — never a diff.
 */
export interface DevicesSnapshot {
  readonly devices: readonly Device[];
  readonly streams: readonly ActiveStream[];
}

/**
 * Mirrors `dto.LiveEnvelopeResponse` — the shape of every regular (default-named) `GET /api/live`
 * SSE `data:` line; the event's own `id:` field carries `seq` as a string (which is what makes
 * `EventSource`'s automatic `Last-Event-ID` resume work with no client code at all). A discriminated
 * union on `type` so a `switch` narrows `payload` to the right shape per branch — the seven `type`
 * values and their payloads are fixed 1:1 with `LiveTopicKind`'s wire values and
 * `LiveUpdateRegistry`'s own javadoc (vision-api). `devices`/`detection-events`/`map` (each its own
 * backend follow-up batch) are, like `fleet`/`event`, always-on — every connection gets them
 * regardless of the `topics` query parameter, so there is no subscribe/unsubscribe management for
 * either on this side, only envelope routing by `type`.
 *
 * **`map` replaced `marks`** (docs/plans/done/MAP-REWORK-PLAN.md §4.3): same always-on posture, same
 * not-snapshot-on-connect caveat (see {@link MapEventPayload}), but it is the one topic with
 * **per-connection filtering** — the server delivers a map event only to connections whose captured
 * viewer may see the event's `layerId`, so this client never filters map data for visibility.
 */
export type LiveEnvelope =
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'fleet'; readonly payload: readonly AssetSummary[] }
  | { readonly seq: number; readonly assetId: string; readonly type: 'telemetry'; readonly payload: readonly TelemetrySample[] }
  | { readonly seq: number; readonly assetId: string; readonly type: 'detections'; readonly payload: DetectionResult }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'event'; readonly payload: LiveEvent }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'devices'; readonly payload: DevicesSnapshot }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'detection-events'; readonly payload: DetectionEvent }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'map'; readonly payload: MapEventPayload };

/**
 * Mirrors `dto.UpdateLiveTopicsRequest` — the body of `PATCH /api/live/{connectionId}/topics`
 * (docs/plans/done/REALTIME-PLAN.md §4, item 2). Both fields optional here too, same `@JsonInclude`-adjacent
 * convention as every other request DTO in this file — `core/live/live-store.ts` always sends both as
 * plain arrays (possibly empty), never omits either, since the backend already defaults an absent
 * field to `[]` and an empty array is simpler to always construct than conditionally omitting one.
 */
export interface UpdateLiveTopicsRequest {
  readonly add: readonly string[];
  readonly remove: readonly string[];
}

/** Mirrors `dto.LiveSubscriptionResponse` — the response body of the `PATCH` above. */
export interface LiveSubscription {
  readonly connectionId: string;
  readonly topics: readonly string[];
}

// --- Geofencing (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract) ------------------------------

/** Mirrors `domain.model.ZoneKind` — `KEEP_IN` (must stay inside) vs. `KEEP_OUT` (must stay outside). */
export type ZoneKind = 'KEEP_IN' | 'KEEP_OUT';

/**
 * Mirrors `dto.GeofenceZoneResponse`, the body of `GET/POST /api/geofences` and
 * `PUT /api/geofences/{id}` (docs/plans/done/OPS-CORE-PLAN.md §G). `polygon` vertices are `{latitude,
 * longitude}` only — a zone's own `maxAltitudeMeters` is the one altitude concept a zone carries,
 * never a per-vertex one (each `GeoPosition`'s own `altitudeMeters` is always absent here).
 * `maxAltitudeMeters` is absent (not `null`) for "no ceiling", same `@JsonInclude(NON_NULL)`
 * convention as every other optional numeric field in this file.
 */
export interface GeofenceZone {
  readonly id: string;
  readonly name: string;
  readonly kind: ZoneKind;
  readonly polygon: readonly GeoPosition[];
  readonly maxAltitudeMeters?: number;
  readonly enabled: boolean;
}

/**
 * Mirrors `dto.GeofenceZoneRequest` — the shared create/update body (`POST /api/geofences`,
 * `PUT /api/geofences/{id}`): the response shape minus `id`. `PUT` is a wholesale replacement
 * (the backend has no partial-patch geofence endpoint), so a rename/enable-toggle must resend
 * every field, not just the one that changed — see `core/geofence/geofence-store.ts`.
 */
export interface GeofenceZoneRequest {
  readonly name: string;
  readonly kind: ZoneKind;
  readonly polygon: readonly GeoPosition[];
  readonly maxAltitudeMeters?: number;
  readonly enabled: boolean;
}

// --- The map as a Common Operational Picture (docs/plans/done/MAP-REWORK-PLAN.md §4's frozen wire contract) -
// Everything under `/api/map/**`: layers (with grantable access), marks (now layered, affiliated and
// verifiable), and drawings. **Replaces the whole `/api/marks` surface** the TACTICAL-MARKS wave
// shipped — that base path, its `Mark`/`MarkEvent` types and the `marks` SSE topic are all gone
// (§4's own "breaking change is fine — the SPA in this repo is the only client"). Every list here is
// already scoped server-side by `MapAccessPolicy` (§3): a layer the viewer may not see never reaches
// this client at all, over REST or SSE, so nothing below is a client-side visibility filter.

/** Mirrors `domain.model.Affiliation` — APP-6's friend/foe axis, the frame colour+shape of a symbol. */
export type Affiliation = 'FRIENDLY' | 'HOSTILE' | 'NEUTRAL' | 'UNKNOWN';

/**
 * Mirrors `domain.model.MarkKind` — *what the object is*, the inner glyph of a symbol. The old
 * enum's `FRIENDLY` is gone (whose it is, is now {@link Affiliation}); `UNIT`/`EQUIPMENT` are new.
 */
export type MarkKind = 'UNIT' | 'EQUIPMENT' | 'HAZARD' | 'POI' | 'TARGET';

/** Mirrors `domain.model.MarkStatus` — `CLEARED` marks drop off `GET /api/map/marks` and are removed client-side. */
export type MarkStatus = 'ACTIVE' | 'CLEARED';

/** Mirrors `domain.model.MarkSource` — `DETECTION` marks came from the cockpit's geolocate action, an honest estimate. */
export type MarkSource = 'MANUAL' | 'DETECTION';

/** Mirrors `domain.model.Verification.VerificationState` — an `UNVERIFIED` mark renders provisional (dashed frame, dimmed). */
export type VerificationState = 'UNVERIFIED' | 'CONFIRMED' | 'REJECTED';

/**
 * Mirrors `domain.model.LayerKind`. `COP` is the single deployment-wide common-picture layer
 * (everyone sees it, managers write to it, it is the default promotion target and can never be
 * renamed or deleted); `TEAM` belongs to a group; `PERSONAL` to one user.
 */
export type LayerKind = 'COP' | 'TEAM' | 'PERSONAL';

/**
 * Mirrors `domain.model.AccessLevel`, declared least→most privileged. Comparisons go through
 * `core/map-data/layers-logic.ts#atLeast` (one ranking, never re-derived at a call site) — this app
 * *does* compare these by rank, unlike {@link Role}, because §3's whole model is "max of the
 * matching rules".
 */
export type AccessLevel = 'VIEW' | 'CONTRIBUTE' | 'MANAGE';

/** Mirrors `domain.model.DrawKind` — LINE/ARROW need ≥2 points, POLYGON ≥3, TEXT exactly 1 + a label. */
export type DrawKind = 'LINE' | 'POLYGON' | 'ARROW' | 'TEXT';

/** Mirrors `domain.model.LayerGrant.SubjectType` — a grant names either one user or one group. */
export type GrantSubjectType = 'USER' | 'GROUP';

/** Mirrors `dto.GrantDto` — one row of a layer's access list. `subjectId` is a user id or a group id per `subjectType`. */
export interface LayerGrant {
  readonly subjectType: GrantSubjectType;
  readonly subjectId: string;
  readonly level: AccessLevel;
}

/**
 * Mirrors `dto.LayerResponse`, the body of every `/api/map/layers` endpoint and the `layer` field of
 * a {@link MapEventPayload}. `myAccess` is the viewer's **server-resolved** effective level (§3's max
 * rule) — the UI hides what it forbids but never re-derives it, and a 403/404 from the server is
 * still the arbiter. `grants` is present **only when `myAccess === 'MANAGE'`** (and always absent
 * over SSE, §4.3), so a reader must treat `undefined` as "not mine to see", never as "no grants".
 * `ownerUserId`/`groupId` are absent (not `null`) when the layer has none.
 */
export interface MapLayer {
  readonly layerId: string;
  readonly name: string;
  readonly kind: LayerKind;
  readonly ownerUserId?: string;
  readonly groupId?: string;
  readonly myAccess: AccessLevel;
  readonly grants?: readonly LayerGrant[];
  readonly markCount: number;
  readonly drawingCount: number;
  readonly createdAt: string;
}

/** Mirrors `dto.CreateLayerRequest` — `POST /api/map/layers`. `kind` is `TEAM` (needs `groupId`, managers only) or `PERSONAL` (anyone); `COP` can never be created. */
export interface CreateLayerRequest {
  readonly name: string;
  readonly kind: Exclude<LayerKind, 'COP'>;
  readonly groupId?: string;
}

/** The body of `PATCH /api/map/layers/{id}` — rename only (§4.1); the COP layer 403s. */
export interface RenameLayerRequest {
  readonly name: string;
}

/** The body of `PUT /api/map/layers/{id}/grants` — **wholesale**, like `GeofenceZoneRequest`'s own PUT: send the full list, not a delta. */
export interface SetLayerGrantsRequest {
  readonly grants: readonly LayerGrant[];
}

/**
 * Mirrors `dto.MarkResponse`, the body of every `/api/map/marks` endpoint and the `mark` field of a
 * {@link MapEventPayload} — one shape regardless of transport. **Position is flat here**
 * (`latitude`/`longitude`/`altitudeMeters`), not the nested {@link GeoPosition} the old `/api/marks`
 * shape used: `core/map-data/mark-logic.ts#markPosition` is the one place that reassembles it for
 * the map/readout code that wants a `GeoPosition`. Optional fields are absent (not `null`), the same
 * `@JsonInclude(NON_NULL)` convention as everywhere else in this file — including
 * `verifiedByUserId`/`verifiedAt`, which only exist once someone actually decided.
 */
export interface MapMark {
  readonly markId: string;
  readonly layerId: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
  readonly kind: MarkKind;
  readonly affiliation: Affiliation;
  readonly label: string;
  readonly note?: string;
  readonly createdByUserId: string;
  readonly groupId?: string;
  readonly createdAt: string;
  readonly status: MarkStatus;
  readonly source: MarkSource;
  readonly verification: VerificationState;
  readonly verifiedByUserId?: string;
  readonly verifiedAt?: string;
}

/**
 * Mirrors `dto.CreateMarkRequest` — a manual mark (an armed map click). `layerId` omitted lets the
 * server pick the caller's default contributable layer (§3: first TEAM layer, else an auto-created
 * PERSONAL one — never COP), which is exactly what the palette sends when the viewer has no
 * CONTRIBUTE layer to choose from.
 */
export interface CreateMarkRequest {
  readonly layerId?: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
  readonly kind: MarkKind;
  readonly affiliation: Affiliation;
  readonly label: string;
  readonly note?: string;
}

/**
 * Mirrors `dto.GeolocateMarkRequest` — the cockpit's one-tap "Mark target": the server reads
 * `assetId`'s freshest telemetry and projects a ground point ahead of the drone. Every field but
 * `assetId` is optional (`kind` defaults to `TARGET`, `label` to `"Contact"`, `depressionDegrees` to
 * the domain's own 45°), so the button can still send `assetId` alone; the Fly palette additionally
 * sends whatever kind/affiliation/layer the operator has selected (§5.2).
 */
export interface GeolocateMarkRequest {
  readonly assetId: string;
  readonly layerId?: string;
  readonly kind?: MarkKind;
  readonly affiliation?: Affiliation;
  readonly label?: string;
  readonly note?: string;
  readonly depressionDegrees?: number;
}

/**
 * Mirrors `dto.PatchMarkRequest` — a true partial patch: every field optional, only a present field
 * changes anything. Drives annotation (label/note/kind/affiliation), drag-to-correct
 * (`latitude`+`longitude` only) and clear (`status` only) alike. Note the **flat** position fields,
 * matching {@link MapMark}; `core/map-data/marks-store.ts` never sends a field it doesn't mean.
 */
export interface PatchMarkRequest {
  readonly latitude?: number;
  readonly longitude?: number;
  readonly altitudeMeters?: number;
  readonly kind?: MarkKind;
  readonly affiliation?: Affiliation;
  readonly label?: string;
  readonly note?: string;
  readonly status?: MarkStatus;
}

/** The body of `POST /api/map/marks/{id}/verify` — a manager's decision; there is no "un-verify" back to `UNVERIFIED`. */
export interface VerifyMarkRequest {
  readonly decision: Exclude<VerificationState, 'UNVERIFIED'>;
}

/** The body of `POST /api/map/marks/{id}/promote` — omit `targetLayerId` for the default (the COP layer). */
export interface PromoteMarkRequest {
  readonly targetLayerId?: string;
}

/** Mirrors `dto.PositionDto` — a drawing vertex. Structurally identical to {@link GeoPosition}, declared separately only because the Java DTO is. */
export interface PositionDto {
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
}

/**
 * Mirrors `dto.DrawingResponse` — a line/polygon/arrow/text annotation on a layer. `colorToken` is a
 * UI token *name* (`accent`/`danger`/…), never a hex value — resolved to a literal stroke colour by
 * `shared/map/tactical-map/tactical-map-logic.ts#drawingColor`, because Leaflet writes path colours
 * as SVG presentation attributes that cannot resolve `var(--token)`.
 */
export interface MapDrawingResponse {
  readonly drawingId: string;
  readonly layerId: string;
  readonly kind: DrawKind;
  readonly label?: string;
  readonly colorToken?: string;
  readonly points: readonly PositionDto[];
  readonly createdByUserId: string;
  readonly createdAt: string;
}

/** Mirrors `dto.CreateDrawingRequest` — `layerId` omitted defaults server-side exactly like {@link CreateMarkRequest}'s. */
export interface CreateDrawingRequest {
  readonly layerId?: string;
  readonly kind: DrawKind;
  readonly label?: string;
  readonly colorToken?: string;
  readonly points: readonly PositionDto[];
}

/** Mirrors `dto.PatchDrawingRequest` — geometry and/or details; an absent field is unchanged. */
export interface PatchDrawingRequest {
  readonly points?: readonly PositionDto[];
  readonly label?: string;
  readonly colorToken?: string;
}

/**
 * Mirrors `dto.MapEventPayload` — the payload of a {@link LiveEnvelope} whose `type` is `'map'`,
 * the **scoped** topic that replaced `marks` (docs/plans/done/MAP-REWORK-PLAN.md §4.3). One always-on topic
 * carries every map lifecycle event for every entity, with the entity and the lifecycle riding in
 * `entity`/`action` rather than three separate topics (mirroring how `detection-events` carries
 * OPEN/CLOSED in one). **Exactly one of `mark`/`drawing`/`layer` is present**, matching `entity`;
 * `layer.grants` is always absent here even for a layer the viewer manages (§4.3 — grants only ever
 * travel over REST).
 *
 * `action` semantics: `'created'`/`'updated'` upsert; `'cleared'` (marks only — a status transition)
 * and `'deleted'` both remove. `layerId` is always the event's own layer, which is what lets
 * `LiveUpdateRegistry` deliver an event only to connections whose viewer may see that layer.
 *
 * **Deliberately not snapshot-on-connect**: a fresh connection gets no backlog on this topic, so
 * every `core/map-data/**` store does its own initial `GET` first and folds deltas on top.
 */
export interface MapEventPayload {
  readonly entity: 'mark' | 'drawing' | 'layer';
  readonly action: 'created' | 'updated' | 'cleared' | 'deleted';
  readonly layerId: string;
  readonly mark?: MapMark;
  readonly drawing?: MapDrawingResponse;
  readonly layer?: MapLayer;
}

// --- Recording + clip export (docs/plans/done/OPS-CORE-PLAN.md §R's frozen wire contract) ------------------

/**
 * Mirrors `dto.UsageRecordingResponse`, the body of `GET /api/usages/{usageId}/recording`
 * (docs/plans/done/OPS-CORE-PLAN.md §R). `url`/`start`/`durationSeconds` are each omitted entirely (not
 * `null`) when `available` is `false` — a known usage with nothing to play back is not an error,
 * just an honest `{"available":false}`; `features/replay/**`'s empty state handles it. `url` is
 * mediamtx's own playback `/get` URL for `[start, start + durationSeconds)` — never proxied, POST/
 * fetched straight from it, same "absolute origin, never rewritten" rule as `ActiveStream#whepUrl`.
 */
export interface UsageRecording {
  readonly available: boolean;
  readonly url?: string;
  readonly start?: string;
  readonly durationSeconds?: number;
}

// --- Guarded command TX (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's frozen contract) ----------------

/**
 * Mirrors the `202` body of `POST /api/assets/{assetId}/return-home` (docs/plans/active/DRONE-INFRA-PLAN.md I-e
 * Stage 1's frozen contract) — a command was sent either way; this only says whether the vehicle
 * acknowledged it. `ACCEPTED` = a `COMMAND_ACK` arrived within the timeout; `NO_ACK` = the UDP
 * packet went out with no acknowledgement heard back in time — honest, not necessarily a failure
 * (the vehicle may still have executed it). A `404`/`409` never reaches this type at all — those
 * are `HttpErrorResponse`s (`404` unknown asset, `409 {message}` not commandable), handled by the
 * caller's own catch, not a third member of this union.
 */
export type ReturnHomeResult = 'ACCEPTED' | 'NO_ACK';

/** The response body itself — see {@link ReturnHomeResult}'s own doc comment for what each value means. */
export interface ReturnHomeResponse {
  readonly result: ReturnHomeResult;
}

// --- Guarded command TX — arm/disarm/mode select (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2's frozen
// contract, extends Stage 1 above) ----------------------------------------------------------------

/**
 * Mirrors the `202` body of `POST /api/assets/{id}/mode`/`arm`/`disarm` (docs/plans/active/DRONE-INFRA-PLAN.md
 * I-e Stage 2's frozen contract) — the identical two-value shape as Stage 1's
 * {@link ReturnHomeResult} (a command was sent either way; this only says whether the vehicle
 * acknowledged it), kept as its own type rather than reusing `ReturnHomeResult` so a future
 * divergence between "bring home" and this trio doesn't silently couple them.
 */
export type FlightCommandResult = 'ACCEPTED' | 'NO_ACK';

/** The response body shared by `mode`/`arm`/`disarm` — all three return this identical shape. */
export interface FlightCommandResponse {
  readonly result: FlightCommandResult;
}

/**
 * Mirrors the `200` body of `GET /api/assets/{id}/flight-capabilities` (docs/plans/active/DRONE-INFRA-PLAN.md
 * I-e Stage 2's frozen contract) — what `features/fly/flight-command-panel.ts` may show for the
 * asset's currently-tracked vehicle. `commandable` gates the whole panel (`false` for a Betaflight/
 * never-heard vehicle, per the plan's own capability matrix — the same cases Stage 1's `returnHome`
 * already refuses with a `409`, surfaced here ahead of time as data instead of waiting for a
 * rejected command); `armSupported`/`modeSelectSupported` independently gate the Arm/Disarm buttons
 * and the Mode picker; `selectableModes` is the vehicle-family mode-name list, empty whenever
 * `modeSelectSupported` is `false`. No `NON_NULL`-style optionality — every field is always present
 * on a `200`.
 */
export interface FlightCapability {
  readonly commandable: boolean;
  readonly armSupported: boolean;
  readonly modeSelectSupported: boolean;
  readonly selectableModes: readonly string[];
}

// --- Guided drone onboarding (docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen wire contract) --------------

/** One site-local IPv4 address this platform's host is reachable on. Mirrors `dto.NetworkAddressResponse`. */
export interface NetworkAddress {
  readonly address: string;
  readonly interfaceName: string;
}

/**
 * Mirrors `dto.SystemNetworkResponse`, the body of `GET /api/system/network` (docs/plans/active/DRONE-INFRA-PLAN.md
 * I-g's frozen wire contract) — every site-local IPv4 address of an up, non-loopback interface, sorted
 * by interface name, plus the MAVLink heartbeat scanner's own listen port (shared with the backend's
 * `vision.discovery.mavlink-port` property so the two can never disagree). This is what lets the
 * onboarding wizard's "Add a real drone" config snippets carry this platform's own reachable
 * address/port instead of asking the operator to type one in (`features/onboarding/drone-config-logic.ts#configSnippets`).
 *
 * `addresses` is never absent, but **may be empty** — a host with no detectable site-local interface
 * is not an error (the plan's own wording: "Never errors for 'no addresses'"); the wizard degrades to
 * a manual-address text input rather than treating an empty list as a failed fetch.
 */
export interface SystemNetworkResponse {
  readonly addresses: readonly NetworkAddress[];
  readonly mavlinkPort: number;
}

// --- Auth (docs/plans/done/U-AUTH-PLAN.md wave 3's frozen contract; wave 4 is this app's own UI) -----------
// `core/auth/auth-store.ts` is the only caller of the three `VisionApi` methods these types back —
// no page/component talks to `/api/auth/**` directly, mirroring every other store in this app.

/**
 * Mirrors `domain.model.Role` — ordered least→most privileged, though this app never compares
 * roles by ordinal (only ever renders a label — `core/auth/auth-logic.ts#roleLabel`); the ordering
 * is the domain's own concern (`User.topRole()`), not re-derived here.
 */
export type Role = 'PILOT' | 'MANAGER' | 'ADMIN';

/** Mirrors `dto.MembershipResponse`, one row of `MeResponse#memberships` — a user's role within one group. */
export interface Membership {
  readonly groupId: string;
  readonly groupName: string;
  readonly role: Role;
}

/**
 * Mirrors `dto.MeResponse` — the frozen shape of `GET /api/auth/me` and `POST /api/auth/login`
 * (docs/plans/done/U-AUTH-PLAN.md wave 3). `authEnabled` is what lets the SPA decide whether a login screen
 * makes sense at all: while `vision.auth.enabled=false` (the default), every one of these
 * endpoints is a no-op that reports this same shape for a fixed dev admin, `authEnabled: false` —
 * `core/auth/auth-store.ts` treats that response as already-logged-in (dev parity: the app works
 * with zero auth exactly as it did before this slice, never showing a login screen). `topRole` is
 * the highest `Role` across `memberships` (the domain's own `User.topRole()`, mirrored — a real
 * account always carries at least one membership, per that method's own contract).
 *
 * **No visibility scoping rides on this type** — every logged-in user still sees the whole fleet
 * (docs/plans/done/U-AUTH-PLAN.md's own "identity becomes real; nothing is visibility-scoped yet" framing);
 * `memberships`/`topRole` back the identity chip's role badge only, in this slice.
 */
export interface MeResponse {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly email: string;
  readonly memberships: readonly Membership[];
  readonly topRole: Role;
  readonly authEnabled: boolean;
}

// --- Org settings: users, groups, pilot assignment, activity ------------------------------------
// docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2's frozen wire contract (waves 1–2, backend done). `core/org/`
// (users/groups), `features/asset-detail/pilots-card.ts` (assignment), and `features/activity/`
// (activity) are the only callers of the `VisionApi` methods these types back — same "no page talks
// to a URL directly" rule as everywhere else here.

/**
 * Mirrors `dto.UserResponse.MembershipView` — a user's role within one group as returned by the
 * `/api/users` admin surface. Deliberately **not** `Membership` above: that one (from `MeResponse`)
 * carries `groupName` for the identity chip's benefit; this admin-list row carries only the raw
 * `groupId`, matching the backend DTO exactly (the org-settings UI resolves a name against the
 * groups list it already loads, rather than the backend denormalizing it onto every membership).
 */
export interface UserMembership {
  readonly groupId: string;
  readonly role: Role;
}

/**
 * Mirrors `dto.UserResponse` — one row of `GET /api/users` / the body of `POST /api/users` and
 * `POST /api/users/{id}/enabled`. `topRole` is **optional** here (unlike `MeResponse#topRole`): a
 * user with no memberships has no top role at all, and the backend returns `null` for that case
 * (`User.topRole()` is an `Optional`), so a reader must treat its absence as "no role yet", never a
 * crash. Every other field is always present.
 */
export interface UserSummary {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly email: string;
  readonly enabled: boolean;
  readonly memberships: readonly UserMembership[];
  readonly topRole?: Role;
}

/**
 * Mirrors `dto.CreateUserRequest` — the invite/create body for `POST /api/users`. `memberships`
 * and `enabled` are optional (the backend defaults an omitted `enabled` to `true` and an omitted
 * `memberships` to an empty list); the scope rule — an inviter may only grant a role at or below
 * their own — is enforced server-side (docs/plans/done/U-SCOPE-PLAN.md), surfaced to this UI as a `403`.
 */
export interface CreateUserRequest {
  readonly username: string;
  readonly displayName: string;
  readonly email: string;
  readonly password: string;
  readonly memberships?: readonly UserMembership[];
  readonly enabled?: boolean;
}

/** Mirrors `dto.GroupResponse` — one row of `GET /api/groups`. `parentGroupId` absent = a root group (`core/org/org-logic.ts#buildGroupTree` treats absent/unknown/self as a root). */
export interface GroupSummary {
  readonly id: string;
  readonly name: string;
  readonly parentGroupId?: string;
}

/** Mirrors `dto.CreateGroupRequest` — the body of `POST /api/groups`. `parentGroupId` omitted creates a root group. */
export interface CreateGroupRequest {
  readonly name: string;
  readonly parentGroupId?: string;
}

/** Mirrors `dto.PilotResponse` — one row of `GET /api/assets/{id}/pilots`. A record (not a bare id) so the shape can grow (a display name, an assigned-at time) without a wire break, exactly as the backend DTO's own doc comment notes. */
export interface AssignedPilot {
  readonly userId: string;
}

/** Mirrors `dto.AssignmentResponse` — one row of `GET /api/me/assignments`, an asset the acting pilot may fly. Same room-to-grow shape as `AssignedPilot`. */
export interface Assignment {
  readonly assetId: string;
}

/**
 * Mirrors `dto.AuditEntryResponse` — one entry of `GET /api/me/activity` (docs/plans/done/U-SCOPE-PLAN.md
 * feature 7), the acting user's own recent actions. `summary` is written to read on its own (no id
 * reconstruction needed); `details` carries before→after specifics. `action` is one of
 * `CREATED`/`UPDATED`/`DEACTIVATED`/`ACTIVATED`/`DELETED`/`RESTORED`, `targetType` one of
 * `ASSET`/`DEVICE` — both left as plain strings here (the UI renders them via
 * `core/org/org-logic.ts#formatActivity`, which tolerates an unrecognized value rather than a
 * closed union that a new backend action would break).
 */
export interface AuditEntry {
  readonly id: string;
  readonly occurredAt: string;
  readonly actor: string;
  readonly action: string;
  readonly targetType: string;
  readonly targetId: string;
  readonly summary: string;
  readonly details: Readonly<Record<string, string>>;
}

// --- Manual control relay (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4 — WS /ws/manual-control) -----------
// Like `LiveConnected`/`LiveEnvelope` above, these frames arrive over a raw `WebSocket`
// (`core/rc/manual-control-client.ts`), never through `VisionApi`/`HttpClient` — mirrored here 1:1
// with the plan's own frozen JSON shapes per this file's own top doc comment: this is exactly as
// much "the wire contract" as anything fetched the usual way. **SITL only** — see the plan doc for
// the full hardware/safety framing; this file only carries the JSON shapes.

/**
 * One physical control → one RC channel, as sent on the `engaged` frame's `channelMap` — trims
 * `domain.model.ControlBinding` to what the client needs to *display* the map (the calibration
 * fields `minMicros`/`centerMicros`/`maxMicros`/`deadband`/`reversed` stay server-side; nothing
 * here recomputes microseconds — that mapping is entirely the backend's `ChannelMap#apply` job).
 */
export interface ManualControlChannelBinding {
  readonly source: 'AXIS' | 'BUTTON';
  readonly sourceIndex: number;
  readonly rcChannel: number;
  readonly label: string;
}

/**
 * `denied.code` — the four refusal codes §4 freezes (`OUT_OF_SCOPE`/`NOT_COMMANDABLE`/
 * `UNSUPPORTED`/`ALREADY_ENGAGED`) for an `engage` refusal, widened to a plain `string` rather than
 * a closed union: `ManualControlWebSocketHandler` (vision-api) also sends `denied` with
 * `MALFORMED`/`UNKNOWN_TYPE`/`BAD_REQUEST` for a frame the handler couldn't process at all — its
 * own javadoc calls this out explicitly ("deliberately a plain string, not a closed enum on the
 * wire"). `denied.reason` is always the human-readable string this app actually renders; `code`
 * exists for a caller that wants to branch on it, not because this app currently does. */
export type ManualControlDeniedCode = string;

/** `released.reason` — an explicit client `release` vs. any socket teardown the server observed
 * (which may itself be best-effort, per §4: "the socket may already be gone"). */
export type ManualControlReleasedReason = 'EXPLICIT' | 'SOCKET_CLOSE';

// Client → server frames.

export interface ManualControlEngageMessage {
  readonly type: 'engage';
  readonly assetId: string;
}

/** `axes`/`buttons` are the raw Gamepad API values straight off `RcInputService`
 * (`axes()`/`buttons()`) — axes -1..1, buttons 0..1; mapping to microseconds is entirely
 * server-side via `ChannelMap`, never computed here. */
export interface ManualControlChannelsMessage {
  readonly type: 'channels';
  readonly axes: readonly number[];
  readonly buttons: readonly number[];
  readonly seq: number;
  readonly tSent: number;
}

export interface ManualControlReleaseMessage {
  readonly type: 'release';
}

export type ManualControlClientMessage =
  | ManualControlEngageMessage
  | ManualControlChannelsMessage
  | ManualControlReleaseMessage;

// Server → client frames.

export interface ManualControlEngagedMessage {
  readonly type: 'engaged';
  readonly assetId: string;
  readonly rateHz: number;
  readonly channelMap: readonly ManualControlChannelBinding[];
}

export interface ManualControlDeniedMessage {
  readonly type: 'denied';
  readonly code: ManualControlDeniedCode;
  readonly reason: string;
}

/** One per `channels` frame the server processed — `seq`/`tSent` echo the client's own values,
 * `tServer` is the server's own receipt clock. `manual-control-logic.ts#computeLatencyMs` measures
 * glass-to-stick RTT as `Date.now() - tSent` at the *client's* receipt of this frame, not
 * `tServer - tSent` (that would only be one-way server-processing latency, not a round trip). */
export interface ManualControlAckMessage {
  readonly type: 'ack';
  readonly seq: number;
  readonly tSent: number;
  readonly tServer: number;
}

export interface ManualControlReleasedMessage {
  readonly type: 'released';
  readonly reason: ManualControlReleasedReason;
}

/** The session was already auto-released server-side by the input-loss watchdog before this
 * arrived — the client must re-`engage` to resume, never assume it can keep streaming. */
export interface ManualControlWatchdogMessage {
  readonly type: 'watchdog';
  readonly timeoutMs: number;
}

export type ManualControlServerMessage =
  | ManualControlEngagedMessage
  | ManualControlDeniedMessage
  | ManualControlAckMessage
  | ManualControlReleasedMessage
  | ManualControlWatchdogMessage;

// --- CV training / dataset improvement loop (docs/plans/done/CV-TRAINING-PLAN.md §3-4's frozen wire contract,
// Wave T5) ------------------------------------------------------------------------------------
// Every endpoint below is gated server-side by `vision.training.enabled` (default `false`) — the
// whole `DatasetController`/`LabelingController` pair is absent, not just erroring, when it's off,
// so a request 404s exactly like any unmapped path. `core/training/training-store.ts`'s own doc
// comment explains how that one clean signal (a 404 on the *list* call, the one endpoint that can
// never legitimately 404 for any other reason) becomes an honest "not enabled here" empty state
// instead of a generic error toast.

/** Mirrors `dto.AnnotationResponse`/`AnnotationRequest`. `MODEL` = pre-filled from the stream's live
 *  detections at capture time; `OPERATOR` = drawn or corrected by hand in the labeling editor. */
export type AnnotationSource = 'MODEL' | 'OPERATOR';

/** One ground-truth (or model-suggested, until reviewed) box + label on a `TrainingSample`. `box` is
 *  the same normalized top-left `[0,1]` shape as every other `BoundingBox` in this app. */
export interface Annotation {
  readonly label: string;
  readonly source: AnnotationSource;
  readonly box: BoundingBox;
}

/** Mirrors domain `SampleStatus`. `PENDING` = just captured, annotations are still the model's own
 *  guess; `LABELED` = operator-confirmed ground truth, the only status export includes; `DISCARDED`
 *  = operator rejected the frame (kept for provenance, never exported). */
export type SampleStatus = 'PENDING' | 'LABELED' | 'DISCARDED';

/**
 * Mirrors `dto.SampleResponse` — one captured frame + its (evolving) annotations. `assetId`/
 * `labeledBy`/`labeledAt` are genuinely **absent** (`@JsonInclude(NON_NULL)`), not serialized
 * `null`, until resolved/reviewed — vision-api/MODULE.md's own Status/T4 entry flags this as a
 * deliberate deviation from the plan's illustrative JSON (which shows literal `null`s); this
 * mirrors every other optional DTO field in this file (`AssetDeletionResponse#…`, etc.).
 */
export interface TrainingSample {
  readonly id: string;
  readonly datasetId: string;
  readonly streamId: string;
  readonly assetId?: string;
  readonly capturedAt: string;
  readonly width: number;
  readonly height: number;
  readonly status: SampleStatus;
  readonly labeledBy?: string;
  readonly labeledAt?: string;
  readonly annotations: readonly Annotation[];
}

export type DatasetStatus = 'OPEN' | 'ARCHIVED';

/**
 * Mirrors `dto.DatasetResponse`. `targetCategory` is genuinely absent (`NON_NULL`) when the dataset
 * isn't tied to one category. `sampleCounts` always carries all three `SampleStatus` keys, even at
 * zero — computed server-side from `TrainingSampleRepositoryPort#countByDataset`, not a stored field.
 */
export interface Dataset {
  readonly id: string;
  readonly name: string;
  readonly targetCategory?: string;
  readonly classes: readonly string[];
  readonly status: DatasetStatus;
  readonly createdAt: string;
  readonly sampleCounts: Record<SampleStatus, number>;
}

/** Mirrors `dto.DatasetsResponse` — `GET /api/datasets`'s wrapper shape (not a bare array), mirroring `CvModelsResponse`'s own wrapped-list precedent. */
export interface DatasetsResponse {
  readonly datasets: readonly Dataset[];
}

/** Mirrors `dto.SamplesResponse` — `GET /api/datasets/{id}/samples`'s wrapper shape. */
export interface SamplesResponse {
  readonly samples: readonly TrainingSample[];
}

/** Mirrors `dto.CreateDatasetRequest`. Absent `classes` defaults to `[]` server-side. */
export interface CreateDatasetRequest {
  readonly name: string;
  readonly targetCategory?: string;
  readonly classes?: readonly string[];
}

/** Mirrors `dto.CaptureSampleRequest` — the body of `POST /api/streams/{streamId}/samples`. */
export interface CaptureSampleRequest {
  readonly datasetId: string;
}

/** Mirrors `dto.LabelAnnotationsRequest` — the confirm/correct body of `PUT
 *  /api/samples/{id}/annotations`. `status` is restricted to the two reviewed terminal states (a
 *  sample can never be PUT back to `PENDING`); absent `annotations` defaults to `[]` server-side
 *  (an empty label set — a valid "confirmed, nothing here" / negative sample). */
export interface LabelAnnotationsRequest {
  readonly status: 'LABELED' | 'DISCARDED';
  readonly annotations: readonly Annotation[];
}

// --- CV model registry (docs/plans/done/CV-TRAINING-PLAN.md §7-8, Phase 2 T9/T10) -------------------------
// The dynamic model registry — every model reference cv-service's own `Training/ListModels` RPC
// actually reports, live, and the one place a model gets promoted to production. **Not** the same
// roster as `CvModelsResponse`/`GET /api/cv/models` above (a static, config-backed picker for the
// Fly cockpit's model dropdown) — see `ModelRegistryController`'s own javadoc (vision-api/MODULE.md,
// "Not the same roster as `CvModelsController`"). Gated by the same `vision.training.enabled` flag
// as the dataset/labeling endpoints above; `features/models/**` (Phase 2 T10) is the one consumer.

/**
 * Mirrors `dto.RegisteredModelResponse` — one row of `GET /api/cv/registry/models`. No optional
 * fields (no `@JsonInclude(NON_NULL)` server-side, mirroring `CvModelResponse`'s own posture) —
 * `version` is routinely `""` today, since cv-service's registry tracks no per-model version data
 * yet (`cv_service/server.py#ListModels`'s own doc comment). See
 * `features/models/models-logic.ts#resolvePromoteVersion` for why that matters when promoting.
 */
export interface RegisteredModel {
  readonly id: string;
  readonly version: string;
  readonly active: boolean;
}

/** Mirrors `dto.RegisteredModelsResponse` — `GET /api/cv/registry/models`'s wrapper shape, the same `{"models":[...]}` precedent `CvModelsResponse`/`DatasetsResponse` set. */
export interface RegisteredModelsResponse {
  readonly models: readonly RegisteredModel[];
}

/** Mirrors `dto.PromoteModelRequest` — the body of `POST /api/cv/registry/models/{id}/promote`. `version` must be non-blank server-side (`ModelRef`'s own compact-constructor check, surfaced as a 400). */
export interface PromoteModelRequest {
  readonly version: string;
}

// --- CV training-job flow (docs/plans/done/CV-TRAINING-PLAN.md §7-8, Phase 2's last web wave) -------------
// Starting a fine-tune run against a dataset and polling its progress — the run in between
// `DatasetController`/`LabelingController` (build the dataset) and `ModelRegistryController`
// (promote the result). Gated by the same `vision.training.enabled` flag as every other CV-training
// endpoint above; `features/training-jobs/**` is the one consumer, reached from
// `features/labeling/dataset-detail.ts`'s own "Train a model" card.

/** Mirrors domain `JobState`. `RUNNING` is the only state a poller keeps chasing —
 *  `SUCCEEDED`/`FAILED` are settled. A training *failure* is reported here, never as an HTTP error
 *  (`TrainingJobController`'s own javadoc, vision-api/MODULE.md). */
export type TrainingJobState = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/** Mirrors `dto.StartTrainingJobRequest` — the body of `POST /api/datasets/{id}/train`. Both fields
 *  are re-validated server-side (`TrainingJobSpec`'s own compact constructor: non-blank `baseModel`,
 *  positive `epochs`), surfaced as a 400. */
export interface StartTrainingJobRequest {
  readonly baseModel: string;
  readonly epochs: number;
}

/**
 * Mirrors `dto.TrainingJobResponse` — the flattened, pollable state of one CV fine-tune job. No
 * optional fields (no `@JsonInclude(NON_NULL)` server-side, the same "no nullable fields" posture
 * `RegisteredModelResponse` takes): `epoch`/`totalEpochs`/`loss`/`map50` are `0`/`0.0` and `message`
 * is `""` before the first progress message arrives; `message` becomes the produced model's registry
 * id on `SUCCEEDED`, the failure reason on `FAILED`.
 */
export interface TrainingJobResponse {
  readonly jobId: string;
  readonly baseModel: string;
  readonly datasetId: string;
  readonly epochs: number;
  readonly epoch: number;
  readonly totalEpochs: number;
  readonly loss: number;
  readonly map50: number;
  readonly state: TrainingJobState;
  readonly message: string;
  readonly startedAt: string;
}

/** Mirrors `dto.TrainingJobsResponse` — `GET /api/training/jobs`'s wrapper shape, the same `{"jobs":[...]}` precedent `DatasetsResponse`/`RegisteredModelsResponse` set. Every tracked job, newest-first by `startedAt`. */
export interface TrainingJobsResponse {
  readonly jobs: readonly TrainingJobResponse[];
}
