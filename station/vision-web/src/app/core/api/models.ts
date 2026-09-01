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
 * Mirrors `kernel.DeviceOrigin` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4) — whether a
 * device is a real sensor (`LIVE`) or a synthetic one fitted onto an asset (`SIMULATED`). Lets
 * "simulated" stop being an asset-wide category and become a property of one device, so an
 * operator can fit a synthetic camera onto an otherwise-real vehicle that has no camera yet. Sent
 * case-insensitively; responses always echo the enum's `name()`, like `Capability`.
 */
export type DeviceOrigin = 'LIVE' | 'SIMULATED';

/**
 * Mirrors `dto.DeviceResponse`.
 *
 * There is no `type` field: the `DeviceType` enum was removed server-side in favor of the
 * data-driven category model, which applies to `Asset`s, not raw devices (see `Category`,
 * `AssetSummary`). `state` can now be `DELETED` too (docs/main/CYCLES-PLAN.md §8 — a device can be
 * archived, e.g. as the last source of an asset, without the asset itself going away).
 *
 * `origin` is always populated on the wire (`DeviceResponse#origin` is never null), but kept
 * optional here — same as `Capability` was on first introduction — so existing test fixtures and
 * mocks built before R4 (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md) keep compiling;
 * tighten to required in the UI wave that actually renders it.
 */
export interface Device {
  readonly id: string;
  readonly name: string;
  readonly capabilities: readonly Capability[];
  readonly protocol: string;
  readonly uri: string;
  readonly options: Record<string, string>;
  readonly state: LifecycleState;
  readonly origin?: DeviceOrigin;
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
  readonly origin?: DeviceOrigin;
}

/**
 * Mirrors `dto.RegisterDeviceRequest`. `capabilities` is optional; a missing/empty value is
 * defaulted server-side **from `protocol`** (`mavlink` → `[TELEMETRY]`, everything else →
 * `[VIDEO]`) — not a blanket `[VIDEO]` default (verified against the DTO's own current doc
 * comment; the discovery inbox's "attach to existing asset" flow, `core/discovery/discovery-inbox-logic.ts#buildDeviceSpecFromCandidate`,
 * relies on exactly this to leave `capabilities` unset for a candidate's suggested stream). An
 * explicit list, when sent, always wins over the default. `origin` is optional and defaults
 * server-side to `LIVE`. No `type` field — see `Device`.
 */
export interface RegisterDeviceRequest {
  readonly name: string;
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
  readonly origin?: DeviceOrigin;
}

/**
 * Mirrors `dto.ActiveStreamResponse`. `viewUrl`/`whepUrl` are each independently absent when the
 * active publisher has no such viewing endpoint wired.
 *
 * `whepUrl` (docs/plans/done/MVP2-PLAN.md §L, L-a) is mediamtx's own **absolute origin URL** for the WebRTC
 * (WHEP) viewing endpoint — unlike `viewUrl`, which can be app-relative (`HlsProxyController`
 * reverse-proxies HLS byte fetches), WHEP is a POST/SDP + ICE exchange a stateless proxy cannot
 * forward, so this is never app-relative and must never be proxied — POST straight to it.
 *
 * **`burnedIn` is gone** (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1, wave W3) — server-side burn-in
 * itself is deleted, not defaulted off, so there is no longer a wire field to mirror; the video is
 * always clean pixels and the client canvas overlay (`shared/player/detection-overlay-logic.ts`) is
 * simply the product now.
 */
export interface ActiveStream {
  readonly streamId: string;
  readonly deviceId: string;
  readonly startedAt: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
  readonly state?: StreamState;
  readonly detectionEnabled?: boolean;
  readonly detectionState?: DetectionState;
}

/**
 * Mirrors the Java `StreamState` enum (docs/plans/done/STREAM-STATE-PLAN.md §2.3) — whether a running
 * stream's **video** is actually flowing, measured server-side from frame arrivals rather than
 * inferred here from "the player has not errored yet".
 *
 * There is deliberately no `'STOPPED'`: this union describes streams that `GET /api/streams`
 * returned, and a stopped stream is not in that list at all — it is an `AssetUsage` row on a
 * different axis. A stream that vanishes from the list has ended; that is the signal, not a state.
 *
 * `'UNOBSERVED'` is the honest answer for a proxied source (docs/plans/done/MEDIA-SOT-PLAN.md D4): no
 * `VideoSourcePort` runs inside the JVM, so the backend counts zero frames forever and must say
 * "cannot judge" instead of reporting a fault that is not there. Absent (old server) degrades the
 * same way — see `stream-state-logic.ts`.
 */
export type StreamState = 'STARTING' | 'LIVE' | 'STALLED' | 'RECONNECTING' | 'UNOBSERVED';

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
 * semantics, an absent `detectionEnabled` defaults to `PipelineConfig.DEFAULT_DETECTION_ENABLED`
 * server-side — **`false`** as of docs/plans/done/CV-DEMAND-PLAN.md wave D1 (flipped from `true`: detection
 * is opt-in per stream now, not opt-out).
 *
 * **Wave W7 (docs/plans/active/CV-SETTINGS-PLAN.md §3.1, H2) deleted the browser-local draft this app
 * used to always send explicitly** (`SettingsStore#effective()` — that slice of `SettingsStore` no
 * longer exists at all). Every call site that starts a stream (`features/fly/cockpit-facade.ts#start`,
 * `features/live/live-facade.ts#start`, `features/devices/devices-facade.ts#start`) now sends **no
 * body at all**, so this request's own documented fallback governs instead: the server resolves the
 * new stream's config from the profile hierarchy (PLATFORM → ORGANIZATION → CATEGORY → ASSET, §3.1) —
 * `PipelineConfig.defaults()` merged with whatever profile is actually bound, not a fixed SPA-side
 * default any more. Both `labelFilter`/`detectionEnabled` are still PATCH-able live afterward — see
 * `UpdateStreamConfigRequest`.
 *
 * `labelDenyFilter` (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2, wave W5) mirrors `dto.StartStreamRequest
 * #labelDenyFilter` one for one: an explicit empty array is a real value meaning "deny nothing",
 * absent leaves the server default (also "deny nothing") alone. Enforced server-side in
 * `StreamPipeline`'s single drop site, *after* `labelFilter` — a label that fails the allowlist or
 * matches the deny list is dropped identically before all fan-out (screen, alerts, recording). See
 * `UpdateStreamConfigRequest#labelDenyFilter` below for the everyday one-click "hide this class" act
 * this field backs; `labelFilter` stays the rarer model-intent allowlist.
 */
export interface StartStreamRequest {
  readonly confidenceThreshold?: number;
  readonly inferenceFps?: number;
  readonly model?: string;
  readonly labelFilter?: readonly string[];
  readonly labelDenyFilter?: readonly string[];
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
 *
 * `labelDenyFilter` (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2, wave W5) mirrors `dto.
 * UpdateStreamConfigRequest#labelDenyFilter` — the one field added since the §2 contract froze,
 * present/absent following the exact same "only present fields change" partial-patch rule as
 * `labelFilter`; an explicit `[]` means "deny nothing" (a real value, not "leave unchanged"). Hot,
 * never re-arms: sent on the same debounced hot-knob PATCH as `labelFilter`
 * (`cv-control-panel-logic.ts#buildHotKnobPatch`). This is the field an operator's one-click "hide
 * this class" act (the strip, the panel's class-chip checklist) actually writes — `labelFilter`
 * itself is left alone by that flow, reserved for the rarer model-intent allowlist (preset fill,
 * seeding on a model switch).
 */
export interface UpdateStreamConfigRequest {
  readonly confidenceThreshold?: number;
  readonly inferenceFps?: number;
  readonly labelFilter?: readonly string[];
  readonly labelDenyFilter?: readonly string[];
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
 * `StreamConfigResponse#tracking` — mirrors `dto.StreamConfigResponse.StreamTrackingConfigResponse`
 * field-for-field (docs/plans/active/CV-SETTINGS-PLAN.md wave W7, H6: "read tracking settings back
 * from `GET .../config` instead of assuming from what was sent"). Wider than
 * {@link CvProfileTracking} — this also carries the three session-only knobs a profile does not own
 * (`redetectIouPercent`/`maxAgeFrames`/`minHits`) plus `reupdateMaxGapMillis`. `lock` is deliberately
 * absent (the Java record's own doc comment): a held target is confirmed from `GET .../tracks`'s own
 * `lockedTrackId` and nowhere else (docs/extracts/TRACKING-ORCHESTRATION.md §3.3).
 */
export interface StreamTrackingConfigResponse {
  readonly mode: TrackingMode;
  readonly engineId: string;
  readonly verifyEveryMillis: number;
  readonly followFps: number;
  readonly redetectIouPercent: number;
  readonly maxAgeFrames: number;
  readonly minHits: number;
  readonly capabilityLevel: number;
  readonly reupdateMaxGapMillis: number;
}

/**
 * Mirrors `dto.StreamConfigResponse`, the `200` body of `GET /api/streams/{streamId}/config`
 * (docs/plans/active/CV-SETTINGS-PLAN.md wave W7) — "the read half of a knob that was write-only
 * over HTTP" (the Java record's own doc comment). Field names mirror
 * {@link UpdateStreamConfigRequest} one-for-one, but **every field here is always present** — this
 * is the effective configuration, not a patch, so there is no "absent means unchanged" to represent.
 * `404` for an unknown/not-running stream — the Java controller's own doc comment: "a plausible-
 * looking default configuration for a stream that does not exist" is exactly the fabrication this
 * app's "degrade honestly" rule forbids, so a `404` here means the caller has nothing to show, not a
 * reason to substitute a guess.
 */
export interface StreamConfigResponse {
  readonly model: string;
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
  readonly labelFilter: readonly string[];
  readonly labelDenyFilter: readonly string[];
  readonly detectionEnabled: boolean;
  readonly tracking: StreamTrackingConfigResponse;
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
  /**
   * Widened roster fields (docs/plans/active/CV-SETTINGS-PLAN.md §5.2, `/vision/profiles`'s model
   * picker) — every field below is **optional**, not because the wire contract permits omitting
   * them, but so this interface stays backward-compatible with every existing object-literal
   * fixture typed as `CvModel` (`cv-control-panel-logic.spec.ts#model()` in particular, which is
   * out of this wave's file scope and must keep compiling with only the original 5 fields set).
   * Absent means "not reported by this roster entry", never a fabricated default — read `undefined`
   * the same way the rest of this app reads a failed enrichment: render "—", never a guessed value.
   */
  readonly version?: string;
  readonly taskType?: 'DETECT' | 'SEGMENT' | 'POSE' | 'CLASSIFY';
  readonly runtime?: 'PYTORCH' | 'ONNX' | 'OPENVINO' | 'TENSORRT';
  readonly classes?: readonly string[];
  /** Registry lifecycle state — `'LIVE'` is the promoted, servable version; `'CANDIDATE'`/`'DRAFT'`/
   * `'RETIRED'` are registry-only states this picker still lists for context but should not
   * default-select. **Corrected wave W8** (docs/plans/active/CV-SETTINGS-PLAN.md §6 row W8) from
   * this file's own original `'LIVE' | 'CANDIDATE' | 'ARCHIVED'` guess, written before the backend
   * landed — `dto.CvModelResponse#status` (station/vision-api) serializes the domain `ModelStatus`
   * name verbatim, which is `DRAFT`/`CANDIDATE`/`LIVE`/`RETIRED`; `'ARCHIVED'` is never sent. */
  readonly status?: 'DRAFT' | 'CANDIDATE' | 'LIVE' | 'RETIRED';
  /** Whether the model file this entry names actually exists on the worker filesystem right now —
   * `'MISSING'` is CV-SETTINGS-PLAN.md §3.5 rule 4's "Missing on worker" case: the profile still
   * references it, but the picker must say so rather than silently falling back to something else. */
  readonly availability?: 'PRESENT' | 'MISSING';
  readonly metrics?: CvModelMetrics;
  readonly provenance?: CvModelProvenance;
  /** Where this roster entry came from — `'config'` is the pre-registry static list (today's only
   * source, docs/plans/active/CV-SETTINGS-PLAN.md §8 Q5: `GET /api/cv/models` never errors, it falls
   * back to `source:"config"` entries rather than surfacing a transport failure); `'registry'` is a
   * trained-and-promoted model row. Absent reads the same as `'config'`. */
  readonly source?: 'config' | 'registry';
}

/** `CvModel#metrics` — evaluation numbers for a trained roster entry; absent for a static config
 * entry with no training run behind it. `kind` distinguishes a training-time metric (measured on
 * held-out validation split during the run) from a future held-out evaluation metric
 * (docs/plans/active/CV-SETTINGS-PLAN.md §7 non-goal — not computed yet, but the field is already
 * shaped to carry one without another wire change). */
export interface CvModelMetrics {
  readonly map50?: number;
  readonly kind?: 'TRAINING' | 'HELDOUT';
}

/** `CvModel#provenance` — training lineage for a `source:'registry'` entry; every field is `null`
 * (not merely absent) for a `source:'config'` entry that was never trained, per §5.2's own example. */
export interface CvModelProvenance {
  readonly datasetId: string | null;
  readonly trainingRunId: string | null;
  readonly baseModel: string | null;
  readonly epochs: number | null;
  readonly trainedAt: string | null;
}

/** Mirrors `GET /api/cv/models`'s `200` body — `yolo26n.pt` (the fast closed-set default) listed
 * first, per docs/plans/done/CV-CONTROL-PLAN.md §4. Never errors server-side; `FleetStore.models` degrades to
 * an empty list on any transport failure instead (silent, background-enrichment read — see that
 * class's own doc comment). */
export interface CvModelsResponse {
  readonly models: readonly CvModel[];
}

// --- CV profiles (docs/plans/active/CV-SETTINGS-PLAN.md §5's frozen wire contract, wave W6) ----------------
// A profile bundles the whole per-stream CV config (model, thresholds, tracking, the event rule) into
// one named, reusable thing that can be bound at ORGANIZATION/CATEGORY/ASSET scope and resolves once
// at stream start (§3.1: PLATFORM → ORGANIZATION → CATEGORY → ASSET → SESSION, most specific wins, a
// session PATCH never writes back upward). **The backend for this wire contract had not shipped when
// this wave landed** (W2/W3/W4 land concurrently in `contexts/vision-perception`/`vision-learning` and
// `storage/persistence` — see docs/plans/active/CV-SETTINGS-CONTEXT.md's ledger) — `/vision/profiles`
// degrades every read to a visible notice on transport failure, never a fabricated row (§3.5 rule 2).

/** Mirrors `domain.model.BindingScope` — where a `CvProfileBinding` attaches. `ORGANIZATION` is the
 * group-wide default; `CATEGORY` binds every asset of one `CategoryId` slug; `ASSET` overrides both
 * for exactly one asset. Resolution picks the most specific bound profile, per §3.1. */
export type BindingScope = 'ORGANIZATION' | 'CATEGORY' | 'ASSET';

/** `CvProfile#tracking` — deliberately a narrower shape than `TrackingConfigRequest` (that request
 * also carries session-only knobs — `lock`, `redetectIouPercent`, `maxAgeFrames`, `minHits`,
 * `reupdateMaxGapMillis` — that a profile does not own; §5.1 lists exactly these five fields). */
export interface CvProfileTracking {
  readonly mode: TrackingMode;
  /** Empty string means "use the deployment default engine" — never a magic sentinel other than "". */
  readonly engineId: string;
  readonly capabilityLevel: number;
  readonly verifyEveryMillis: number;
  readonly followFps: number;
}

/**
 * `CvProfile#eventRule` — occupancy-style open/close rule for this profile's stream. **Start-time
 * only**: §5.1 marks this whole object absent from the PATCH-shaped update request — a running
 * stream keeps whatever event rule it started with, exactly like every other profile field (§3.1's
 * "resolved once at stream start" rule), so the profile editor must say so next to this section
 * rather than let it look like a live-editable control.
 */
export interface CvProfileEventRule {
  readonly labels: readonly string[];
  readonly confidenceThreshold: number;
  readonly consecutiveToOpen: number;
  readonly absenceToCloseSeconds: number;
}

/**
 * Mirrors `domain.model.CvProfile` (§5.1's frozen JSON). `groupId` is **optional**, not because the
 * wire can omit it arbitrarily, but because the Java domain record validates it bidirectionally
 * (docs/plans/active/CV-SETTINGS-CONTEXT.md "W1 → W2/W3 handoff"): `builtIn === true` requires
 * `groupId` absent/null, `builtIn === false` requires it present — a built-in profile has no owning
 * org, a forked/custom one always does. `eventRule` is present on every read but never sent back on
 * an update (see `CvProfileRequest`).
 */
export interface CvProfile {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly builtIn: boolean;
  readonly groupId?: string;
  readonly model: string;
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
  readonly labelFilter: readonly string[];
  readonly labelDenyFilter: readonly string[];
  readonly detectionEnabled: boolean;
  readonly tracking: CvProfileTracking;
  readonly eventRule: CvProfileEventRule;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** Mirrors `GET /api/cv/profiles`'s `200` body — the wrapped-list convention every list endpoint in
 * this API follows (`CvModelsResponse`, `CvTrackersResponse`, …), never a bare array. */
export interface CvProfilesResponse {
  readonly profiles: readonly CvProfile[];
}

/**
 * Body for `POST /api/cv/profiles` (create) and `PUT /api/cv/profiles/{id}` (update) — one shared
 * request shape for both verbs, this codebase's own precedent (`GeofenceZoneRequest`). Deliberately
 * excludes `id`/`builtIn`/`createdAt`/`updatedAt` (server-assigned) and `eventRule` (§5.1:
 * start-time only, never accepted on an update — a profile's event rule is fixed at creation and
 * only changes by forking a new profile). `groupId` is never sent — POST always creates a
 * non-built-in profile for the caller's own org, and PUT never moves a profile between orgs.
 */
export interface CvProfileRequest {
  readonly name: string;
  readonly description: string;
  readonly model: string;
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
  readonly labelFilter: readonly string[];
  readonly labelDenyFilter: readonly string[];
  readonly detectionEnabled: boolean;
  readonly tracking: CvProfileTracking;
}

/** Mirrors `domain.model.CvProfileBinding`. `scopeId` is a `GroupId`/`CategoryId` slug/`AssetId`
 * depending on `scopeKind` — an opaque string from this client's point of view, resolved by picking
 * it from the matching existing list (groups/categories/assets) rather than typed free-form. */
export interface CvProfileBinding {
  readonly scopeKind: BindingScope;
  readonly scopeId: string;
  readonly profileId: string;
  readonly createdAt: string;
}

/** Body for `PUT /api/cv/bindings` (set/replace) and `DELETE /api/cv/bindings` (clear) — §5.2 has no
 * `GET /api/cv/bindings` to list raw rows (only the write verbs), so `/vision/profiles` derives its
 * "bound to" summaries from `GET /api/cv/coverage` instead (`vision-profiles-logic.ts`). `profileId`
 * is omitted on a `DELETE` (clearing a scope doesn't name which profile it was bound to). */
export interface CvProfileBindingRequest {
  readonly scopeKind: BindingScope;
  readonly scopeId: string;
  readonly profileId?: string;
}

/** Mirrors `GET /api/cv/profiles/effective?assetId=…`'s `200` body — the profile that would actually
 * apply to this asset's next stream start, plus which binding produced it (or `'PLATFORM'` when
 * nothing at all is bound, §3.1's "behavior-preserving with zero bindings" default). */
export interface EffectiveCvProfile {
  readonly assetId: string;
  readonly profile: CvProfile;
  readonly source: BindingScope | 'PLATFORM';
}

/** One row of `GET /api/cv/coverage`'s `200` body — a per-asset resolved-profile summary
 * (§4's UI sketch: "asset · category · profile · source · detection · model · filters"). `assetId`
 * is always present; `categoryId`/`categoryName` describe the asset's own category, not necessarily
 * where the resolved binding came from (that's `source`). */
export interface CvCoverageRow {
  readonly assetId: string;
  readonly assetName: string;
  readonly categoryId: string;
  readonly categoryName: string;
  readonly profileId: string;
  readonly profileName: string;
  readonly source: BindingScope | 'PLATFORM';
  readonly detectionEnabled: boolean;
  readonly model: string;
  readonly labelFilter: readonly string[];
  readonly labelDenyFilter: readonly string[];
}

/** Mirrors `GET /api/cv/coverage`'s `200` body. */
export interface CvCoverageResponse {
  readonly rows: readonly CvCoverageRow[];
}

/**
 * Mirrors `domain.model.TrainingRun` (§5.2) — a `vision-learning` training job's own **persisted**
 * record (`cv_training_runs`, survives a browser/server restart), distinct from the older, in-memory
 * `TrainingJobResponse` (`core/api/models.ts` below) that `TrainingJobController#job`/`#jobs` still
 * serve — that one is this app's live-poll view of a run still in flight; this one is the
 * queryable history `VisionApi#getTrainingRuns`/`#getTrainingRun` reads (wave W8,
 * `features/training-jobs/run-history*`). Reuses `TrainingJobState` for `state` rather than a second
 * near-identical union (`RUNNING` / `SUCCEEDED` / `FAILED`) — same three-state lifecycle, one
 * vocabulary.
 */
export interface TrainingRun {
  readonly runId: string;
  readonly datasetId: string;
  readonly datasetName: string;
  readonly baseModel: string;
  readonly epochs: number;
  readonly state: TrainingJobState;
  readonly epoch: number;
  readonly totalEpochs: number;
  readonly loss: number | null;
  readonly map50: number | null;
  readonly outputModelId: string | null;
  readonly startedAt: string;
  readonly finishedAt: string | null;
  readonly startedBy: string;
}

/** Mirrors `GET /api/cv/training/runs`'s `200` body — wrapped-list convention, matching
 * `CvModelsResponse`/`DatasetsResponse`'s own precedent. Read by `VisionApi#getTrainingRuns`. */
export interface TrainingRunsResponse {
  readonly runs: readonly TrainingRun[];
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
 *
 * `reupdated` (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2, wave J4) is `TrackRef#reupdated()`
 * verbatim — this track's gap was just reconstructed by ORU (observation-centric re-update) on this
 * frame, from the two real observations bracketing it, rather than carried forward from drifted
 * extrapolation. Always present once `track` exists (a straight boolean, no absent-means-unknown
 * case) — `false` for the overwhelming majority of frames, where no gap needed reconstructing.
 */
export interface DetectionTrack {
  readonly id: number;
  readonly state: TrackState;
  readonly source: DetectionSource;
  readonly velocityX: number;
  readonly velocityY: number;
  readonly reupdated: boolean;
}

/**
 * Mirrors the nested `"capability"` object on `FrameTracking` (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md
 * §2, wave J4) — what capability level the host actually served for this frame, and why, whenever
 * that differs from what was asked. **The whole object is absent** when the server reported no level
 * at all — a pre-V3 cv-service — and that is the *only* "no level" case a reader here needs to
 * handle; there is no zeroed/placeholder shape to fall back to (invariant B3).
 *
 * `levelServed` is `1`-`5`, never `0` — the wire's own `0` sentinel ("nothing reported") is exactly
 * what makes this whole object absent instead of present-with-a-zero, mirroring every other
 * absent-not-zero rule in this file. `reason` is never absent/`null`: `''` means `levelServed`
 * matched what the running stream actually asked for at the time this frame was produced; any other
 * string names why it was capped (e.g. `"OpenVINO unavailable; degraded from requested L4 to L2"`).
 *
 * **Invariant B5 — a level is a ceiling, never a demand.** `levelServed` is what happened, and this
 * type carries no field for what was requested — a reader who wants "asked for L4, got L2" must
 * combine this with the operator's own local ceiling pick (`cv-control-panel.ts#capabilityLevel`,
 * which has no server readback — see that signal's own doc comment), and must never render that
 * local pick into this slot as though the server had reported it.
 * `cv-control-panel-logic.ts#isCapabilityDowngraded` reads `reason`, not a locally-recomputed
 * comparison, to decide whether to render this as a downgrade — the server is the one that actually
 * knows what request produced this frame, this app's own local ceiling pick might already be stale.
 */
export interface TrackingCapability {
  readonly levelServed: number;
  readonly reason: string;
}

/**
 * Mirrors the nested `"tracking"` object on `dto.DetectionResultResponse` (docs/plans/done/TRACKING-PLAN.md
 * §4.G) — one frame's duty-cycle facts, riding beside `detections` rather than flattened onto
 * `DetectionResult` (docs/extracts/TRACKING-ORCHESTRATION.md §5.2's own gap fix: `DetectionResult` had
 * nowhere to carry `detectorRan` before `TrackingTelemetry` existed). Absent entirely while tracking
 * is off for this stream, or on an old server — never a batch of zeroed-out fields.
 *
 * `detectionLagMillis`/`reupdateMillis`/`reupdatedTracks`/`capability` (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md
 * §2, wave J4) are the capability-ladder + ORU facts. `detectionLagMillis` is the measured
 * capture→association lag — `0` means "unknown", never a claim of zero lag (the domain's own
 * `Duration.ZERO` sentinel, `cv-control-panel-logic.ts#formatDetectionLag` renders it `—`);
 * `docs/conclusions/CV-RATE-BUDGET.md` §1 budgets this at **< 50 ms** for the *Hold* job — see
 * `DETECTION_LAG_BUDGET_MILLIS`. `reupdateMillis`/`reupdatedTracks` are this frame's ORU cost/count,
 * both `0` when ORU didn't run this frame. `capability` is absent on a pre-V3 server — see
 * {@link TrackingCapability}'s own doc comment for the full B5 reasoning.
 */
export interface FrameTracking {
  readonly detectorRan: boolean;
  readonly detectorReason: DetectorReason;
  readonly trackerMillis: number;
  readonly engineId: string;
  readonly lockedTrackId: number;
  readonly detectionLagMillis: number;
  readonly reupdateMillis: number;
  readonly reupdatedTracks: number;
  readonly capability?: TrackingCapability;
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
 *
 * `capabilityLevel`/`reupdateMaxGapMillis` (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2, wave J4) —
 * ordinary patch fields, same "absent = unchanged" rule as every other field here. `capabilityLevel`
 * is **a ceiling, not a demand** (plan decision E12): `0` = auto-probe (the default,
 * `cv-control-panel.ts#capabilityLevel`'s own initial value), `1`-`5` caps what this stream may use;
 * the host may still serve less than the ceiling if it cannot afford it — see `TrackingCapability`'s
 * own doc comment for how the *outcome* is read back, on a completely different response.
 * `reupdateMaxGapMillis` is typed here for wire completeness (the JSON contract this DTO mirrors
 * includes it) but has no control in this panel yet — `0`/absent keeps the server default, and
 * nothing in this app ever sends a non-zero value.
 */
export interface TrackingConfigRequest {
  readonly mode?: TrackingMode;
  readonly engineId?: string;
  readonly verifyEveryMillis?: number;
  readonly followFps?: number;
  readonly redetectIouPercent?: number;
  readonly maxAgeFrames?: number;
  readonly minHits?: number;
  readonly capabilityLevel?: number;
  readonly reupdateMaxGapMillis?: number;
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
 *
 * `reupdated` (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2, wave J4) — same field, same meaning
 * as {@link DetectionTrack}'s own `reupdated`, mirrored here because `TrackResponse` carries it flat
 * rather than nested (this interface *is* the track resource, not a per-box annotation of one).
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
  readonly reupdated: boolean;
  readonly firstSeen: string;
  readonly lastSeen: string;
}

/**
 * Mirrors `dto.DetectionState` (docs/plans/done/CV-DEMAND-PLAN.md §3.6) — which of the two
 * independent detection gates currently explains a stream's boxes-or-no-boxes state: `'OFF'` (the
 * operator's own choice), `'IDLE_NO_VIEWERS'` (enabled, but nobody has consumed this stream's
 * detections within the grace period — not a fault), `'RUNNING'` (both gates open; says nothing
 * about detector *health* — a stalled cv-service still reads `'RUNNING'`, see the Java enum's own
 * javadoc). Absent on `StreamTracksResponse` for an unknown/not-running stream or an old server —
 * every reader degrades to the honest "nothing measured yet" case, never a guess.
 */
export type DetectionState = 'OFF' | 'IDLE_NO_VIEWERS' | 'RUNNING';

/**
 * Mirrors the `"rate"` object of `GET /api/streams/{streamId}/tracks` (`dto.DetectionRateResponse`,
 * docs/plans/done/CV-RATE-CONTROL-PLAN.md §1) — why this stream is detecting at the rate it is.
 * `submittedFps` is the one figure `cv-control-panel.ts`'s honest status line actually reads (the
 * *measured* rate, replacing the fps slider's now-false "this is the rate" label per
 * docs/plans/done/CV-UX-RESEARCH.md §1.2) — every other field mirrors the DTO 1:1 for parity even
 * though only `submittedFps` has a reader today.
 */
export interface DetectionRate {
  readonly windowSeconds: number;
  readonly sourceFps: number;
  readonly targetFps: number;
  readonly demandFps: number;
  readonly submittedFps: number;
  readonly submitted: number;
  readonly droppedInFlight: number;
  readonly droppedOutage: number;
  readonly missedDeadlines: number;
  readonly dropRatio: number;
  readonly transport: string;
  readonly decodeMillisP50: number;
}

/**
 * Mirrors the `"latency"` object of `GET /api/streams/{streamId}/tracks` (`dto.PipelineLatencyResponse`,
 * docs/conclusions/CV-RATE-BUDGET.md §3) — what a detection costs in wall-clock time. Present
 * whatever the tracking mode is (independent of `stats`), absent before the first completed
 * detection. Mirrored for DTO parity alongside {@link DetectionRate}; no reader yet.
 */
export interface PipelineLatency {
  readonly windowSeconds: number;
  readonly samples: number;
  readonly roundTripMillisP50: number;
  readonly roundTripMillisP95: number;
  readonly roundTripMillisMax: number;
  readonly updateIntervalMillisP50: number;
  readonly effectiveFps: number;
  readonly worstBoxAgeMillis: number;
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
 *
 * `rate`/`latency`/`detectionState` (docs/plans/done/CV-UX-RESEARCH.md §7 wave U3, the backend
 * already served all three — this app simply hadn't read them) are each independently absent when
 * there's nothing honest to report yet (no completed detection, or an old server) — never a zeroed
 * placeholder.
 */
export interface StreamTracksResponse {
  readonly streamId: string;
  readonly lockedTrackId: number;
  readonly tracks: readonly StreamTrack[];
  readonly stats?: TrackStats;
  readonly latency?: PipelineLatency;
  readonly rate?: DetectionRate;
  readonly detectionState?: DetectionState;
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

/** Mirrors `dto.StartStreamResponse`. `whepUrl` follows the same absolute-origin rule as
 *  `ActiveStream#whepUrl`; `burnedIn` is gone for the same reason — see that field's own doc
 *  comment (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1, wave W3). */
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
  /**
   * docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/D16 — the same entries a probe/register call
   * wants under its own `options` (e.g. `{"sysid":"7"}`), ending the lossy `details["sysid"]`-only
   * read. Required, not optional: `DiscoveredDeviceResponse.from` (verified against source)
   * constructs at minimum `Map.of()`, never `null` — unlike `suggestedCategory`/`protocol`/`uri`
   * above, this field is never omitted from the wire.
   */
  readonly suggestedOptions: Record<string, string>;
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

// --- Discovery inbox (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P2, §11, wave Z2d) ---
// `GET /api/discovery/inbox` (manageOrg) — the persisted, deduplicated "found devices" list a
// standing MAVLink lobby / periodic ONVIF-mDNS-V4L2 sweep / mediamtx path scanner all feed
// (`DiscoveryInboxRunner`, vision-app). Poll-only v1: no SSE topic yet
// (`DiscoveryInboxController`'s own class doc) — a 30s client poll matches the backend's own sweep
// cadence, so nothing is lost between polls.

/** Mirrors `warehouse.domain.model.DiscoveryCandidate.CandidateStatus`. No `EXPIRED` — staleness
 *  is derived from `lastSeen` and shown as an age (`core/discovery/discovery-inbox-logic.ts#candidateAgeLabel`),
 *  never stored as a status of its own. */
export type DiscoveryCandidateStatus = 'NEW' | 'DISMISSED' | 'REGISTERED';

/**
 * Mirrors `dto.DiscoveryCandidateResponse` — one row in the discovery inbox. Unlike
 * `DiscoveredDevice` (the transient `POST /api/discovery/scan` shape), this carries the
 * suggested stream's full `options()` map, not the lossy `details["sysid"]`-only workaround.
 * `suggestedCategory`/`suggestedStreamProtocol`/`suggestedStreamUri`/`suggestedStreamOptions`/
 * `registeredAssetId` are each independently absent (never `null`) when the mechanism has nothing
 * to offer there; `details` is always present, at minimum `{}` (mirrors `DiscoveredDevice#details`).
 */
export interface DiscoveryCandidate {
  readonly id: string;
  readonly method: string;
  readonly name: string;
  readonly address: string;
  readonly suggestedCategory?: string;
  readonly suggestedStreamProtocol?: string;
  readonly suggestedStreamUri?: string;
  readonly suggestedStreamOptions?: Record<string, string>;
  readonly details: Record<string, string>;
  readonly firstSeen: string;
  readonly lastSeen: string;
  readonly status: DiscoveryCandidateStatus;
  readonly registeredAssetId?: string;
}

/**
 * Mirrors `dto.RegisterDiscoveryCandidateRequest` — the Add dialog's own minimal payload
 * (`core/discovery/discovery-inbox-logic.ts#buildRegisterCommand`). `ownership` is never a field
 * here — the backend always derives it from the caller, the same rule `CreateAssetRequest`
 * follows. `attributes`/`identity` are typed for wire-parity but never sent by the one-click Add
 * dialog (deliberately not the full onboarding wizard — see that dialog's own doc comment).
 */
export interface RegisterDiscoveryCandidateRequest {
  readonly displayName: string;
  readonly category: string;
  readonly attributes?: Record<string, string>;
  readonly identity?: AssetIdentity;
}

/**
 * Mirrors `dto.RegisterDiscoveryCandidateResponse` — a minimal pointer at the asset the candidate
 * became (not the full `AssetDetails` shape; see that DTO's own doc comment for why). The Add
 * dialog routes to `/assets/{assetId}` with this on success.
 */
export interface RegisterDiscoveryCandidateResponse {
  readonly assetId: string;
  readonly displayName: string;
  readonly category: string;
}

/**
 * Mirrors `dto.DiscoverySourceResponse` — one discovery mechanism's own reachability (A3,
 * docs/plans/active/ASSET-FLOWS-PLAN.md §2), reported alongside the candidate list so a client can
 * tell "this source is unreachable" apart from "reachable, nothing found" instead of both collapsing
 * into an identical empty list (`core/discovery/discovery-inbox-logic.ts#sourceUnreachableWarnings`).
 * `id` is the same mechanism key `DiscoveryCandidate#method` carries (`mavlink`/`onvif`/`mdns`/
 * `v4l2`/`mediamtx`) — `discoveryMethodLabel` gives both the same human name.
 */
export interface DiscoverySource {
  readonly id: string;
  readonly status: 'OK' | 'UNREACHABLE';
}

/**
 * Mirrors `dto.DiscoveryInboxResponse`, the body of `GET /api/discovery/inbox` since A3 — replaces
 * the bare `DiscoveryCandidate[]` this endpoint used to answer; `candidates` carries exactly that
 * same array under its own key now (docs/plans/active/ASSET-FLOWS-PLAN.md §2's frozen contract).
 */
export interface DiscoveryInboxResponse {
  readonly candidates: readonly DiscoveryCandidate[];
  readonly sources: readonly DiscoverySource[];
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
 * reports them). A probe that can't prove the connection *at all* is the documented 422 path
 * instead (an `HttpErrorResponse`, not a `{ok: false}` body).
 *
 * **Two legal 200 shapes** (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §3). A video probe
 * returns every frame field. A **telemetry-only** probe — `mavlink`, a link that carries no video
 * and never will — omits `widthPx`/`heightPx`/`frameJpegBase64` entirely, which is why all three are
 * typed optional here. The backend deliberately omits them rather than sending a `0×0` frame a
 * client could not tell apart from a real one, so `frameJpegBase64 === undefined` is the reliable
 * "there is no picture to show" test; do not reintroduce a truthiness check on `widthPx`, which a
 * genuine (if absurd) zero-width frame would also fail.
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
  readonly widthPx?: number;
  readonly heightPx?: number;
  readonly codec?: string;
  readonly fps?: number;
  readonly telemetryDetected: boolean;
  readonly frameJpegBase64?: string;
  readonly warnings?: readonly string[];
}

/**
 * Mirrors `dto.ErrorResponse`, produced by `ApiExceptionHandler` — this app's one real, shipped
 * error envelope, `{error, message}`, read by `core/api-error.ts#describeHttpError` and every
 * flag-disabled-409 detector (`core/readiness/readiness-logic.ts#isProbeDisabledError`,
 * `core/camera-geo/camera-geo-logic.ts#isFixedCameraGeoDisabledError`). Noted here because
 * `docs/plans/done/FIXED-CAMERA-GEO-PLAN.md` §5's own preamble illustrates its flag-off 409 as
 * `{"detail": "…"}` instead — a different key from this established shape.
 * `isFixedCameraGeoDisabledError` checks both defensively rather than picking one and guessing wrong.
 */
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
 *
 * `connected` (docs/plans/active/WAREHOUSE-UX-PLAN.md D4, wave W3) — whether an asset in this
 * category must wrap at least one device. Always present on the wire (a primitive boolean, no
 * `@JsonInclude(NON_NULL)` concern) — the Inventory page's Vehicles/Equipment tab split
 * (`core/fleet/inventory-logic.ts`) reads it directly, never a category-name heuristic.
 */
export interface Category {
  readonly slug: string;
  readonly name: string;
  readonly parent?: string;
  readonly attributeHints: readonly string[];
  readonly connected: boolean;
}

/**
 * Request body for `POST /api/categories` — mirrors `dto.CreateCategoryRequest`
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Categories table write half, wave W3/W4).
 */
export interface CreateCategoryRequest {
  readonly id: string;
  readonly name: string;
  readonly parentId?: string;
  readonly connected: boolean;
  readonly attributeHints?: readonly string[];
}

/**
 * Request body for `PUT /api/categories/{id}` — mirrors `dto.UpdateCategoryRequest`. A
 * whole-record replacement, not a partial patch (that DTO's own javadoc: `parentId` rules out the
 * usual "absent means unchanged" convention) — callers must resend every field, not just the one
 * they changed.
 */
export interface UpdateCategoryRequest {
  readonly name: string;
  readonly parentId?: string;
  readonly connected: boolean;
  readonly attributeHints?: readonly string[];
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

/** Mirrors `AssetUsage#phase` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O7) — which part of a
 * flight this usage is in, `PREFLIGHT` until the aircraft first arms. */
export type UsagePhase = 'PREFLIGHT' | 'IN_FLIGHT' | 'LINK_LOST' | 'POSTFLIGHT' | 'ABANDONED' | 'CLOSED';

/** Mirrors `AssetUsage#origin` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2) —
 * which verb opened this usage: a video stream starting, or the operator's own `engage` (`features/
 * fly/rc-monitor-logic.ts#resolveSessionAffordance`, docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * §3 P4, is the first reader). */
export type UsageOrigin = 'STREAM' | 'OPERATOR';

/**
 * Mirrors `dto.AssetUsageResponse`, embedded in `AssetDetails#recentUsages`. `endedAt` absent
 * means the usage is still open — this is how the telemetry store finds the usage to poll.
 * `phase`/`origin` are absent for a usage the domain never stamped one on (the DTO's own
 * `@JsonInclude(NON_NULL)`), not a fabricated default — treat a missing `origin` as "unknown", never
 * as `STREAM`. `pilotId` (docs/plans/active/ASSET-FLOWS-PLAN.md §2 D1p, BK4) is the operator this
 * usage is attributed to, a canonical UUID string, absent if genuinely unknown — id only, no
 * display-name resolution (matches `MaintenanceRecord#openedBy`'s own precedent); no reader in this
 * cycle resolves it to a name yet.
 */
export interface AssetUsage {
  readonly usageId: string;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly startPosition?: GeoPosition;
  readonly lastPosition?: GeoPosition;
  readonly sampleCount: number;
  readonly phase?: UsagePhase;
  readonly origin?: UsageOrigin;
  readonly pilotId?: string;
}

/**
 * Mirrors `dto.FirmwareResponse` — the asset's most recently observed firmware, joined from
 * vision-flight at the vision-api layer (docs/plans/active/WAREHOUSE-UX-PLAN.md D5: "firmware stays
 * flight-owned; the table joins it. Warehouse must not read `vehicle_profiles`."). Nested on
 * {@link AssetSummary}, absent entirely (not `{ name: undefined, version: undefined }`) when the
 * asset's devices were never probed at all. Not to be confused with `VehicleProfile#firmware`/
 * `firmwareVersion` (`core/api/models.ts`'s own flat-string probe-result pair, a different feature) —
 * this is the joined, display-ready fact `AssetSummaryResponse`/`FleetMaintenanceRecordResponse`
 * carry.
 *
 * @property name    `"ardupilot"` | `"generic"` | `"px4"`, or absent if the probe answered but
 *                    firmware was not identified.
 * @property version  the firmware version string, or absent if not identified.
 */
export interface Firmware {
  readonly name?: string;
  readonly version?: string;
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
  /**
   * Mirrors `dto.IdentityResponse` (docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W3 → W4/W6/W7 handoff",
   * D1–D8) — the wire always sends an object, individual fields omitted (not `null`) when unknown.
   * Optional here (not on the wire) only so the many pre-existing `AssetSummary` test fixtures
   * outside this wave's file scope (`core/fleet/**`, `features/assets|devices|fly/**`, …) keep
   * compiling without every one of them being touched to add these five fields — a real `VisionApi`
   * response always carries it, so a reader that needs it treats `undefined` as "not fetched yet",
   * never as a fabricated "no identity".
   */
  readonly identity?: AssetIdentity;
  /** Mirrors `dto.CustodyResponse` — see {@link identity}'s own doc comment for why this is optional in TS despite always being present on the wire. */
  readonly custody?: AssetCustody;
  /** The **effective** value (`InventoryStates#effective`) — `ISSUED`/`IN_FIELD` are derived server-side, never stored (WAREHOUSE-UX-PLAN.md §3.2 D2). See {@link identity}'s own doc comment for why this is optional in TS. */
  readonly inventoryState?: InventoryState;
  readonly createdAt?: string;
  readonly updatedAt?: string;
  /**
   * Mirrors `AssetSummaryResponse#firmware` (docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9
   * handoff") — absent when never probed, or when the caller has no join to offer (`GET
   * /api/assets/{id}/custody`/`/inventory`'s own responses always omit it, see
   * `AssetInventoryController#detailsResponse`'s own doc comment on the five-parameter ceiling; a
   * caller wanting a fresh value after a custody/inventory mutation re-fetches `GET /api/assets/{id}`).
   */
  readonly firmware?: Firmware;
  /**
   * Mirrors `AssetSummaryResponse#totalFlightSeconds` — cumulative flight seconds across every
   * usage; absent only when the caller has no join to offer (see {@link firmware}'s own doc
   * comment), never when the true value is a genuine zero (a never-flown asset reports `0`).
   */
  readonly totalFlightSeconds?: number;
}

/** Mirrors `warehouse.domain.model.InventoryState` (WAREHOUSE-UX-PLAN.md §3.2 D1/D2). */
export type InventoryState = 'IN_STOCK' | 'ISSUED' | 'IN_FIELD' | 'MAINTENANCE' | 'RETIRED';

/** Mirrors `dto.IdentityResponse` — every field absent (never `null`) when not yet recorded. */
export interface AssetIdentity {
  readonly serialNumber?: string;
  readonly make?: string;
  readonly model?: string;
  readonly registration?: string;
}

/** Mirrors `dto.CustodyResponse` — `custodianId`/`since` absent for an asset still in stock. */
export interface AssetCustody {
  readonly custodianId?: string;
  readonly location?: string;
  readonly since?: string;
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
 * `deviceService.register(...)` for every entry, unconditionally). `options`/`capabilities`/`origin`
 * are optional, `@JsonInclude(NON_NULL)`-style like every other request DTO here — omit rather than
 * send `undefined`/empty. `origin` defaults server-side to `LIVE`, same as `RegisterDeviceRequest`.
 */
export interface CreateAssetDeviceSpec {
  readonly name: string;
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
  readonly origin?: DeviceOrigin;
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
  /**
   * Mirrors `dto.IdentityRequest`, reused verbatim on the create path (docs/plans/active/WAREHOUSE-UX-PLAN.md
   * D1, wave W6's Identify step — serial/make/model/registration). Omitted rather than sent as `{}`;
   * the same shape as {@link AssetIdentity} since the backend's `IdentityRequest`/`IdentityResponse`
   * carry identical fields. **Not** `custody` — the onboarding wizard establishes custody later, in
   * its own Hand-over step (`VisionApi#setAssetCustody`), not at creation time, mirroring the
   * pre-wizard "Assign" step's own timing.
   */
  readonly identity?: AssetIdentity;
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
  /**
   * Mirrors `dto.UpdateAssetRequest#identity` — present replaces the asset's identity wholesale
   * (its own absent fields mean "unknown", not "unchanged"). Used only by the onboarding wizard's
   * legacy whole-vehicle Simulate path (`OnboardingStore#createViaSimulation`), which cannot send
   * `identity` on the initiating `POST /api/simulations` call and so folds it into this follow-up
   * `PATCH` alongside `displayName` instead.
   */
  readonly identity?: AssetIdentity;
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

// --- Maintenance / inventory (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.2/§4 W7, the "W3 → W4/W6/W7
// handoff" section of WAREHOUSE-UX-CONTEXT.md for the exact wire shapes below) ---------------------

/**
 * Mirrors `warehouse.domain.model.MaintenanceKind` — only `GROUNDING`/`INSPECTION_DUE` are a
 * readiness NO-GO blocker (`DefaultReadinessService#MAINTENANCE_BLOCKER_PREFIX`,
 * `MaintenanceKind#blocksFlight()`); `REPAIR`/`NOTE` are informational, never block `engage`.
 */
export type MaintenanceKind = 'GROUNDING' | 'INSPECTION_DUE' | 'REPAIR' | 'NOTE';

/**
 * Mirrors `dto.MaintenanceRecordResponse` — one element of `GET /api/assets/{id}/maintenance`'s
 * array, and the return value of the open/close endpoints. `closedAt`/`flightSecondsAt` are absent
 * (never `null`) while the record is open / when the asset's flight-hours weren't known at close.
 */
export interface MaintenanceRecord {
  readonly id: string;
  readonly assetId: string;
  readonly kind: MaintenanceKind;
  readonly openedAt: string;
  readonly openedBy: string;
  readonly summary: string;
  readonly closedAt?: string;
  readonly flightSecondsAt?: number;
}

/** Mirrors `dto.CreateMaintenanceRecordRequest` — `POST /api/assets/{id}/maintenance` (opens a record *without* also grounding the asset; use {@link InventoryActionRequest}'s `GROUND` action for that). */
export interface CreateMaintenanceRecordRequest {
  readonly kind: MaintenanceKind;
  readonly summary: string;
}

/** Mirrors `application.maintenance.MaintenanceListState` — the `state` query param `GET /api/maintenance` accepts, case-insensitive on the wire, always sent lowercase here. */
export type MaintenanceListState = 'open' | 'closed' | 'all';

/**
 * Mirrors `dto.FleetMaintenanceRecordResponse` — one element of `GET /api/maintenance`'s array
 * (docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9 handoff"), {@link MaintenanceRecord}'s
 * fleet-wide counterpart: carries the asset's name and category slug alongside each record so the
 * Maintenance page needs one call, not one `GET /api/assets/{id}/maintenance` per grounded asset.
 * `closedAt`/`flightSecondsAt` are absent (never `null`) while the record is open / when the
 * asset's flight-hours weren't known at open, same convention as {@link MaintenanceRecord}.
 */
export interface FleetMaintenanceRecord {
  readonly id: string;
  readonly assetId: string;
  readonly assetName: string;
  readonly categoryId: string;
  readonly kind: MaintenanceKind;
  readonly openedAt: string;
  readonly closedAt?: string;
  readonly openedBy: string;
  readonly summary: string;
  readonly flightSecondsAt?: number;
}

/** Mirrors the three actions `POST /api/assets/{id}/inventory` accepts. */
export type InventoryAction = 'GROUND' | 'RELEASE' | 'RETIRE';

/**
 * Mirrors `dto.InventoryActionRequest` — `kind`/`summary` are required only for `GROUND` (opens a
 * blocking-capable {@link MaintenanceRecord} in the same call); `RELEASE`/`RETIRE` take neither.
 * Response is the asset's `AssetDetails`.
 */
export interface InventoryActionRequest {
  readonly action: InventoryAction;
  readonly kind?: MaintenanceKind;
  readonly summary?: string;
}

/** Mirrors the two actions `POST /api/assets/{id}/custody` accepts (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4, wave W3/W4). */
export type CustodyAction = 'ISSUE' | 'RETURN';

/**
 * Mirrors `dto.CustodyActionRequest` — `ISSUE` hands an in-stock asset to a custodian (`custodianId`
 * required, `location` optional); `RETURN` brings an issued asset back to stock (both fields
 * ignored). Response is the asset's `AssetDetails`, same shape as {@link InventoryActionRequest}'s.
 */
export interface CustodyActionRequest {
  readonly action: CustodyAction;
  readonly custodianId?: string;
  readonly location?: string;
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
 * blank duration. `pilotId` mirrors `AssetUsage.pilotId`'s own doc comment (D1p, BK4) — same
 * absent-if-unknown, id-only contract.
 */
export interface UsageSummary {
  readonly usageId: string;
  readonly assetId: string;
  readonly assetName: string;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly durationSeconds?: number;
  readonly sampleCount: number;
  readonly pilotId?: string;
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
  /**
   * `total`'s subset by **effective** inventory state (docs/plans/active/WAREHOUSE-UX-PLAN.md D6,
   * wave W3) — `issued`/`inField` already resolved from custody/open-usage server-side, never the
   * raw stored value. Always present (no `@JsonInclude(NON_NULL)` concern, all `int`).
   */
  readonly inStock: number;
  readonly issued: number;
  readonly inField: number;
  readonly maintenance: number;
  readonly retired: number;
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
 * union on `type` so a `switch` narrows `payload` to the right shape per branch — the eight `type`
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
 *
 * **`geo` is the 8th, from docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4 (wave H6/H5)** — `geo:<assetId>`,
 * opt-in like `telemetry`/`detections` (not always-on), coalescing latest-wins with ring capacity 1
 * (the freshest correction is the only one that matters, CLAUDE.md rule 9); payload is
 * {@link CorrectionResponse} verbatim, byte-identical to the REST shape.
 */
export type LiveEnvelope =
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'fleet'; readonly payload: readonly AssetSummary[] }
  | { readonly seq: number; readonly assetId: string; readonly type: 'telemetry'; readonly payload: readonly TelemetrySample[] }
  | { readonly seq: number; readonly assetId: string; readonly type: 'detections'; readonly payload: DetectionResult }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'event'; readonly payload: LiveEvent }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'devices'; readonly payload: DevicesSnapshot }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'detection-events'; readonly payload: DetectionEvent }
  | { readonly seq: number; readonly assetId?: undefined; readonly type: 'map'; readonly payload: MapEventPayload }
  | { readonly seq: number; readonly assetId: string; readonly type: 'geo'; readonly payload: CorrectionResponse };

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
/**
 * The `track` entity on the map SSE topic (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5/D11).
 *
 * Wave G5 shipped this uppercase because §5 froze it that way, and flagged the mismatch rather than
 * normalizing it silently. §5 was wrong: the shipped `MapEventPayload.java` lowercases every
 * `MapEvent.EntityType.name()`, and this SPA's map stores already fold `"mark"`/`"created"` off the
 * same topic — uppercase would have made `track` the only shouting entity on a channel with live
 * consumers. The plan was amended 2026-08-19 and this type follows it.
 */
export type MapTrackEntity = 'track';

/**
 * The three actions a `track` event carries. `deleted` is never used for tracks (§5): `cleared` is
 * the single terminal action, which is the whole reason this narrower alias exists rather than
 * reusing {@link MapEventPayload}'s full action union.
 */
export type MapTrackAction = 'created' | 'updated' | 'cleared';

/**
 * The `track` field of a live `TRACK` {@link MapEventPayload} (§5) — `ProjectedTrackResponse`
 * **without `trail`** for `CREATED`/`UPDATED` (a reload's `GET /api/map/tracks` is the trail's own
 * source — see {@link ProjectedTrackResponse}'s own doc comment). For `CLEARED` the wire carries only
 * `assetId`/`trackId` (§5's own second example); every other field is genuinely absent then, not
 * merely unresolved — which is why every field but those two is optional here rather than this being
 * two separate wire types: one interface, two honestly-partial shapes depending on `action`.
 */
export interface ProjectedTrackLive {
  readonly assetId: string;
  readonly trackId: number;
  readonly label?: string;
  readonly layerId?: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly rangeMeters?: number;
  readonly errorRadiusMeters?: number;
  readonly updatedAt?: string;
}

export interface MapEventPayload {
  readonly entity: 'mark' | 'drawing' | 'layer' | MapTrackEntity;
  readonly action: 'created' | 'updated' | 'cleared' | 'deleted' | MapTrackAction;
  readonly layerId: string;
  readonly mark?: MapMark;
  readonly drawing?: MapDrawingResponse;
  readonly layer?: MapLayer;
  /** Present only when `entity === 'track'` — see {@link ProjectedTrackLive}'s own doc comment for the CLEARED-is-partial shape. */
  readonly track?: ProjectedTrackLive;
}

// --- Fixed-camera geolocation (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5's frozen wire contract, wave G5) ---
// The backend does not exist yet as of this wave (G4 is blocked on G2+G3) — every shape below is
// typed directly off the plan's §5 prose, not off a running server; property names/status
// codes/enum spellings are what §5 calls frozen. One more discrepancy flagged here (see
// `ApiErrorBody`'s own doc comment for the first, `MapEventPayload`'s `TRACK` entity above): §5's
// preamble illustrates the flag-off 409 body as `{"detail": "…"}`, but this app's one real,
// shipped error envelope (`ApiErrorBody`, this file, `{error, message}`) is what every other
// flag-gated 409 in this codebase actually uses (`core/readiness/readiness-logic.ts#isProbeDisabledError`'s
// own precedent). `core/camera-geo/camera-geo-logic.ts#isFixedCameraGeoDisabledError` checks both
// `message` and `detail` defensively rather than picking one and guessing wrong.

export type CameraPoseSource = 'MANUAL' | 'CALIBRATED';

/**
 * Mirrors `CameraPoseResponse` (§5) — the 200 body of `GET`/`PUT /api/assets/{assetId}/camera-pose`.
 * `targetLayerId`/`rmsErrorPixels` are absent (not `null`) when unset, this file's own
 * `@JsonInclude(NON_NULL)` convention (see this file's top doc comment).
 */
export interface CameraPoseResponse {
  readonly assetId: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly aglMeters: number;
  readonly yawDegrees: number;
  readonly pitchDegrees: number;
  readonly hfovDegrees: number;
  readonly targetLayerId?: string;
  readonly source: CameraPoseSource;
  readonly rmsErrorPixels?: number;
  readonly updatedAt: string;
}

/**
 * The body of `PUT /api/assets/{assetId}/camera-pose` (§5) — manual entry (`source: 'MANUAL'`), or
 * confirming a solve (send the calibration result's own `pose` fields back verbatim with
 * `source: 'CALIBRATED'`). `targetLayerId`/`rmsErrorPixels` are explicit `null`, not omitted — this
 * is a PUT (whole-resource replace), unlike {@link PatchMarkRequest}'s partial-patch convention.
 */
export interface CameraPoseRequest {
  readonly latitude: number;
  readonly longitude: number;
  readonly aglMeters: number;
  readonly yawDegrees: number;
  readonly pitchDegrees: number;
  readonly hfovDegrees: number;
  readonly targetLayerId: string | null;
  readonly source: CameraPoseSource;
  readonly rmsErrorPixels: number | null;
}

/**
 * One frame-click ↔ map-click correspondence in a calibration attempt (§5, D5) — `u`/`v` are
 * normalized pixel coordinates in `[0,1]`, top-left origin, `v` measured from the top of the frame
 * (D3's own "bbox bottom-centre" convention for a live track's pixel, mirrored here for a
 * calibration landmark click).
 */
export interface CalibrationPointRequest {
  readonly u: number;
  readonly v: number;
  readonly latitude: number;
  readonly longitude: number;
}

/** The body of `POST /api/assets/{assetId}/camera-pose/calibration` (§5) — solves but **never persists**; 2–8 `points`, `400` outside that range or for a `u`/`v` outside `[0,1]`. */
export interface CalibrateCameraPoseRequest {
  readonly latitude: number;
  readonly longitude: number;
  readonly aglMeters: number;
  readonly imageWidth: number;
  readonly imageHeight: number;
  readonly points: readonly CalibrationPointRequest[];
}

/** `'GOOD'` for a ≥3-point solve within tolerance; `'UNDETERMINED'` for an exact 2-point fit (D5 — the residual is meaningless with only two points; the UI says "add a third point to verify"). `null` on the whole result exactly when `solved: false`. */
export type CalibrationQuality = 'GOOD' | 'UNDETERMINED';

/**
 * Mirrors the 200 body of `POST .../camera-pose/calibration` (§5, D5) — a discriminated union on
 * `solved`, matching the two literal shapes §5 documents. `solved: true` carries `pose`/`quality`
 * and a `null` `reason`. `solved: false` carries a `null` `pose`/`quality` and a **verbatim** `reason`
 * string — D5's own frozen examples are `"residual 41.0px exceeds 25.0px"`,
 * `"landmarks span only 6° of bearing"`, `"landmark 2 is 1.4m from the camera"` — that this app must
 * render exactly, never paraphrased or re-worded: this wave's own brief requires "refusal reasons
 * shown verbatim, and offers no save" (`features/camera-geo/camera-calibration-wizard.ts`).
 */
export type CalibrationResult =
  | {
      readonly solved: true;
      readonly pose: CameraPoseResponse;
      readonly rmsErrorPixels: number;
      readonly quality: CalibrationQuality;
      readonly reason: null;
    }
  | {
      readonly solved: false;
      readonly pose: null;
      readonly rmsErrorPixels: number;
      readonly quality: null;
      readonly reason: string;
    };

/** One trail point of a {@link ProjectedTrackResponse} (§5) — oldest→newest, already decimated server-side (D7). */
export interface ProjectedTrackPoint {
  readonly latitude: number;
  readonly longitude: number;
  readonly at: string;
}

/**
 * Mirrors `ProjectedTrackResponse` (§5) — one row of `GET /api/map/tracks`. `errorRadiusMeters` is
 * drawn as a circle under the track dot **always**, never conditionally — D6's own "a 200m-error
 * estimate must never render as a 5m-accurate-looking dot" — see
 * `shared/map/tactical-map/tactical-map.ts`'s `applyTracks`.
 */
export interface ProjectedTrackResponse {
  readonly assetId: string;
  readonly trackId: number;
  readonly label: string;
  readonly layerId: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly rangeMeters: number;
  readonly errorRadiusMeters: number;
  readonly updatedAt: string;
  readonly trail: readonly ProjectedTrackPoint[];
}

/** The body of `GET /api/map/tracks` (§5) — every track on a layer the viewer `canView`s (D10). */
export interface MapTracksResponse {
  readonly tracks: readonly ProjectedTrackResponse[];
}

// --- Visual geolocation v2 (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3/§3.4's frozen wire contract, wave H6) ---
// Built against the plan text, not a running server — H5 (persistence/REST/SSE) lands concurrently.
// D9's flag-off envelope is the app's real, shipped `ApiErrorBody` shape (`{error, message}`), no
// ambiguity to flag here (contrast Fixed-camera-geo's `detail`-vs-`message` discrepancy above).
// `RegionProgressResponse.phase`/`state` are fully enumerated in §3.1's proto comment
// (`ReferenceIndexProgress.phase`, `JobState`), so both are closed unions, not loose `string`s.
// `RegionIngestRequest`'s own field names aren't spelled out as a named DTO in §3.3 beyond "an
// ingest form (bounds + zoom)" (§3.8) — mirrors `RegionResponse`'s own bounds/zoom fields exactly,
// the same request-narrows-response idiom this file already uses for `GeofenceZoneRequest` against
// `GeofenceZone`. Every optional `Double`/`String` field follows this file's own `@JsonInclude(NON_NULL)`
// convention (absent via `?:`, never `null`) except `CorrectionResponse`'s `assetId`/`usageId`/
// `frameAt`/`computedAt`/`status`/`source`/`divergent` — §3.3's own named exceptions to NON_NULL,
// plus the two non-nullable `Instant`s the domain `TrackCorrection` record never omits.

/**
 * Mirrors `GeoController`'s D9 flag-off refusal (§3, D9) — `vision.geo.visual.enabled=false` answers
 * this on all six `/api/geo/**` routes. Exported so `core/geo/geo-logic.ts#isVisualGeoDisabledError`
 * can match against it without duplicating the literal.
 */
export const VISUAL_GEO_DISABLED_MESSAGE = 'visual geolocation is disabled (vision.geo.visual.enabled)';

/** Mirrors `RegionStatus` (§3.3) — `NEVER_ACCEPT` is a first-class, honestly-displayed state (§3.8), never hidden like a failure. */
export type RegionStatus = 'BUILDING' | 'READY' | 'NEVER_ACCEPT' | 'FAILED';

/**
 * Mirrors `RegionResponse` (§3.3) — one row of `GET /api/geo/regions`, and the `202` body of a
 * successful `POST` (`status: 'BUILDING'`). D10: cv-service is the single source of truth for what
 * regions exist — this DTO is proxied from cv-service's own `ListRegions`, not a Postgres table.
 */
export interface RegionResponse {
  readonly regionId: string;
  readonly name: string;
  readonly zoom: number;
  readonly north: number;
  readonly south: number;
  readonly east: number;
  readonly west: number;
  readonly status: RegionStatus;
  readonly tileCount?: number;
  readonly neverAcceptCells?: number;
  readonly encoderId?: string;
  readonly acceptSimilarity?: number;
  readonly acceptMargin?: number;
  readonly holdoutRecallAt1?: number;
  readonly holdoutMedianErrorMeters?: number;
  readonly builtAt?: string;
}

/** The body of `GET /api/geo/regions` (§3.3). */
export interface RegionsResponse {
  readonly regions: readonly RegionResponse[];
}

/**
 * The body of `POST /api/geo/regions` (§3.3) — the ingest form's own bounds + zoom, `403` unless
 * `canAdminister` (a region ingest hits an external imagery provider), `400` for invalid bounds,
 * zoom outside `[15,19]`, or a tile count over `vision.geo.visual.tiles.max-tiles`.
 */
export interface RegionIngestRequest {
  readonly name: string;
  readonly zoom: number;
  readonly north: number;
  readonly south: number;
  readonly east: number;
  readonly west: number;
}

/** One in-flight ingest job's phase (§3.1 proto comment, `ReferenceIndexProgress.phase` — frozen, closed set). */
export type RegionIngestPhase = 'receiving' | 'extracting' | 'encoding' | 'indexing' | 'calibrating' | 'done';

/** `JobState`, reused (§3.1) — the job's own terminal/non-terminal status, independent of `phase`. */
export type RegionJobState = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/**
 * The index-quality stats attached to a progress event's terminal `SUCCEEDED` (§3.3's own literal
 * example shows `"stats": null` for a `RUNNING` phase — this file infers the terminal shape from
 * `RegionResponse`'s own stats fields, the same computed values a finished region reports there,
 * rather than the proto's fuller `ReferenceIndexStats` — `descriptorCount`/`descriptorDim`/
 * `indexBytes` are cv-service-internal and no `RegionResponse` example ever surfaces them).
 */
export interface RegionIndexStats {
  readonly tileCount?: number;
  readonly neverAcceptCells?: number;
  readonly encoderId?: string;
  readonly acceptSimilarity?: number;
  readonly acceptMargin?: number;
  readonly holdoutRecallAt1?: number;
  readonly holdoutMedianErrorMeters?: number;
}

/** The body of `GET /api/geo/regions/{regionId}/progress` (§3.3) — `404` once no in-flight job and no `READY` region exists for `regionId`. */
export interface RegionProgressResponse {
  readonly regionId: string;
  readonly phase: RegionIngestPhase;
  readonly done: number;
  readonly total: number;
  readonly state: RegionJobState;
  readonly message: string;
  readonly stats?: RegionIndexStats | null;
}

/** Mirrors `CorrectionStatus` (§3.5) — wire spelling is the Java enum name verbatim. */
export type CorrectionStatus = 'CONFIRMED' | 'PROBABLE' | 'NO_FIX';

/** Mirrors `CorrectionSource` (§3.5) — one value today, by design. */
export type CorrectionSource = 'VISUAL_HEAVY';

/**
 * Mirrors `CorrectionResponse` (§3.3) — one visual-geolocation fix against an asset's raw telemetry
 * track, served by `GET /api/geo/corrections/live` (latest per asset), `GET /api/geo/corrections?usageId=`
 * (a finished/open usage's full history), and the `geo:<assetId>` SSE topic payload (§3.4, byte-identical).
 *
 * `latitude`/`longitude` are absent (not `null`) exactly when `status === 'NO_FIX'` (domain: `position`
 * null iff `NO_FIX`); `rawLatitude`/`rawLongitude` absent whenever the aircraft's own telemetry had
 * no fix at `frameAt` (a telemetry gap — §4.5: "absence of evidence is not evidence", `separationMeters`
 * absent in the same case). `refusal` carries the first refused gate's name, verbatim, only on a
 * `NO_FIX` row (§3.8 cockpit popover: shown exactly as received, never paraphrased — the D5 rule).
 *
 * `cellCalibrated`/`sequenceConverged` are the two booleans the backend gate reads to choose PROBABLE
 * over CONFIRMED; H8 gave them a column and a wire field so a correction can finally say why it was
 * only PROBABLE (§9.11 defect 4). Optional here like every other evidence field, so a row served by an
 * older backend simply reads `'—'` rather than a confident `'no'`.
 */
export interface CorrectionResponse {
  readonly assetId: string;
  readonly usageId: string;
  readonly frameAt: string;
  readonly computedAt: string;
  readonly status: CorrectionStatus;
  readonly source: CorrectionSource;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly yawDegrees?: number;
  readonly radiusMeters?: number;
  readonly impliedAglMeters?: number;
  readonly rawLatitude?: number;
  readonly rawLongitude?: number;
  readonly separationMeters?: number;
  readonly sigmaMeters?: number;
  readonly divergent: boolean;
  readonly divergentSince?: string;
  readonly regionId?: string;
  readonly tileId?: string;
  readonly matchCount?: number;
  readonly inlierCount?: number;
  readonly inlierRatio?: number;
  readonly rerankMargin?: number;
  readonly reprojectionRmsPixels?: number;
  readonly rectified?: boolean;
  /** Whether the winning cell has a real, self-calibrated accept threshold rather than a never-accept verdict. */
  readonly cellCalibrated?: boolean;
  /** Whether the sequence filter reported convergence under its own false-convergence gate. */
  readonly sequenceConverged?: boolean;
  readonly sequenceSpreadMeters?: number;
  readonly sequenceUpdates?: number;
  readonly refusal?: string;
}

/** The body of both `GET /api/geo/corrections/live` and `GET /api/geo/corrections?usageId=` (§3.3). */
export interface CorrectionsResponse {
  readonly corrections: readonly CorrectionResponse[];
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
  readonly vehicleKind: VehicleKind;
}

/**
 * What kind of machine is on the other end — decoded live from the vehicle's own heartbeat, never
 * stored or guessed (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P1/P9).
 *
 * `'UNKNOWN'` is an honest answer, not an error state: the vehicle has not identified itself as
 * anything the platform recognizes. Render it as such — there is no safe default to substitute,
 * because a throttle resting at its minimum is idle on a copter and *full reverse* on a rover.
 */
export type VehicleKind = 'COPTER' | 'PLANE' | 'ROVER' | 'UNKNOWN';

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

// --- Drone onboarding: vehicle profile & fleet readiness (docs/plans/active/DRONE-ONBOARDING-PLAN.md
// §8.1's frozen wire contract, O6) — the PROBE/CONFIGURE stages (`OnboardingController`) and the
// read-only NEGOTIATE stage (`ReadinessController`). Every type below is verified 1:1 against O5's
// actual DTOs (`station/vision-api/.../dto/VehicleProfileResponse.java` etc.) and the domain enums
// they wrap, not assumed from the plan doc alone — field names/order/nullability match exactly.
//
// **`vision.onboarding.probe.enabled` defaults `false` (D17, the guardrail).** With it off,
// `POST /api/onboarding/probe` and `POST /api/assets/{id}/probe`/`/remediate` all 409 with the
// exact, stable body `{"error":"...", "message":"vehicle probing is disabled
// (vision.onboarding.probe.enabled)"}` (verified against `NoopVehicleConfigPort.DISABLED_MESSAGE`
// and its wiring test) — `core/readiness/readiness-logic.ts#isProbeDisabledError` matches this exact
// string so the UI can render "not enabled here" as a first-class state rather than a generic
// failure toast, the same disabled-signal pattern `core/training/training-store.ts` established.
// `GET /api/assets/{assetId}/readiness` and `GET /api/fleet/readiness` are **never** gated by this
// flag (verified against `ReadinessController` — no flag check anywhere in it) — a never-probed
// asset still gets a full report, every feature `UNKNOWN`, `profileObservedAt: null`. This is why
// the fleet board and the readiness screen work identically whether or not probing is enabled; only
// the wizard's Verify step and any "probe now"/"remediate" action are flag-gated.

/** One `VehicleProfile#messages()` entry — a MAVLink message observed during a probe's passive inventory window. */
export interface MessageObservation {
  readonly messageId: number;
  readonly name: string;
  readonly hz: number;
  readonly count: number;
}

/** One `VehicleProfile#parameters()` entry — a parameter value read back during a probe. */
export interface ParameterReading {
  readonly name: string;
  readonly value: number;
  readonly type: string;
}

/**
 * Mirrors `VehicleProfileResponse` — the body of `POST /api/onboarding/probe`, `GET/POST
 * /api/assets/{assetId}/profile|probe`. Verified against source: the Java record carries no
 * `@JsonInclude(NON_NULL)` (unlike `DiscoveredDeviceResponse`), so every field below is always
 * present on a `200` — `incompleteReason` serializes as a literal `null` when `complete` is `true`,
 * never omitted (C7 — "never probed anything for this field" is itself an honest, renderable
 * answer). `sysid`/`firmware`/`firmwareVersion`/`vehicleKind`/`capabilityBitmask`/
 * `linkBytesPerSecond` are nullable for the same reason: a short or interrupted probe may complete
 * with some fields still unobserved.
 */
export interface VehicleProfile {
  readonly linkKey: string;
  readonly observedAt: string;
  readonly sysid: number | null;
  readonly firmware: string | null;
  readonly firmwareVersion: string | null;
  readonly vehicleKind: string | null;
  readonly capabilityBitmask: number | null;
  readonly capabilityFlags: readonly string[];
  readonly messages: readonly MessageObservation[];
  readonly parameters: readonly ParameterReading[];
  readonly linkBytesPerSecond: number | null;
  readonly complete: boolean;
  readonly incompleteReason: string | null;
}

/**
 * Request body for `POST /api/onboarding/probe` — mirrors `ProbeCandidateRequest`, deliberately the
 * same `{protocol, uri, options}` shape as `ProbeDeviceRequest` above (the plan's own "mirrors the
 * existing probe-before-save gesture, just against a vehicle link"). `options["sysid"]`, when
 * present, is folded into the derived candidate identity server-side.
 */
export interface ProbeCandidateRequest {
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
}

/**
 * The frozen v1 feature keys (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) — mirrors
 * `FeatureRequirement.FEATURE_KEYS` verbatim, in the seed migration's own row order (not
 * alphabetical), so a fleet-board column order that follows this array matches the report's own
 * `features` array order (`ReadinessRowResponse`'s javadoc: "insertion order matches the report's
 * own feature order").
 */
export const FEATURE_KEYS = [
  'map-position',
  'preflight-checks',
  'ground-speed',
  'link-quality',
  'failsafe-banners',
  'battery',
  'visual-geolocation',
  'fleet-identity',
  'command-tx',
  'rc-relay',
  'video-ingest',
] as const;

/** One of the eleven frozen v1 {@link FEATURE_KEYS} strings. */
export type FeatureKey = (typeof FEATURE_KEYS)[number];

/** Mirrors `ReadinessVerdict` — frozen wire spelling, the enum constant name is the wire string. */
export type ReadinessVerdict = 'GO' | 'NO_GO' | 'UNKNOWN';

/** Mirrors `FeatureStatus` — frozen wire spelling, the enum constant name is the wire string. */
export type FeatureStatus = 'READY' | 'DEGRADED' | 'MISSING' | 'UNKNOWN';

/**
 * Mirrors `RemedyKind` — frozen wire spelling. A {@link FeatureReadiness} row with no automatable
 * remedy carries `remedy: null`, not a fifth constant here.
 */
export type RemedyKind = 'MESSAGE_INTERVAL' | 'PARAM_WRITE' | 'CLI_SCRIPT' | 'MANUAL';

/**
 * One `ReadinessReportResponse#features()` row. Mirrors `FeatureReadinessResponse` — note the wire
 * key is `feature`, not `featureKey` (§8.1's own frozen spelling, carried through unchanged).
 */
export interface FeatureReadiness {
  readonly feature: FeatureKey;
  readonly label: string;
  readonly status: FeatureStatus;
  readonly detail: string;
  readonly remedy: RemedyKind | null;
}

/**
 * Mirrors `ReadinessReportResponse` — the body of `GET /api/assets/{assetId}/readiness`, and a
 * remediation's own `reprobe`. No `NON_NULL` on the Java side (verified against source):
 * `profileObservedAt` is a literal `null`, never omitted, for an asset that was never probed (C7) —
 * `ReadinessReport`'s own javadoc calls this itself an honest, renderable answer, not a failure.
 */
export interface ReadinessReport {
  readonly assetId: string;
  readonly verdict: ReadinessVerdict;
  readonly evaluatedAt: string;
  readonly profileObservedAt: string | null;
  readonly features: readonly FeatureReadiness[];
  readonly blockers: readonly string[];
}

/**
 * One `GET /api/fleet/readiness` row. Mirrors `ReadinessRowResponse` — deliberately flatter than
 * {@link ReadinessReport} (no `detail`/`remedy` text): the fleet board renders a compact status
 * grid, the per-asset readiness screen renders the full report. `features` is `featureKey ->
 * status`, one entry per row the report evaluated, in the report's own order.
 */
export interface ReadinessRow {
  readonly assetId: string;
  readonly displayName: string;
  readonly verdict: ReadinessVerdict;
  readonly features: Record<string, FeatureStatus>;
}

/** Mirrors `FleetReadinessResponse` — the body of `GET /api/fleet/readiness`. */
export interface FleetReadiness {
  readonly assets: readonly ReadinessRow[];
}

/**
 * Request body for `POST /api/assets/{assetId}/remediate` — mirrors `RemediationRequest`.
 * `features` names which {@link FeatureReadiness} rows to attempt; `actions` names which
 * {@link RemedyKind} mechanisms the caller allows — a feature whose own remedy isn't listed here
 * reports `UNSUPPORTED`, never silently skipped.
 */
export interface RemediationRequest {
  readonly features?: readonly FeatureKey[];
  readonly actions?: readonly RemedyKind[];
}

/**
 * One `RemediationResultResponse#actions()` entry — mirrors `RemediationActionResponse`. No
 * `NON_NULL` on the Java side (verified against source): `messageId`/`intervalMicros`/
 * `previousValue`/`newValue` all serialize as literal `null` when inapplicable to this action, never
 * omitted. `action` itself is `null` when no remedy applies to the feature at all (mirrors
 * `FeatureReadiness.remedy()`'s own nullability); `detail` is always an honest sentence (C7).
 */
export interface RemediationAction {
  readonly action: RemedyKind | null;
  readonly messageId: number | null;
  readonly intervalMicros: number | null;
  readonly outcome: 'ACCEPTED' | 'DENIED' | 'NO_ACK' | 'UNSUPPORTED';
  readonly previousValue: number | null;
  readonly newValue: number | null;
  readonly detail: string;
}

/**
 * Mirrors `RemediationResultResponse` — the body of `POST /api/assets/{assetId}/remediate`.
 * `verifiedAt`/`reprobe` are both `null` together when nothing was actually dispatched to the
 * vehicle (nothing to verify) — e.g. every requested action came back `UNSUPPORTED`.
 */
export interface RemediationResult {
  readonly requestedAt: string;
  readonly verifiedAt: string | null;
  readonly actions: readonly RemediationAction[];
  readonly reprobe: ReadinessReport | null;
}

/**
 * Request body for `POST /api/assets/{id}/parameters` (docs/plans/active/FLEET-RADIO-PLAN.md R5) —
 * mirrors `ParameterWriteRequest`. `consent` must be exactly `true`, sent only because the operator's
 * own explicit click on this write action *is* that consent — never defaulted, never sent from an
 * automatic/background call (mirrors `RemediationOrchestrator`'s own refusal to synthesise this shape,
 * D6). A `consent` other than `true`, a blank `name`, or a missing `value` all 400 before the asset is
 * even resolved — see `AssetParameterController#writeParameter`'s own javadoc.
 */
export interface ParameterWriteRequest {
  readonly name: string;
  readonly value: number;
  readonly consent: boolean;
}

/**
 * Mirrors `ParameterWriteResponse` — the body of `POST /api/assets/{id}/parameters`. No
 * `NON_NULL` on the Java side (verified against source, same convention as `VehicleProfile`/
 * `RemediationResult` above): every field is always present, literal `null` where nothing was read.
 * `parameterName` is the spelling actually sent to the vehicle (`AssetParameterController`'s own
 * spelling resolution, F0), not necessarily the one the request named.
 */
export interface ParameterWriteResponse {
  readonly parameterName: string;
  readonly outcome: 'ACCEPTED' | 'DENIED' | 'NO_ACK' | 'UNSUPPORTED';
  readonly previousValue: number | null;
  readonly newValue: number | null;
  readonly detail: string | null;
}

// --- System status (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.1/§4.3's frozen wire contract, S3) -----------
// `GET /api/system/status` — the platform's own honest "is it working right now" surface
// (`SubsystemStatusPort`/`SystemStatusController`, station/vision-api). Backs `core/system-status/**`
// (the shell rollup dot + `/manage/system`) and `/debug`'s repointed Health card.

/**
 * Mirrors `platform.SubsystemStatusPort.Health` — one subsystem's own reading. `'DISABLED'` means
 * "switched off by config" and **must render as neutral/off, never as a fault**
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.1's own explicit rule — a deliberately-disabled subsystem is not
 * a problem); `'UNKNOWN'` covers both "a provider threw while answering" and ordinary "nothing to
 * report yet" cases (e.g. `mavlink-link` before any vehicle is claimed, per the S2 outcome notes this
 * type's own doc comment is transcribed from) — this app treats it the same honest, uncoloured way as
 * `'DISABLED'` (see `core/system-status/system-status-logic.ts#healthSeverity`), rather than guessing
 * which of the two it is. `Overall` below is the same four values minus `'DISABLED'` — the backend
 * excludes disabled subsystems from the rollup entirely, so the top-level verdict can never carry it.
 */
export type SubsystemHealth = 'OK' | 'DEGRADED' | 'DOWN' | 'DISABLED' | 'UNKNOWN';

/** Mirrors `SystemStatusResponse#overall` — the worst subsystem `SubsystemHealth`, `'DISABLED'` ignored, `'UNKNOWN'` for an empty subsystem list. */
export type OverallHealth = 'OK' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

/**
 * Mirrors `dto.SubsystemStatusResponse` — one row of `SystemStatusResponse#subsystems`. `id` is a
 * stable slug (`cv-service`/`mavlink-link`/`video-publish`/`live-updates` today, per §4.2's frozen
 * table) the UI keys off but never hard-codes into layout — the backend wires providers
 * conditionally, so the list itself is not fixed (§5.1's own instruction: render whatever arrives).
 * `detail` is the backend's own honest human sentence; `since`/`hint` are each independently **absent,
 * not `null`**, when there is nothing to say — same `@JsonInclude(NON_NULL)` convention as every other
 * optional field in this file.
 */
export interface SubsystemStatus {
  readonly id: string;
  readonly label: string;
  readonly health: SubsystemHealth;
  readonly detail: string;
  readonly since?: string;
  readonly hint?: string;
}

/**
 * Mirrors `dto.SystemStatusResponse`, the body of `GET /api/system/status` — **readable by any
 * authenticated user, not `managerOnly`** (§4.3's own deliberate call: an operator whose CV died must
 * be able to see why, and the response exposes no secrets, hostnames/states only). A provider that
 * throws server-side still returns a `200` with that one subsystem reported `'UNKNOWN'` (§4.3: "one
 * sick provider must not blind the page") — this app never needs to special-case a failed subsystem
 * read the way it does a failed *endpoint* read.
 */
export interface SystemStatus {
  readonly overall: OverallHealth;
  readonly checkedAt: string;
  readonly subsystems: readonly SubsystemStatus[];
}

// --- Ops thresholds (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "Battery thresholds" D6, wave S3) ------------
// `GET /api/ops/thresholds` — the ONE severity source the Fly cockpit's OSD and fleet attention-logic
// both read (`core/ops/thresholds-store.ts`), replacing their old separately-hardcoded, disagreeing
// thresholds (OSD's own 20/45, fleet's own 20/10). `@OpenByDesign` server-side — display config, not
// fleet or per-user data, so any signed-in caller may read it.

/**
 * Mirrors `dto.BatteryThresholdsResponse` — one severity-threshold group inside {@link
 * OpsThresholdsResponse}. Both boundaries are **inclusive** ("at/below" — the backend's own DTO
 * javadoc, echoed by `platform.EventType#BATTERY_LOW`'s "at or below"/"at or above" hysteresis
 * wording): a reading exactly at `criticalPercent` is already critical, exactly at `warningPercent`
 * is already warning, matching `core/fleet/attention-logic.ts#batteryAttentionSeverity` and
 * `core/telemetry/telemetry-logic.ts#batterySeverity`, both rewired this wave to the same `<=`
 * comparison so the two surfaces can never disagree at the boundary value itself again.
 */
export interface BatteryThresholds {
  readonly warningPercent: number;
  readonly criticalPercent: number;
}

/**
 * Mirrors `dto.OpsThresholdsResponse`, the body of `GET /api/ops/thresholds` — deploy-time config
 * (`vision.ops.battery.*`), not a per-request computation, read once per SPA session
 * (`core/ops/thresholds-store.ts`).
 */
export interface OpsThresholdsResponse {
  readonly battery: BatteryThresholds;
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
 * feature 7), the acting user's own recent actions, **and**, since docs/plans/done/OPS-UX-PLAN.md §3 B1,
 * `GET /api/audit` (`core/api/vision-api.ts#listAudit`), the fleet-wide equivalent behind
 * `features/audit/**` — the same DTO on the wire, so one type backs both. `summary` is written to
 * read on its own (no id reconstruction needed); `details` carries before→after specifics, and,
 * on some entries, a `result` key (`"DENIED:<reason>"` on a refused attempt — see
 * `core/audit/audit-logic.ts#auditResult`). `action` is one of
 * `CREATED`/`UPDATED`/`DEACTIVATED`/`ACTIVATED`/`DELETED`/`RESTORED`, `targetType` one of
 * `ASSET`/`DEVICE`/`DATASET`/`MODEL` — both left as plain strings here (the UI renders them via
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
 * What one control *does* to the vehicle. RC1 is roll on a copter and steering on a rover, so the
 * channel number alone never carried the meaning — this does.
 */
export type ControlFunction =
  | 'ROLL'
  | 'PITCH'
  | 'THROTTLE'
  | 'YAW'
  | 'STEERING'
  | 'AUX_1'
  | 'AUX_2'
  | 'AUX_3'
  | 'AUX_4';

/**
 * Where a control rests, and therefore what its travel means
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P3).
 *
 * - `'CENTERED'` — rests at `centerMicros`, travels both ways. Roll, pitch, yaw, steering, **and a
 *   rover's throttle**, where centre is *stop*: 50→0 is reverse, 50→100 is forward.
 * - `'UNIDIRECTIONAL'` — rests at `minMicros`, travels one way. A copter's or plane's throttle:
 *   0→100 with rest at 0, because a released stick on a multirotor must be idle, not half power.
 *
 * This is the field a control surface must branch on. Drawing both throttles the same way is the
 * exact defect the profiles exist to close.
 */
export type ControlTravel = 'CENTERED' | 'UNIDIRECTIONAL';

/**
 * One physical control → one RC channel, as sent on the `engaged` frame's `channelMap`. Mirrors
 * `domain.model.ControlBinding`.
 *
 * The microsecond fields are carried so a client can *render* the control honestly (where it rests,
 * how far it travels) — nothing here recomputes them for the wire: mapping an input to microseconds
 * is entirely the backend's `ChannelMap#apply` job, and `deadband`/`reversed` stay server-side.
 */
export interface ManualControlChannelBinding {
  readonly source: ControlSource;
  /** What the operator declared this control physically *is* — a different question from `source`
   * (an EdgeTX 3-position switch is a `SWITCH_3` read from the `AXIS` array). Added additively by
   * docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C1; it is what lets a surface draw a
   * detented switch instead of a slider. */
  readonly kind: ControlInputKind;
  readonly function: ControlFunction;
  readonly travel: ControlTravel;
  readonly sourceIndex: number;
  readonly rcChannel: number;
  readonly minMicros: number;
  readonly centerMicros: number;
  readonly maxMicros: number;
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

/**
 * `vehicleKind`/`profileCode`/`profileName` were added additively
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P13) — the server resolves the vehicle
 * kind from the heartbeat it is hearing at engage time and shapes `channelMap` from it, so the
 * client renders what the machine actually is rather than assuming a multirotor.
 */
export interface ManualControlEngagedMessage {
  readonly type: 'engaged';
  readonly assetId: string;
  readonly rateHz: number;
  readonly vehicleKind: VehicleKind;
  /** Short channel-order code, e.g. `'AETR'` for an aircraft, `'S-T-'` for a ground vehicle. */
  readonly profileCode: string;
  /** What to call this vehicle in front of an operator, e.g. `'Multirotor'`. */
  readonly profileName: string;
  /** The engaged layout's id — the handle to find its action bindings in `GET /api/control-profiles`
   * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.4). */
  readonly profileId: string;
  /** Whether the operator's own saved layout was engaged, or the platform's fallback. An operator
   * who configured one and is nonetheless flying `'BUILT_IN'` has an activation problem they cannot
   * otherwise see. */
  readonly profileSource: ControlProfileSource;
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

// --- CV model registry (docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§5.2, wave W8) --------------------------
// The registry's mutation surface — promote a model to `LIVE`, or roll back to whichever model that
// promotion demoted. **The read side moved**: `GET /api/cv/registry/models` is deleted; the roster
// (worker truth joined with platform governance — status/runtime/metrics/provenance/availability) is
// now `GET /api/cv/models`/`CvModelsResponse` above, the one roster this app reads (`CvModel#status`/
// `#source` distinguish a registry row from a static config entry — see that interface's own doc
// comment). Gated by `vision.cv.registry.enabled` (default `vision.cv.enabled`), **not**
// `vision.training.enabled` — `ModelRegistryController`'s own javadoc, CV-SETTINGS-PLAN.md §5.5.
// `features/models/**` is the one consumer.

/** Mirrors `dto.PromoteModelRequest` — the body of `POST /api/cv/registry/models/{id}/promote`. `version` must be non-blank server-side (`ModelRef`'s own compact-constructor check, surfaced as a 400). */
export interface PromoteModelRequest {
  readonly version: string;
}

/**
 * Mirrors `dto.PromotionResultResponse` — the shared response body of `POST
 * /api/cv/registry/models/{id}/promote` and `POST /api/cv/registry/rollback` (§5.2): the model now
 * `LIVE`, and whichever model (if any) that operation demoted to `RETIRED`. `id`/`version` name the
 * model **now live** — not the one that was just promoted/rolled-back *from* — per the wire
 * contract's own field names (unlike the domain `PromotionResult#modelId`).
 *
 * `previousModelId`/`previousVersion` are **absent, not `null`**, when nothing was demoted (the
 * DTO carries `@JsonInclude(NON_NULL)` server-side, which omits a null field from the JSON body
 * entirely rather than serializing it as `null`) — read `undefined` the same as any other
 * "not reported" field in this app, never fabricate a "none" string.
 */
export interface PromotionResultResponse {
  readonly id: string;
  readonly version: string;
  readonly status: 'LIVE';
  readonly previousModelId?: string;
  readonly previousVersion?: string;
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

// --- After-action evidence package (docs/plans/done/AFTER-ACTION-PLAN.md §3's frozen wire contract, wave W2) ---
// One request against one finished flight returns everything the platform knows about it, in open
// formats, with an explicit account of what's missing/approximate (D3). Two endpoints (D2): this
// JSON manifest — also what `features/replay/**`'s after-action panel renders directly — and a ZIP
// archive (`VisionApi#afterActionArchiveUrl`) carrying the same facts as files, never fetched into
// memory here (it's a browser download, see that method's own doc comment). **Coded against the
// plan's own frozen §3.1 example verbatim, ahead of the backend (wave W1), which was built against
// the identical contract in parallel** — see `core/after-action/after-action-logic.ts`'s own doc
// comment for a defect found in that example while doing so.

/** The six things an evidence package accounts for, always in this exact order on the wire (`AfterActionManifest#parts`) — never omitted, never reordered (§3.1). */
export type AfterActionPart = 'telemetry' | 'detections' | 'marks' | 'recording' | 'passport' | 'audit';

/**
 * What happened when the assembler tried to gather one part (D3). `PRESENT`/`ABSENT` are both
 * honest "nothing went wrong" outcomes (data existed, or genuinely doesn't); `TRUNCATED` is a
 * truthful "you got some of it, not all" (telemetry thinning, D7/§3.4); `FORBIDDEN` is the caller's
 * own role refusing a read the assembler otherwise could have made (e.g. the audit trail).
 * `ABSENT` and `FORBIDDEN` are deliberately distinct constants, not one "couldn't get it" bucket —
 * a referee must be able to tell "there is nothing to see" from "there is something, and you may
 * not see it" apart.
 */
export type AfterActionPartState = 'PRESENT' | 'ABSENT' | 'TRUNCATED' | 'FORBIDDEN';

/**
 * One row of `AfterActionManifest#parts`. `count` is `0` for `ABSENT`/`FORBIDDEN` (never a real
 * count masquerading as a state, §3.1). `note` is explicit `| null`, not optional — this DTO family
 * deliberately does not mirror `@JsonInclude(NON_NULL)` as `?:` the way most of this file's other
 * types do (see `AfterActionManifest`'s own doc comment): `null` is `null` only when
 * `state === 'PRESENT'` and there's nothing to qualify, and must render as no text at all, never a
 * fabricated reassurance (`core/after-action/after-action-logic.ts#buildPartRows`).
 */
export interface AfterActionPartStatus {
  readonly part: AfterActionPart;
  readonly state: AfterActionPartState;
  readonly count: number;
  readonly note: string | null;
}

/**
 * Mirrors the after-action assembler's manifest DTO verbatim — `GET
 * /api/assets/{assetId}/usages/{usageId}/after-action`. Every field is always present on a 200;
 * `endedAt` and each part's own `note` are the two places `null` is meaningful data, not absence —
 * **`@JsonInclude(NON_NULL)` is deliberately not used on this DTO family** (§3.1's own explicit
 * rule), the one deliberate exception to this file's usual "absent, not `null`, typed `?:`"
 * convention.
 *
 * `open: true` / `endedAt: null` together mean the flight is still running — the package is still
 * served regardless (a still-open usage is a valid input, never a 404, §5 hazard 4), windowed as if
 * `endedAt` were "now". `scopedTo` names the viewer whose visibility scope actually built this
 * package (D6) — two referees with different scopes get different, both-correct packages.
 * `complete === true` iff every part is `PRESENT`; `caveats` is the ordered list of every non-null
 * `note` from a non-`PRESENT` part, empty when `complete` (see `after-action-logic.ts#deriveCaveats`
 * for why this app recomputes that list itself rather than trusting this field verbatim).
 */
export interface AfterActionManifest {
  readonly assetId: string;
  readonly assetName: string;
  readonly usageId: string;
  readonly startedAt: string;
  readonly endedAt: string | null;
  readonly open: boolean;
  readonly generatedAt: string;
  readonly scopedTo: string;
  readonly parts: readonly AfterActionPartStatus[];
  readonly complete: boolean;
  readonly caveats: readonly string[];
}

// --- Controller setup: what each stick/button/switch does -------------------------------------
// docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4. `/api/control-profiles` CRUD + catalogue; the
// engaged `channelMap` above is the same binding model, narrowed to what a live session needs.

/** Which Gamepad array a control's value is read from. */
export type ControlSource = 'AXIS' | 'BUTTON';

/**
 * What a control physically *is*, as the operator declared it — deliberately separate from
 * {@link ControlSource} (decision C1): an EdgeTX 3-position switch arrives on the *axes* array, a
 * latching toggle on the *buttons* array. `'SWITCH_3'` can only be read from an axis; one button
 * cannot report three positions.
 */
export type ControlInputKind = 'AXIS' | 'BUTTON' | 'SWITCH_2' | 'SWITCH_3';

/**
 * Where a switch is sitting — ArduPilot's own three positions, at the firmware's own PWM bands
 * (<1200 µs LOW, 1200–1800 MIDDLE, >1800 HIGH). A `SWITCH_2` uses LOW/HIGH; a `BUTTON` is HIGH
 * while pressed.
 */
export type SwitchPosition = 'LOW' | 'MIDDLE' | 'HIGH';

/**
 * What a switch position fires. Short on purpose: this is what the platform can genuinely send, not
 * what a ground station could imagine (CONTROLLER-SETUP-CONTEXT.md §2.4). `'AUX_FUNCTION'` is the
 * escape hatch that reaches every switch-driven ArduPilot feature by `RCx_OPTION` number.
 */
export type ControlAction =
  | 'ARM'
  | 'DISARM'
  | 'TOGGLE_ARM'
  | 'EMERGENCY_STOP'
  | 'RETURN_TO_HOME'
  | 'SET_MODE'
  | 'AUX_FUNCTION';

/** What an action needs configured alongside it, and therefore what the UI must ask for. */
export type ControlActionParameter = 'NONE' | 'MODE_NAME' | 'AUX_FUNCTION';

/** Whether a layout is one the operator saved or one of the platform's built-ins. */
export type ControlProfileSource = 'SAVED' | 'BUILT_IN';

/**
 * One control that *streams* into an RC channel. `travel`/`label` are derived server-side and
 * ignored on write — send them back unchanged or omit them, the server recomputes either way.
 */
export interface ControlBinding {
  readonly source: ControlSource;
  readonly kind: ControlInputKind;
  readonly function: ControlFunction;
  readonly sourceIndex: number;
  readonly rcChannel: number;
  readonly minMicros: number;
  readonly centerMicros: number;
  readonly maxMicros: number;
  readonly deadband: number;
  readonly reversed: boolean;
  readonly travel?: ControlTravel;
  readonly label?: string;
}

/** What one position of a switch does. `parameter` is a mode name, an `RCx_OPTION` number, or absent. */
export interface PositionAction {
  readonly position: SwitchPosition;
  readonly action: ControlAction;
  readonly parameter?: string | null;
}

/** One control whose *positions* fire one-shot commands, instead of streaming into a channel. */
export interface ActionBinding {
  readonly source: ControlSource;
  readonly kind: ControlInputKind;
  readonly sourceIndex: number;
  readonly positions: readonly PositionAction[];
}

/**
 * One controller layout. A built-in is read-only — every write endpoint refuses its id — and is
 * copied rather than edited (`POST /api/control-profiles`).
 *
 * `active` answers "is this what a session would actually engage with", which for a built-in means
 * the operator has activated no saved profile for its vehicle kind.
 */
export interface ControlProfile {
  readonly id: string;
  readonly source: ControlProfileSource;
  readonly kind: VehicleKind;
  readonly code: string;
  readonly name: string;
  readonly active: boolean;
  readonly updatedAt?: string;
  readonly channelMap: readonly ControlBinding[];
  readonly actionMap: readonly ActionBinding[];
  /**
   * The owner's transmitter mode, 1–4, and which way its vertical axes read. Both change only how
   * the layout is **drawn** — never a microsecond on the wire — and both are stored per layout
   * rather than per browser, so setting a layout up on one machine and flying it from another does
   * not ask the same question twice. A built-in reports the platform default, having no owner.
   */
  readonly stickMode: number;
  readonly forwardIsUp: boolean;
}

/** `POST /api/control-profiles` — starts a copy of the built-in for `kind`. */
export interface CreateControlProfileRequest {
  readonly kind: VehicleKind;
  readonly name: string;
}

/** `PUT /api/control-profiles/{id}` — the whole layout, replaced in one write (see the DTO's own
 * javadoc for why this is not a per-binding PATCH). */
export interface UpdateControlProfileRequest {
  readonly name: string;
  readonly channelMap: readonly ControlBinding[];
  readonly actionMap: readonly ActionBinding[];
  /** See `ControlProfile`; omitted by a client with no opinion, which the backend reads as the default. */
  readonly stickMode?: number;
  readonly forwardIsUp?: boolean;
}

/**
 * `GET /api/control-profiles/catalog` — everything the setup page may offer, answered by the
 * backend rather than duplicated here (decision C8): a UI holding its own copy eventually offers an
 * action the server refuses, discovered at the moment the operator flicks the switch.
 */
export interface ControlCatalog {
  readonly vehicleKinds: readonly { readonly name: VehicleKind; readonly label: string }[];
  readonly inputKinds: readonly {
    readonly name: ControlInputKind;
    readonly label: string;
    readonly sources: readonly ControlSource[];
    readonly positions: readonly SwitchPosition[];
  }[];
  readonly positions: readonly {
    readonly name: SwitchPosition;
    readonly label: string;
    /** The value `MAV_CMD_DO_AUX_FUNCTION` expects — so nothing here hardcodes that HIGH is 2. */
    readonly level: number;
  }[];
  readonly functions: readonly { readonly name: ControlFunction; readonly label: string }[];
  readonly actions: readonly {
    readonly name: ControlAction;
    readonly label: string;
    readonly parameter: ControlActionParameter;
    /** Needs the extra confirmation friction of decision C9 — the arm/disarm family. */
    readonly dangerous: boolean;
  }[];
  readonly auxFunctions: readonly { readonly number: number; readonly label: string }[];
  /**
   * The highest RC channel worth binding — what the relay actually puts on the wire, not the
   * `[1,18]` the backend will validate. Served rather than assumed (C8): a binding above it is
   * stored and shown and never sent, so offering more would be offering nothing.
   */
  readonly maxRcChannel: number;
}

/** `POST /api/assets/{id}/aux-function` — `level` is ArduPilot's own 0 LOW / 1 MIDDLE / 2 HIGH. */
export interface AuxFunctionRequest {
  readonly function: number;
  readonly level: number;
}
