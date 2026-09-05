# CV-CLEAN-FEED-PLAN — two streams, one overlay, an honest filter

Status: **MERGED to master 2026-08-22** (carried inside `2484d6ab` — track-identity branched off this branch; verified `git merge-base --is-ancestor`, 2026-09-04). Originally authored 2026-08-20. Owner decisions this plan executes:
1. **Remove burn-in entirely** — video and detections become two separate streams; the video is
   always clean pixels, detections are always data, and every rendering choice is client-side and
   customizable.
2. **Compact the detection/tracking surfaces** — one data feed, one Vision drawer, one declutter
   control, instead of today's parallel pollers and sibling drawers.
3. **Fix the object filter** — today's allowlist semantics are bad UX (hiding one class freezes
   the visible vocabulary); replace the operator-facing act with a deny-list.

Grounding: `docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md` (D1–D9, the render model) and
`docs/plans/done/CV-UX-RESEARCH.md` (§4 filter diagnosis, §9 candidates). This plan deliberately
**unfreezes** parts of the CV-CONTROL wire contract; §2 below is the new frozen contract for all
waves. App + SPA ship in one jar, so wire removals land atomically in one release.

## 1. Decisions and their verified consequences

### D-1: burn-in is removed, not defaulted off

The `adapter-overlay` module (Java2D box + OSD burn-in), the `OverlayPort` domain port, the
`overlayBurnIn`/`overlayTelemetry` config fields, and the `burnedIn` wire fields are **deleted**.
Rationale: the SPA is the product; the client canvas overlay is crisper (HiDPI), interactive
(hover/click-to-follow), and customizable (tiers/declutter), while burn-in costs a JPEG
decode + annotate + re-encode **per published frame per stream** and produces the double-draw
defect (research D1). Verified consequences, each named honestly:

- **External players (VLC/HLS embeds) lose boxes and the burned OSD.** Accepted: they keep clean
  video; detection consumers use the SPA. (`StreamViewerLinks` copy must stop implying boxes.)
- **Replay/recorded video loses baked boxes.** Replay never had a client overlay
  (`features/replay/` renders density buckets + counts, no box canvas); detections are persisted,
  so nothing is lost — a replay box-overlay is future work (§6.1), not this plan.
- **Training capture simplifies.** `StreamPipeline` keeps a pre-overlay `latestFrame` split
  *because* burn-in would poison training pixels (`StreamPipeline.java:357-366`); with no overlay
  the published frame IS clean, and the split's doc comment gets updated (mechanism may stay).
- **INVARIANT — the telemetry supplier survives.** `CameraPose`/ego-motion compensation rides the
  same telemetry supplier the OSD originally consumed, and it already shipped dead once when its
  construction was gated on `OverlayPort != null` (tracking-engine post-mortem). Removing
  `OverlayPort` must not remove or re-gate the supplier: `DefaultStreamService` keeps building it
  unconditionally. A test must assert poses still flow with no overlay anywhere in the wiring.
- The client-side OSD (`features/fly/fly-osd`) already covers the operator's telemetry readout.

### D-2: the object filter becomes a deny-list

Today `labelFilter` is an **allowlist enforced pre-fan-out** (screen + alerts + recording;
`StreamPipeline#onDetectionResult`), and empty means "all" — so the everyday act ("stop boxing
trees") forces the client to enumerate the observed-so-far complement, silently hiding classes
never yet seen (CV-UX-RESEARCH §4.4). Fix on the wire:

- `labelDenyFilter: string[]` joins `labelFilter` on `PipelineConfig`, the PATCH body, and the
  start-stream options. Semantics: a detection is dropped when its label fails the allowlist
  (when non-empty — unchanged, still the model-intent seed) **or** matches the deny-list.
  Enforcement in the same single place `labelFilter` already drops, so screen/alerts/recording
  stay consistent — the drop-everywhere behavior is kept and stated, not hidden.
- Operator UX: "hide this class" writes **one entry to the deny-list** and never touches the
  allowlist. New classes keep appearing. The trap is gone, not documented around.

### D-3: compaction

- **One drawer.** The `cv` (settings) and `detections` (strip) drawers merge into one **Vision**
  drawer: strip content on top (what is being seen — now interactive, §5 W5), settings below
  (tiered per CV-UX-RESEARCH §3). Two of seven cockpit drawers become one (UX-SIMPLIFY F4).
- **One feed owner.** The panel's private `GET /streams/{id}/tracks` poller and `DetectionsStore`'s
  detections poll/SSE merge into one client store owning both cadences (the code already gestures
  at this — `cv-control-panel.ts:69-77`). Wire unchanged in this plan; folding tracks into the
  SSE topic stays a candidate (§6.2).
- **One density control.** `BoxesMode` (`overlay|burned|off`) collapses to declutter levels
  (`all|priority|locked|off`) — `'burned'` has no referent once D-1 lands.

## 2. Wire contract (frozen for this plan's waves)

Removals (atomic with the SPA update, same jar):
- `ActiveStream`/`StartStreamResult`/stream DTOs: **`burnedIn` removed**.
- `PipelineConfig`/PATCH/start options: **`overlayBurnIn`, `overlayTelemetry` removed**.
- `vision.overlay.*` config keys and `VisionOverlayProperties` removed.

Additions:
- `PipelineConfig.labelDenyFilter: List<String>` (default empty) + same field on
  `PATCH /api/streams/{id}/config` and start-stream options. Patch semantics mirror
  `labelFilter`'s (absent = unchanged, present = replace).

Unchanged: `GET /api/streams/{id}/detections`, SSE `detections:<assetId>`,
`GET /api/streams/{id}/tracks` (incl. `rate`/`latency`/lock echo), `GET /api/cv/models`,
tracking lock PATCH.

## 3. Wave plan

Branch: `feat/cv-clean-feed`. Backend waves and web waves are file-disjoint and run in parallel;
web waves serialize among themselves (shared files). Every wave: scoped build green
(`-pl` + `-am` where cross-module, foreground — never backgrounded) + MODULE.md updated.

### W1 — backend: delete the burn path *(+ W2 folded in: deny-list)* — agent: backend, scope: Java only

1. `contexts/vision-perception`: remove `OverlayPort` and every overlay/burn-in path from
   `StreamPipeline` (the `overlayIfNeeded` stage publishes the input frame unchanged; delete
   overlay failure latches); `PipelineConfig` drops `overlayBurnIn`/`overlayTelemetry`, gains
   `labelDenyFilter` (compact-ctor validated, defaults empty; convenience-ctor chain rebuilt —
   respect DTO rule 6: no silent field-dropping ctors); `PipelineConfigPatch` + merge logic
   updated; `ActiveStream` drops `burnedIn`; `DefaultStreamService` drops the `OverlayPort`
   collaborator **but keeps the telemetry supplier unconditional (D-1 invariant, with a test)**;
   deny-list enforced beside the allowlist in the one `onDetectionResult` drop site.
2. `station/vision-api`: DTOs drop `burnedIn`, gain `labelDenyFilter` on config/start;
   `StreamController`/`AssetStreamController`/`StreamViewerLinks` updated (viewer-links copy stops
   promising boxes).
3. `station/vision-app`: `PublishWiring#overlayRenderer` bean + `VisionOverlayProperties` deleted;
   `ApplicationServiceWiring` constructor threading shrinks; `application.yaml` loses
   `vision.overlay.*`; E2E tests (`CvDetectionE2ETest`, `CvDetectionEndpointE2ETest`,
   `TrackingAssociateE2ETest`, `StreamControllerTest`) updated.
4. Delete `video-output/overlay/` module + root `pom.xml` entry + its MODULE.md; update the
   module index in `CLAUDE.md`/`ARCHITECTURE.md` references.
5. Build: install perception first, then `-pl station/vision-api,station/vision-app -am` (stale
   ~/.m2 trap). MODULE.md: perception, vision-api, vision-app.

### W3 — web: burned mode out, overlay default in — agent: web-ui, scope: vision-web only

`BoxesMode` loses `'burned'` (type, cycles, `resolveBurnedIn`/`boxesModeCycle`/`defaultBoxesMode`
deleted or reduced), `models.ts` drops `burnedIn`, the 7 consumer files updated (`cockpit-facade`,
`fly-logic`, `live-facade`, `wall-tile`, `cv-control-panel`, `detection-overlay-logic`,
`player.ts`); `'overlay'` becomes the default everywhere; `B`-key + panel copy updated; staleness
fade + cutoff + "detections paused — last seen N s" stage notice (research §3.4). Tests + MODULE.md.

### W4 — web: priority tiers + label collision-yield + declutter levels

Research §3.2/§3.3/§3.6 exactly: tier computation + tiered rendering (T0 lock/hover emphasis,
lock-dims-rest, T2 thin/no-label, T3 dots, trails T0-only, class-bucket hues), greedy
priority-ordered label placement with hysteresis + cap, `B` cycles All/Priority/Locked-only/Off.
Pure functions in `detection-overlay-logic.ts` with specs. After W3 (same files).

### W5 — web: the Vision drawer + honest filter UX + one feed owner

- Merge `cv` + `detections` drawers into one Vision drawer (cockpit rail entry count drops by one;
  gates/shortcuts preserved).
- Strip becomes interactive: chips gain counts (`person ×3`), hover-highlights the class's boxes,
  **click hides via `labelDenyFilter`** (W1's wire) with the honest drop-everywhere copy; "Seen
  now" chips at rest per CV-UX-RESEARCH §4 (that plan's U4, implemented here against the fixed
  wire — no allowlist-complement trap, no first-hide hint needed).
- One `VisionFeedStore` owns both the detections feed and the tracks poll; the panel consumes it
  (its private poller deleted).
After W4 (same files as W3/W4 plus strip/store).

### W6 — verify + close

Full scoped verify chain, live click-through on `/fly` (the lesson: run the real path), measured
before/after: published-frame CPU (burn-in gone), overlay draw stats. Update
memory and mark research docs' overtaken sections
(CV-UX-RESEARCH §9.1 → done here; U-waves that W5 subsumes).

## 4. What this plan does NOT do

- No replay box-overlay (future, §6.1). No SSE topic for tracks (§6.2). No alert-priority wire
  flag (§6.3). No panel-tier restructure beyond what W5's merge needs (CV-UX-RESEARCH U1–U3
  remain their own effort). No change to click = follow.

## 6. Future candidates (explicitly out)

1. **Replay box overlay** — persisted detections re-drawn over recorded video with the same tier
   renderer; needs a time-sync story against recording timestamps.
2. **Tracks/rate folded into the detections SSE topic** — removes the last poll on `/fly`.
3. **`alerting` flag per detection on the wire** — event-engine-driven T1 promotion.
4. **Deleting `labelFilter` allowlist from the operator UI entirely** — after intent cards
   (CV-UX-RESEARCH U2) own model seeding, the allowlist may become invisible plumbing.

## 7. W7 — client-side box extrapolation (defect found by W6 live smoke)

**Defect (2026-08-20, owner-observed):** with burn-in gone, boxes visibly trail moving objects —
"the flow of detections is much slower than video". Root cause: the deleted burn-in path called
`DetectionExtrapolator.at(frame.capturedAt())` per published frame, velocity-projecting every box
onto the exact frame being encoded; the client overlay draws raw batches and only *selects* by
video latency, so whenever video latency < detection arrival lag (WHEP especially; 2 s poll
fallback worst) the newest batch on hand is inherently behind the picture. W1 removed the server's
catching-up without replacing it client-side.

**Fix (pure frontend, wire frozen):** port forward-projection into
`shared/player/detection-overlay-logic.ts`, mirroring the server's semantics and tuning
(`StreamPipelineSettings` defaults, `application.yaml` `extrapolation:` block):
- `extrapolateDetections(selected, previous, targetMs, maxMs, gate)` — match by track id when both
  sides carry tracks, else same-label nearest normalized-center within gate **0.15**; project each
  matched box's center by its per-pair velocity, capped at **800 ms** past `selected.capturedAt`
  (then freeze, exactly the server's freeze rule); unmatched boxes draw raw; centers clamp to
  [0,1]; box size unchanged (the server projected centers only).
- `redrawOverlay` projects the selected batch to the on-screen instant
  (`now − overlaySyncLatency`), taking the predecessor batch from the already-held results list;
  the per-video-frame redraw loop animates it. `drawnBoxes` gets projected geometry, so
  hover/click-to-follow stay consistent with what is painted.
- Constants exported + cited to the server defaults so the two ends cannot silently diverge.
- Known bound, stated: with the 2 s poll fallback (no SSE), batches can be older than the 800 ms
  cap — boxes freeze at the cap rather than invent motion; §6.2 (tracks/detections into SSE)
  remains the real fix for poll staleness.

Specs for the pure functions (track match, gate match, cap/freeze, no-predecessor → raw,
degenerate dt → raw, clamping); `npm run test:ci` + `tsc` + prod build green.
