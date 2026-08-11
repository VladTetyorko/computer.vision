# CV-CONTROL-PLAN — live per-stream CV control + open-vocabulary model

Status: **draft for review** (2026-07-30). User go received for the shape; frozen contract below.
Gives the operator full, live control of the detection pipeline of a *running* stream from the Fly
cockpit — model, confidence, inference-fps, which classes to keep, detection on/off — and adds a
single open-vocabulary model (prompt-free YOLOE) that detects people + vehicles + buildings without
any proto/wire change.

Two problems, one plan:
1. **The pipeline is write-once.** `PipelineConfig` is fixed at `StreamService.start(...)` and never
   changes for the stream's life — the operator must stop and restart a stream to touch any knob,
   and even then only three of eight knobs are reachable from the UI. There is no per-stream
   "detection off" at all (the only off-switch is the JVM-wide boot flag `vision.cv.enabled`).
2. **The model set is hardcoded and narrow.** `yolo11n`/`orion12l`/composite, wired as a literal
   `DETECTION_MODEL_OPTIONS` array in the SPA (`settings-store.ts:45`). A user who wants "people,
   cars, and buildings" has no single model that covers it and no UI to ask for it.

## Goal, in the operator's terms

> "While I'm flying, let me point the detector at what I care about — pick a model, drag the
> confidence and rate, tick the classes I want boxes for (people, vehicles, buildings), and turn
> detection off entirely when I want the CPU back — and have it take effect on the live stream
> without dropping video."

Made precise:
- The cheap knobs (confidence, inference-fps, label filter, detection on/off) apply **live** to the
  running pipeline — no video interruption, no stream restart.
- A **model** change applies via a quick internal detector swap (a brief detection gap, video
  untouched) — honestly surfaced as "briefly re-arms detection", not hidden.
- A new prompt-free **YOLOE** model exposes a ~1200-class open vocabulary; the operator narrows it
  to the classes they want with the label-filter multi-select (a "People + vehicles + buildings"
  default preset). Runs on **laptop CPU** via OpenVINO export.

## Current state (honest)

| Layer | Today | Gap this plan closes |
|---|---|---|
| `PipelineConfig` (domain) | 8 fields, immutable; `defaults()` model = `ModelRef("yolo","latest")` — an id matching **no** real checkpoint, so every default stream silently falls back to cv-service's `DEFAULT_MODEL` | add `detectionEnabled`; fix `defaults()` to a real checkpoint id |
| `labelFilter` (domain) | field exists + is validated + defensively copied — **but is applied nowhere**: not in `StreamPipeline`, not in the cv-grpc adapter, not on the wire. Fully dormant. | actually enforce it (Java-side drop in `StreamPipeline`) |
| Per-stream detection on/off | **does not exist**; only the JVM-wide `vision.cv.enabled` boot flag | `detectionEnabled` field + pipeline skips `detect()` when false |
| `StreamPipeline` config | captured at ctor, read from a final `config` field (`inferenceFps`, `maxInFlightInferences`, `overlayBurnIn`, `overlayTelemetry`, passed whole to `detectionPort.detect`) — **no update path** | swappable `volatile` config + `updateConfig(...)`; model change → detector re-arm |
| `StreamService` | `start` / `stop` / `streams` / `latestFrame` — no update | add `updateConfig(StreamId, PipelineConfigPatch)` |
| REST | `POST /api/devices/{id}/stream` + `POST /api/assets/{id}/stream` (start), `DELETE`, `GET /detections`, `GET /snapshot`. `StartStreamRequest` exposes only confidence/fps/overlayBurnIn/model. **No PATCH.** No model-roster endpoint. | `PATCH /api/streams/{id}/config`; expose labelFilter + detectionEnabled on start; `GET /api/cv/models` |
| Model roster | `ModelRegistryPort` declared but **dormant** (no impl, no controller); UI list hardcoded | `GET /api/cv/models` from a static/config list in vision-app (dormant port stays dormant — see D) |
| cv-service | `YoloDetector` always builds `ultralytics.YOLO(name)`; `ModelRegistry` discovers local `*.pt` / `*_openvino_model`; default `yolo26n.pt` | **no Python code change** — a `yoloe-*-seg-pf.pt` dropped in the service dir auto-routes through the existing `YOLO(...)` + roster glob; add seg→box test, OpenVINO export note, roster/perf docs |
| Angular | `SettingsStore` with hardcoded `DETECTION_MODEL_OPTIONS`; `fly.html` has **no** CV panel; `live.html` has model/conf/fps controls; detections already render as a client vector overlay | Fly CV control panel; data-driven model list; label chips + detection toggle; PATCH wiring |

Video is structurally independent of CV (detection is a side-branch of `StreamPipeline`; every frame
publishes regardless — see REMOTE-CV-PLAN "Video never depends on CV"). That is what makes live knob
changes and a detection re-arm safe: none of them touch the frame/publish path.

---

## Frozen wire contract

Everything below is frozen; all waves code against it and may parallelize. Property names, JSON
shapes, status codes, and the domain field addition are pinned exactly.

### 1. `PipelineConfig` field addition (domain)

Add **one** field, `boolean detectionEnabled`, appended last, defaulting `true` (unchanged behavior):

```
record PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps,
                      int maxInFlightInferences, boolean overlayTelemetry, Set<String> labelFilter,
                      EventRuleConfig eventRule, boolean overlayBurnIn, boolean detectionEnabled)
```

- `public static final boolean DEFAULT_DETECTION_ENABLED = true;`
- Respect the record's existing **N-1-arg convenience-ctor idiom**: the current 8-arg canonical ctor
  becomes a convenience ctor delegating with `DEFAULT_DETECTION_ENABLED` (so every existing caller —
  `mergeOntoDefaults`, tests, `defaults()` — compiles unchanged), and the new 9-arg becomes
  canonical. Keep the existing 6-/7-arg convenience ctors chaining as they do.
- `defaults()` returns `detectionEnabled = true` **and** fixes the model id: `new ModelRef("yolo26n.pt","latest")` — the real checkpoint cv-service already defaults to (`DEFAULT_MODEL = "yolo26n.pt"`), replacing the dead `"yolo"` id. This keeps the default model as the fast NMS-free `yolo26n` (people + vehicles), the approved default (§E). Version string stays `"latest"` (cv-service ignores version; only the id routes).

### 2. `StartStreamRequest` / `StartAssetStreamRequest` additions (vision-api DTOs)

Add two optional fields to **both** start DTOs; absent = default, exactly as the existing optional
fields work. Frozen JSON (all fields optional):

```json
{
  "confidenceThreshold": 0.4,
  "inferenceFps": 10,
  "overlayBurnIn": true,
  "model": "yoloe-26s-seg-pf.pt",
  "labelFilter": ["person", "car", "truck", "bus", "motorcycle", "bicycle", "building"],
  "detectionEnabled": true
}
```

- `labelFilter`: `List<String>` (JSON array) → merged into the config's `Set<String>` (empty/absent = all labels, existing semantics). Labels are the raw model class strings (see Open Questions on the exact YOLOE vocabulary spelling).
- `detectionEnabled`: `Boolean` (nullable) → defaults to `true`.
- `mergeOntoDefaults()` extends to fold both in.

### 3. `PATCH /api/streams/{streamId}/config` — live update (NEW)

Request body `UpdateStreamConfigRequest` — **every field nullable/optional; only present fields
change; absent fields are left as-is** (partial patch, not a full replace):

```json
{
  "confidenceThreshold": 0.5,
  "inferenceFps": 5,
  "labelFilter": ["person", "building"],
  "detectionEnabled": true,
  "model": "yoloe-26s-seg-pf.pt"
}
```

Responses:
- `200 {"streamId": "...", "modelReArmed": false}` — applied. `modelReArmed` is `true` **only** when the `model` field was present and differed from the running model (the caller then knows a brief detection gap occurred). All other knobs are hot and never re-arm.
- `404` — unknown / not-running stream id (this instance).
- `400 {"message": ...}` — a value fails `PipelineConfig` validation (confidence outside [0,1], inferenceFps ≤ 0, etc.) — same validation the record's compact ctor already enforces, surfaced as 400.
- `409 {"message": ...}` — reserved for "cannot apply" states if any arise (e.g. stream mid-teardown); Stage-1 keeps one refusal code, mirroring the FlightCommand endpoints' idiom.

Semantics frozen: `maxInFlightInferences`, `overlayTelemetry`, `overlayBurnIn`, `eventRule`, and
model **version** are **not** PATCH-able (not in the DTO). `overlayBurnIn` stays start-only
deliberately — it changes the encode path, not a live knob. A model **id** change is the only knob
that re-arms.

### 4. `GET /api/cv/models` — model roster for the picker (NEW)

```json
{
  "models": [
    { "id": "yolo26n.pt", "displayName": "General (people & vehicles, fast)",
      "kind": "general", "openVocab": false, "defaultLabelFilter": [] },
    { "id": "orion12l.pt", "displayName": "Military vehicles",
      "kind": "specialized", "openVocab": false, "defaultLabelFilter": [] },
    { "id": "yoloe-26s-seg-pf.pt", "displayName": "Everything (incl. buildings, slower)",
      "kind": "open-vocab", "openVocab": true,
      "defaultLabelFilter": [] }
  ]
}
```

- `yolo26n.pt` is listed **first** and is the default (`PipelineConfig.defaults()`): the fast NMS-free
  closed-set model for the common people+vehicles case. `yoloe-26s-seg-pf.pt` is the **opt-in**
  open-vocabulary model — materially slower on laptop CPU (open-set LRPC lookup + seg masks), so it is
  a deliberate coverage-vs-speed choice the operator makes, never the default (§E, §F).
- `id` — the exact checkpoint filename cv-service's registry routes on (what `model` on start/PATCH forwards verbatim). Composite ids (`"a.pt,b.pt"`) are permitted values of `model` but the roster lists atomic models only; "Both"-style composites remain a UI concern if kept.
- `kind` — free-form UI hint string (`general` | `specialized` | `open-vocab`); not an enum on the wire, to avoid a domain enum for a display concern.
- `openVocab` — drives whether the UI leads with the label-filter multi-select for that model.
- `defaultLabelFilter` — the class set the UI pre-selects when the operator picks that model (empty = "all classes"). **Refined after Wave A's real measurement:** the open-vocab YOLOE vocab is **4585 classes** and building detection is **image-dependent** (the model emits `building`/`skyscraper`/`office building`/`house`/`apartment`/`roof` but also `downtown`, `Prague Castle`, etc.), so a strict exact-match preset would silently drop most buildings. Therefore the open-vocab model's `defaultLabelFilter` is **empty (show all)**; the UI seeds its class chips from **labels observed in the live detection stream** (the SPA already has a detections store) so the operator prunes from reality. A curated "People + vehicles + buildings" quick-preset (a *set* of building-ish labels, not one string) is an **opt-in one-click chip-fill**, never an enforced silent default. Closed-set models (`yolo26n`/`orion12l`) may still carry a small fixed `defaultLabelFilter` since their class list is fixed and known.
- Backing (frozen decision): a **static/config-backed list in vision-app** (a `@ConfigurationProperties` `vision.cv.models` or an equivalent constant bean), **not** the dormant `ModelRegistryPort`, **not** a new cv-service RPC. Rationale in §D. Never errors; returns at least the built-ins.

### 5. `StreamService.updateConfig` (application)

```
UpdateOutcome updateConfig(StreamId streamId, PipelineConfigPatch patch);
```
- `PipelineConfigPatch` — an application-layer record of nullable knobs (confidence, inferenceFps, labelFilter, detectionEnabled, model-id); the api DTO maps onto it. Keeps the api → application boundary a command object, not the transport DTO.
- Throws `NoSuchElementException` for an unknown/stopped stream (→404), `IllegalArgumentException` for an invalid value (→400).
- Returns `UpdateOutcome(boolean modelReArmed)`.

---

## Design decisions (with rationale)

### A. Hybrid live-update: hot knobs swap a `volatile` config; model change re-arms

`StreamPipeline` reads its config live in `everyNth` (inferenceFps), `maybeDetect`
(maxInFlightInferences), `overlayIfNeeded`/`telemetrySampleFor` (overlay flags), and passes the whole
`config` into `detectionPort.detect(frame, config)` (confidence, model, labelFilter). Make the
`config` field **`volatile`** and add `updateConfig(PipelineConfig next)`:

- **confidence, inferenceFps, labelFilter, detectionEnabled** — a `volatile` write is all that's
  needed; the next sampled frame reads the new value. No lock, no restart. `inferenceFps` re-derives
  `sampleEveryNthFrame` on the next frame arrival naturally (`everyNth` reads `config.inferenceFps()`
  every call).
- **detectionEnabled = false** — `maybeDetect` returns immediately before any `detect()` submission,
  so **zero CPU** is spent on inference while video keeps flowing. Re-enabling resumes on the next
  sampled frame. (Gate at the top of `maybeDetect`, before outage/in-flight logic, so a disabled
  stream also stops probing.)
- **labelFilter enforcement (new)** — apply the filter in `onDetectionResult` **before** the result
  fans out: keep only detections whose label is in `config.labelFilter()` (empty = keep all). This is
  the honest place — it fixes the dormant-field gap and means the filter affects `latestDetections`,
  the extrapolator, the event engine, live SSE, and persistence uniformly. Filtering is **Java-side**
  because the proto carries no class list (consistent with the zero-wire-change decision); the model
  still infers all ~1200 classes and we drop the unwanted ones here.
- **model id change** — re-arm: `detectionPort` sessions are per-stream and bound to the model at
  session open; a new model id needs the detector swapped. `updateConfig` detects an id change, swaps
  the volatile config, and signals the detection branch to tear down and re-open its detection
  session on the next sample (video untouched throughout). Keep this **inside `StreamService` /
  `StreamPipeline`**, not a stream stop+start, so viewer URLs, usage session, and SSE topics survive.
  Return `modelReArmed = true` so the UI can message it.

`eventRule` and `overlayBurnIn` remain effectively immutable-per-stream (not PATCH-able); the
`DetectionEventEngine` is built once at `start` from `config.eventRule()` and keeps its instance —
we do not rebuild it on PATCH. Documented as a non-goal below.

### B. Minimal domain change — one field, N-1 ctor idiom

Only `detectionEnabled` is added. Everything else the feature needs (labelFilter) already exists on
`PipelineConfig` — the work is *enforcing* and *exposing* it, not modeling it. This keeps the domain
diff tiny and every existing `PipelineConfig` test green (the old canonical ctor lives on as a
convenience ctor). The `defaults()` model-id fix is a correctness bug-fix riding along: today's
default `ModelRef("yolo","latest")` never matches a checkpoint and relies on cv-service's
silent-fallback — pinning it to a real id (`yolo26n.pt`, the id cv-service already defaults to) makes the default honest.

### C. Guardrail — defaults leave existing behavior unchanged

`detectionEnabled` defaults `true`; `labelFilter` default stays empty (all labels); PATCH is
additive; start DTOs' new fields are optional. No existing stream, test, or caller changes behavior
unless a client sends the new fields. The `defaults()` model-id change is behavior-preserving at
runtime (server already fell back to a yolo model) but makes the intent explicit — call it out in the
domain test so the assertion documents the fix.

### D. `GET /api/cv/models` is config-backed, not the dormant registry — deliberately

`ModelRegistryPort` models versioned promote/rollback (a Phase-3 training-studio concern) and has no
impl. Wiring it now would be over-building for a **picker that needs a display list**. A static list
in vision-app (properties or a constant bean) is correct for v1: the roster changes at deploy time,
not runtime, and lives next to the `vision.cv.*` wiring that already knows the deployment's model
story. Note explicitly in the controller javadoc and MODULE.md that the future real source is either
the dormant `ModelRegistryPort` or a small cv-service roster RPC (cv-service's `ModelRegistry` already
knows its local roster) — a documented seam, not built now.

### E. Model strategy — fast closed-set default, open-vocab as an opt-in choice

**Approved:** `yolo26n` (NMS-free, fast, people+vehicles) stays the **default**; prompt-free YOLOE is
an **opt-in** "Everything (incl. buildings)" model the operator picks from the roster. Prompt-free is
YOLOE's heaviest mode (open-set text/LRPC lookup + instance-seg masks), materially slower than
closed-set `yolo26n` on laptop CPU — making it the default would tax every stream for a capability
most streams don't need. So the coverage-vs-speed tradeoff is a **UI control**, not a silent default.
The roster's `displayName`/hint copy names the cost honestly ("slower").

**labelFilter is the primary UX control for the open-vocab model.** Prompt-free YOLOE emits its full
built-in ~1200-class vocabulary (LVIS/Objects365). Unfiltered, that is noise. The label multi-select
is the main control for it, pre-seeded from the model's `defaultLabelFilter` ("People + vehicles +
buildings"). Perf implication stated honestly: the model **infers all classes every frame regardless
of the filter** (filter is a Java-side drop, §A), so filtering reduces on-screen clutter and
downstream cost, **not** inference cost. If inference cost must drop, the future optimization is
**text-prompted** YOLOE (`set_classes`/text embeddings to infer fewer classes) — deferred, not built
(it would touch the model-load path). Note: prompt-free checkpoints are **segmentation-only**
(`yoloe-*-seg-pf.pt`); we use only their boxes and ignore masks (§Wave A).

### F. Performance realism (laptop CPU)

State plainly in the plan and the UI hint copy:
- Prompt-free YOLOE (`yoloe-26s-seg-pf`) on laptop CPU will **not** sustain 30 detections/sec across
  several streams — it is YOLOE's heaviest mode (open-set lookup + seg masks), which is exactly why it
  is opt-in and `yolo26n` stays default. Rough single-stream expectation: OpenVINO-exported, imgsz 416,
  order of ~5–12 fps single-stream CPU (measure on the target laptop — treat as a budget, not a spec;
  the existing "General ~58ms, Both ~346ms" numbers in `settings-store.ts` are the calibration
  reference). The lighter `yoloe-26n-seg-pf` fallback trades accuracy for speed. Confirm on the machine.
- **Video fps is already decoupled from detection fps** (`inferenceFps` frame sampling; full-rate
  video always publishes). So video stays smooth no matter how slow detection is.
- The **inference-fps slider** and **detection on/off toggle** are the operator's explicit CPU-budget
  controls; label filter does not reduce CPU. Recommend OpenVINO export (~3× CPU speedup), the
  smallest acceptable YOLOE variant, and tuning `CV_MAX_CONCURRENT_INFERENCES` /
  `maxInFlightInferences` for multi-stream. Remote-GPU offload already exists via REMOTE-CV
  (config-only: point `vision.cv.endpoint` at a GPU box) and is a **future option, not part of this
  feature.**

---

## Implementation waves (disjoint file scopes)

Each wave ends **independently green** with its scoped build and its `MODULE.md` updated. Sequencing:
**A ‖ B** can start immediately and in parallel; **C** needs B (domain field); **D** needs B + C
(service method); **E** (UI) runs in parallel with everything against the frozen contract, integrating
last. The wire contract above is the sole coupling.

### Wave A — cv-service: enable prompt-free YOLOE (Python) — `cv-service/**`
Agent: general-purpose (Python). **Reduced scope — NO Python code change to load YOLOE.**
Verified facts driving this: prompt-free YOLOE checkpoints (`yoloe-*-seg-pf.pt`) load via the
**standard `ultralytics.YOLO(name)` class** (not a `YOLOE` class), need **no** `set_classes()` (they
use the built-in ~1200-class vocab automatically), and are **segmentation-only** — but Ultralytics
seg `Results` still expose `.boxes` (`.xyxy`/`.conf`/`.cls`), so cv-service's existing
`map_detections` (duck-types on `.boxes`) works **unchanged** and just ignores masks. A
`yoloe-26s-seg-pf.pt` dropped into `cv-service/` therefore auto-routes through the existing
`discover_roster` glob + `YoloDetector(YOLO(...))` with zero code change.
- **No** `inference.py` / `registry.py` class-dispatch change. Confirm `discover_roster` picks up the
  new checkpoint and it round-trips.
- Obtain the checkpoint: `yoloe-26s-seg-pf.pt` primary, `yoloe-26n-seg-pf.pt` lighter CPU fallback —
  via ultralytics' own auto-download (same mechanism as `yolo11n.pt`/`yolo26n.pt` today; ultralytics'
  own asset, not a third-party repo — consistent with the repo's weights policy). Document the
  one-time acquisition step in `cv-service/MODULE.md` / DEPLOY notes.
- Set the recommended model config so the checkpoint is present/loadable in the target deployment
  (roster picks it up automatically; no `CV_MODEL` default change — `yolo26n` stays default).
- `Dockerfile`: add an OpenVINO export step for the YOLOE checkpoint mirroring the existing yolo11n
  export (same fixed-imgsz-at-export caveat — export imgsz **must** equal runtime `CV_IMGSZ`; see
  `cv-service/MODULE.md`). **Verify export feasibility for the seg-pf architecture** (Open Q 4); if
  unsupported, ship the `.pt` path with the measured perf hit documented.
- Tests: (1) a **seg-style Results double** carrying **both `.boxes` and `.masks`** maps to boxes and
  ignores masks (proves the seg-only checkpoint is box-compatible — no ultralytics needed); (2) a
  real-weights smoke test **skip-if-absent** (matching `test_real_model.py`) that also **prints/captures
  the real `model.names`** so the UI's default class set can be reconciled to actual label strings
  (Open Q 3, 5). Verify: `pytest` green; `cv-service/MODULE.md` updated (roster incl. the two YOLOE
  ids, seg-pf box-only note, OpenVINO/imgsz coupling, measured single-stream fps, acquisition step).

### Wave B — domain: `detectionEnabled` + `defaults()` fix — `vision-domain/**`
Agent: domain-modeler.
- `PipelineConfig`: add `detectionEnabled` (canonical 9-arg ctor; old 8-arg becomes a convenience ctor
  delegating `DEFAULT_DETECTION_ENABLED`; 6-/7-arg chains preserved); add `DEFAULT_DETECTION_ENABLED`;
  javadoc the new field and the "detection off = skip detect, video continues" semantic.
- `defaults()`: `detectionEnabled = true` and model id → `new ModelRef("yolo26n.pt","latest")` (the id cv-service already defaults to; keeps `yolo26n` the fast default per §E).
- Do **not** touch `ModelRegistryPort` (stays dormant).
- Tests: field default, ctor-chain equivalence (old callers), `defaults()` asserts the real model id
  (documenting the bug-fix). Verify: `-pl vision-domain test` green; `vision-domain/MODULE.md` updated.

### Wave C — application: live update + skip-detect + label enforcement — `vision-application/**`
Agent: application-service. Depends on B.
- `StreamPipeline`: `config` field → `volatile`; add `void updateConfig(PipelineConfig next)` (swap +
  detect model-id change → signal detection-session re-arm). Gate `maybeDetect` on
  `config.detectionEnabled()` (skip all detect/probe when false). Apply `config.labelFilter()` in
  `onDetectionResult` before fan-out (empty = keep all) — **the dormant-field fix**.
- `StreamService`: add `updateConfig(StreamId, PipelineConfigPatch)` + `PipelineConfigPatch` record +
  `UpdateOutcome`. `DefaultStreamService.updateConfig`: resolve `RunningStream`, build the merged
  `PipelineConfig` from current + patch (validation via the record ctor → propagates as
  `IllegalArgumentException`), call `pipeline.updateConfig`, return `modelReArmed`.
- Tests (hand-fake ports): hot knob applies without re-open; `detectionEnabled=false` stops
  `detect()` calls (fake DetectionPort sees none) while publish continues; labelFilter drops
  non-matching detections from save/live/extrapolator; model-id change triggers re-arm and reports
  `modelReArmed=true`; unknown stream → `NoSuchElementException`; bad value → `IllegalArgumentException`.
  Verify: `-pl vision-application test` green; `vision-application/MODULE.md` updated.

### Wave D — vision-api / vision-app: PATCH + roster + start-DTO fields — `vision-api/**`, `vision-app/**`
Agent: spring-integrator. Depends on B + C.
- `StreamController`: add `PATCH /api/streams/{streamId}/config` → `UpdateStreamConfigRequest` DTO →
  `PipelineConfigPatch` → `streamService.updateConfig`; map `NoSuchElementException`→404,
  `IllegalArgumentException`→400, response `{streamId, modelReArmed}`. Follow the existing controller
  error-contract idiom (the FlightCommand endpoints' 400/404/409 split; audit is **not** required —
  a stream is transient plumbing, not an audited fleet change, exactly as `StreamController`'s class
  javadoc already states no acting user is threaded here).
- `StartStreamRequest` + `StartAssetStreamRequest`: add `labelFilter` + `detectionEnabled`; extend
  `mergeOntoDefaults()`.
- New `CvModelsController` (or fold into an existing cv-config controller): `GET /api/cv/models` from a
  config/static list in **vision-app** (`vision.cv.models` `@ConfigurationProperties` or a constant
  bean); DTO `CvModelResponse{id, displayName, kind, openVocab, defaultLabelFilter}`. Never errors.
- ArchUnit-safe (no adapter imports). Tests: PATCH happy path + 400/404 + `modelReArmed`; start with
  labelFilter/detectionEnabled; roster shape. Verify: `-pl vision-api test` and `-pl vision-app test`
  green; both `MODULE.md`s updated.

### Wave E — web-ui: Fly CV control panel — `vision-web/**`
Agent: web-ui. Parallel; integrates against the frozen contract.
- `core/api/models.ts`: extend TS `StartStreamRequest` with `labelFilter?`/`detectionEnabled?`; add
  `UpdateStreamConfigRequest`, `CvModel`/`CvModelsResponse`, `PatchStreamConfigResponse`.
- `core/api/vision-api.ts` + `core/fleet`: add `patchStreamConfig(streamId, patch)` and
  `getCvModels()`.
- `SettingsStore`: drop hardcoded `DETECTION_MODEL_OPTIONS`/`DetectionModelId` union (model list now
  from `GET /api/cv/models`); add `labelFilter` + `detectionEnabled` to `PipelineSettings`/profiles
  with migration-safe restore (mirror `withValidModel`'s backfill pattern — absent = default preset
  filter / enabled true).
- `features/fly`: new **CV control panel** in `fly.html` (currently has none) — model picker (from
  roster), confidence slider, inference-fps slider, class multi-select **chips** (seeded from the
  picked model's `defaultLabelFilter`, prominent for `openVocab` models), detection on/off toggle.
  Cheap knobs → `PATCH` live (debounced); model change → `PATCH` with a clear "briefly re-arms
  detection" hint driven off `modelReArmed`. No optimistic lies — reflect what the server accepted.
  Honest perf hint copy per §F.
- Tests: vitest for store rework + panel logic + api client; `tsc` clean; production build green.
  Verify: `npm test`/`tsc`/build green; component/store docs updated.

---

## Non-goals / deferred (named, not dropped)

- **Text-prompted YOLOE** (`set_classes` to infer *fewer* classes for CPU savings) — deferred; the
  filter here is display/Java-side only. The realistic future perf lever if inference cost bites.
- **PATCH-able `overlayBurnIn`, `overlayTelemetry`, `maxInFlightInferences`, `eventRule`, model
  version** — not live-updatable in v1 (overlayBurnIn changes the encode path; eventRule is bound
  into the event engine at start). Start-time only.
- **`ModelRegistryPort` activation / model promote-rollback / training studio** — stays dormant;
  `GET /api/cv/models` is config-backed with a documented future-source seam.
- **Remote-GPU offload for YOLOE** — already exists via REMOTE-CV (config-only). Not part of this
  feature.
- **Persisting per-stream config server-side / resuming knobs across restart** — the SPA `SettingsStore`
  persists the operator's *chosen* settings; the server holds live config only for the stream's life.
- **Composite "Both" with YOLOE** — composite mode still works on the wire, but the roster lists atomic
  models; a YOLOE+orion composite is not a planned preset.

## Open questions / to confirm during implementation

Confirmed (no longer open, driving the frozen contract): prompt-free checkpoints are
`yoloe-*-seg-pf.pt` (seg-only, box-compatible via `.boxes`); they load through the **standard
`ultralytics.YOLO(name)`** class with **no `set_classes()`**; recommended ids `yoloe-26s-seg-pf.pt`
(primary) / `yoloe-26n-seg-pf.pt` (fallback); `yolo26n` stays the default. So cv-service needs **no**
class-dispatch code (Wave A reduced accordingly).

Confirmed by Wave A (RESOLVED):
1. **Vocabulary label strings** — measured on the real `yoloe-26s-seg-pf.pt`: **4585-class** LVIS/Objects365
   vocab. people → `person` (also `man`/`woman`); vehicles → `car`/`truck`/`bus`/`van`/`motorcycle`/`bicycle`;
   buildings are **image-dependent** — `building`/`skyscraper`/`office building`/`house`/`apartment`/`roof`
   appear, but so do `downtown`/`Prague Castle`/`sky tower`. **Design consequence (folded into §4/§E):** the
   open-vocab model ships with an **empty** `defaultLabelFilter` (show all) and the UI seeds class chips from
   **live-observed labels**; the "People + vehicles + buildings" preset is an opt-in chip-fill over a *set* of
   building-ish labels, not an enforced exact-match default (which would drop most buildings). Measured latency
   ~85–100ms/frame (~10–12 fps single-stream CPU), ~2× `yolo26n`.

Still to confirm during implementation:
2. **OpenVINO export feasibility for the seg-pf architecture** and imgsz coupling: confirm `yolo export
   format=openvino` supports `yoloe-*-seg-pf` and that the export imgsz can equal runtime `CV_IMGSZ`
   (the yolo11n export-imgsz-must-match caveat in `cv-service/MODULE.md` applies). If unsupported, ship
   `.pt` with the measured perf hit documented.
3. **Measured single-stream CPU fps** for `yoloe-26s-seg-pf` (and `-26n` fallback) on the target
   laptop — feeds the §F perf-realism copy and the roster "slower" hint; also decides whether `-26s`
   or `-26n` is the shipped primary.
