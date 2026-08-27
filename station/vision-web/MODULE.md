# vision-web

Angular 21 SPA (driving adapter): the whole product UI — operator cockpit, manager dashboard, tactical map, asset/device management, replay, CV training — talking only to vision-api's REST/SSE surface, built into vision-app's jar.

**Depends on:** nothing internal (HTTP only) · Angular 21 (`core|common|forms|router|cdk`), `hls.js`, `leaflet` (lazy, and see Gotchas), `rxjs` (WS/preload only) · dev/test: `@angular/cli|build`, `vitest`, `jsdom`
**Used by:** vision-app (packages `dist/` into `META-INF/resources`; vision-api ships no static resources)
**Build/test:** `./mvnw -B -pl station/vision-web clean install` · `-DskipWeb=true` skips Node+Angular entirely · tests `npm run test:ci` (**never** raw `npx vitest`, see Gotchas) · dev `npm start` (proxies `/api`, `/actuator`, `/legacy`, `/hls` → `:8080`)

## API surface

### `core/api/` — the only place wire shapes live
- `models.ts` (~3000 lines) — every DTO, mirroring Java 1:1. Change a Java DTO → fix here first, then `grep -rn` the field.
- `VisionApi` (~120 methods) — thin `firstValueFrom` wrappers over `HttpClient`; no component calls `fetch`/`HttpClient` directly.
- `api-error.ts#describeHttpError(err)` — the one place an HTTP failure becomes user text.

### `core/**` — stores (all `providedIn: 'root'` unless noted)
| Area | Types |
|---|---|
| fleet | `FleetStore` (devices+streams, 5s poll; `run()` is the toast boundary) |
| live | `LiveStore` — one SSE connection, topic subscribe/unsubscribe, append-only log w/ per-consumer cursors |
| telemetry / detections | `TelemetryStore`, `DetectionsStore` (`DETECTIONS_LIMIT=50` retained batches) |
| map-data | `LayersStore`, `MarksStore`, `DrawingsStore` + `layers-logic`/`mark-logic`/`drawings-logic`/`map-event-logic` |
| map / geofence / weather | `FleetMapStore`, `GeofenceStore`, `WeatherStore` |
| identity | `AuthStore`, `OrgStore`, `SettingsStore` |
| geo | `GeoStore` (visual-geo correction), `camera-geo/` (fixed-camera pose, pure) |
| rc | `RcInputService`, `VirtualRcInputService`, `RcSource`, `ManualControlClient`, `ControlActionDispatcher`, `ControlProfileStore` |
| events | `EventsStore` (`GET /api/events`), `SystemEventsStore`, `TracksStore`, `TrainingStore`, `SystemStatusStore` |
| shell/ui | `SidebarStore`, `ThemeStore`, `UiStore` (exclusive overlays), `GlobalOverlayStore`, `ToastService`, `PollScheduler`, `IdlePreload`, `LeafletWarmup`, `panel-state.ts` |
| pure-logic only (no store) | `audit/`, `after-action/`, `readiness/`, `roster/`, `activity/`, `command/`, `stream-info-logic.ts` |

### Routes (one lazy `loadComponent` chunk each; per-feature `*.routes.ts`)
`/fly` picker · `/fly/:assetId` cockpit · `/command` · `/wall` · `/live/:deviceId` · `/assets` · `/assets/:assetId` · `/assets/:assetId/readiness` · `/assets/:assetId/replay/:usageId` · `/replay` · `/devices` · `/add-source` · `/activity` · `/login` · `/org` · `/settings`, `/settings/detection` · `/debug`
Hubs: `/operate`(+`/preflight`, `/missions`) · `/monitor`(+`/alerts`, `/audit`, `/layouts`) · `/manage`(+`/roster`, `/reports`, `/categories`, `/controller`, `/system`, `/health`, `/firmware`, `/geo/regions`, `/training`, `/training/models`, `/training/:datasetId[/samples/:sampleId]`, `/training/jobs/:jobId`)
Redirects: `/map` → `/command`, `/warehouse` → `/assets` (both pages deleted; the stub keeps old bookmarks off the 404).

### `features/**`
- **fly/** — `DronePickerPage` + `CockpitPage`/`CockpitFacade`; `flight-command-panel`, `cv-control-panel` (live per-stream CV) + `cv-setup-modal`, `rc-monitor`, `virtual-control-surface`, `arm-confirm-dialog`, `fly-osd`, `failsafe-banner`, `geo-chip`, `marks-panel`, `diagnostics-card`. Pure: `fly-logic`, `stream-state-logic`, `cv-control-panel-logic`, `flight-command-panel-logic`, `rc-monitor-logic`.
- **command/** — `CommandPage`/`CommandFacade` over `TacticalMap`; `asset-panel`, `marks-panel`, `zones-panel`, `setup-checklist`, `geofence-zone-dialog`. Pure: `command-logic`.
- **assets/devices/asset-detail/** — two-pane `?sel=<id>` lists (`AssetsFacade`/`DevicesFacade`); `AssetDetailPage` is the manager view (KPI stats fetched independently of `load()`), cockpit reached via `SettingsStore.flyAssetId` + `/fly`.
- **onboarding/** — `WIZARD_STEPS = profile→connect→test→verify→create→assign`; `simulate` skips test+verify; `assign` is terminal. `drone-config-logic#linkCompatibility` → `full`/`monitor-only`/`no-go`.
- **replay/** — `ReplayLibraryPage` (`/replay`) and `ReplayPage` (routed, *and* embedded non-routed for the `?asset=&usage=&t=` deep link); `AfterActionPanel` renders all six evidence parts always, in order.
- **camera-geo/, geo/, labeling/, models/, training-jobs/, audit/, alerts/, roster/, org-settings/, reports/, readiness/, system-status/, categories/, controller/, preflight/, activity/, debug/, demo/, hubs/, auth/, not-found/**.

### `shared/`
- **player/** — `Player` (`<vision-player>`): inputs `src`, `whepUrl`, `suspended`, `stopped`, `compact`, `detections`, `boxesMode`, `lockedTrackId`, `hoveredClass`; outputs `latencyChanged`, `transportChanged`, `trackFollowed`. `BoxesMode = 'all'|'priority'|'locked'|'off'`. Siblings: `player-recovery.ts` (pure `(state,action)=>state` reducers), `live-edge-logic`, `detection-overlay-logic` (letterbox math, hues, trails, sticky-label election), `detections-strip`, `stream-info-panel`, `sample-box-editor`, `webrtc-ice-restart`, `webrtc-certificate*`.
- **map/** — `TacticalMap` (the one map: layers, marks, drawings, affiliation symbology, follow mode), `map-controls/` (`mark-palette`, `verify-controls`, `drawing-toolbar`, `layer-manager`), `fleet-plan-dialog/`, `tile-cache/leaflet-loader.ts` (`MAP_LAYERS`, IndexedDB tile cache, `ensureLeafletStylesheet`).
- **ui/** — `AppSidebar`, `PageBar`, `TwoPane`, `SidePanel`, `ConfirmDialog`, `KebabMenu`, `Icon`/`IconButton`/`icon-registry`, `EmptyState`, `Notice`, `Stat`, `SectionHeader`, `EventRow`/`EventsRail`/`SystemEventRow`, `NotificationBell`, `IdentityChip`, `ToastHost`, `UndoToast`(+service), `WeatherChip`, `PreflightChecklist`, `ReturnHomeButton`.

## Conventions

- Standalone components, `OnPush`, signals for all state, **zoneless** (no `zone.js`).
- **Three files per component** — `.ts`/`.html`/`.css`. Never inline `template`/`styles`; split any component you touch.
- **Component → Facade → Store → Service.** Every *routed* feature has a `<feature>-facade.ts` (`@Injectable()`, in the page's own `providers`) owning all injection, `computed()` read-models (incl. every `canX`/disabled predicate) and HTTP commands. The page injects **only its facade** (+ host-owned `UiStore`) — enforced by `core/ui/architecture.spec.ts`, which globs `features/**` only. Non-routed presentational children may inject a shared store directly.
- Mutually-exclusive overlays go through `UiStore`, never independent booleans.
- HTTP errors are reported **once**, by the layer that knows what the user attempted (`FleetStore.run()`) — never an interceptor. Background polls degrade silently (no toast).
- **Pure logic in `*-logic.ts`, unit-tested; components are not.** No `*-facade.spec.ts` exists anywhere — facades are covered through their logic + `tsc` + the architecture guard. Component `TestBed` specs only for wiring bugs a pure spec cannot reach.
- Single-consumer logic stays feature-local; it moves to `core/` **only when a second consumer needs it** (re-exported from the old home for existing imports).
- **Never paraphrase a server-supplied failure reason** — render it verbatim (calibration `solved:false`, geo `NO_FIX`, onboarding verify).
- **Absent ≠ empty.** A disabled feature renders *nothing*; an enabled one with no data renders a distinct empty/dim state. Prefer enforcing it in the type (`GeoChipTone` has no `danger` member, so "never red for PROBABLE" is structural).
- Never optimistic about a *claim*: "Following #N" comes only from the server's `lockedTrackId` poll. A segmented control echoing the operator's own click is ordinary feedback, not a claim — and is still re-synced from the server after.
- **Marks are not draggable.** A mark records where something *was observed*; a drag would silently rewrite it. No `(markMoved)`, no `MarksStore.moveTo`. `PATCH /api/map/marks/{id}` still accepts coordinates (generic patch machinery) — nothing here sends them. Flight-plan waypoints and geofence vertices *do* drag; they run their own Leaflet maps.
- Styling: two-tier tokens only, raw hex confined to TIER 1 in `styles.css` (exception: canvas/Leaflet draw colors, which can't read `var()`). Light default; `.surface-dark` marks video enclaves. Selection language everywhere: 2px `--color-info` left bar + `--color-info-soft` fill. Classification → muted text, state → one chip. See `.claude/skills/frontend-style`.
- Diagnostic logging: plain `console.*` with a per-file `LOG_PREFIX` (`[player]`, `[fleet]`, `[fly]`) — no logging service. One line per state transition, never per tick. `Player`'s reducer dispatches log `[action] <event> → phase X→Y` only when state actually changed.

## Gotchas

- **`ng test` ≠ `vitest run`.** Specs need the Angular CLI's zoneless `TestBed` bootstrap; raw `npx vitest run` fails hundreds of specs with `TestBed.initTestEnvironment()`/`localStorage is not defined`. Use `npm run test:ci`.
- **Signals compare with `Object.is`, and a poll hands you a fresh object every tick.** Any `effect()` whose job is "call a store method when an id changes" must derive that id as a **primitive** and compare it against the last id *it itself* acted on. Ignoring this produced a 50–90×/sec `TelemetryStore.track()` loop (a real PATCH storm: 721 requests in 11.5s) and a tab-freezing reattach loop in `Player`. Guards: `telemetry-logic#trackingIdChanged`, `Player#lastAttachKey`, `TelemetryStore/DetectionsStore#lastTrackKey`.
- **A `linkedSignal` that re-seeds off a whole object destroys in-progress user input.** Stores replace their whole list on every SSE fold and 30s poll, so an operator mid-typing lost keystrokes. Use the `{source, computation}` form keyed on a primitive id: `(id, previous) => previous?.source === id ? previous.value : fresh()`. Both closures are tracked reactively (verified against Angular's runtime, not the typings).
- **A `localStorage` read inside a `computed()`/`effect()` is invisible to reactivity** — dependency tracking sees signal reads only. A helper that looks pure (two exported functions, no class) is exactly the shape that forgets this; it needs a module-level `signal` seeded from storage at load, written *before* the persist call. See `leaflet-loader#explicitMapLayer`.
- **`inject()` inside an `afterNextRender` callback throws NG0203** on every route. Inject in the constructor's context, capture the instance, use it in the callback.
- **A route param name must match the page's `input()` name exactly.** `assets/:id` + `input.required<string>('assetId')` made `withComponentInputBinding()` set `undefined`, which `HttpClient` cheerfully serialized to `GET /api/assets/undefined` — the page was fully broken, not degraded. Path is `assets/:assetId`.
- **Never assume an `await`ed WebRTC promise's continuation runs before a related event.** `pc.ontrack` fires *during* `setRemoteDescription`, so a no-track timeout armed after the `await` fired unconditionally 6s later against healthy streams — every attach. Re-check observable state (`whepTrackArrived`) at both arm and fire.
- **`navigator.sendBeacon` cannot send a WHEP `DELETE`** (it only ever POSTs; mediamtx's session resource requires the real verb). Teardown uses `fetch(url, {method:'DELETE', keepalive:true})`.
- **`webrtc-ice-restart.ts` was derived from mediamtx's Go source at tag `v1.19.2`, not the IETF drafts.** mediamtx's own bundled reference client doesn't implement ICE-restart-via-PATCH at all, so there's nothing upstream to diff against — re-verify against the server's request handling on any tag bump.
- **The WHEP→HLS fallback is load-bearing, not an edge case.** Dockerized mediamtx advertises `127.0.0.1` ICE candidates unless `VISION_WEBRTC_HOST` is set, so every LAN viewer silently lands on HLS. Don't "fix" an HLS transport chip before checking that env var.
- **`proxy.conf.json` must carry `/hls`** — without it `ng serve` answers the playlist request with `index.html` (HTTP 200, `text/html`), which looks exactly like a broken player. `Player` now detects this shape and says so. **Restart `ng serve` after editing the proxy** — it's read once at startup.
- **Snap-to-live never triggers for WHEP** (`Player#behindLive` is pinned `0` — there's no seekable buffer). Overlay/detection sync uses `overlaySyncLatencySeconds` (which substitutes the measured WHEP latency) instead. The behind-live chip uses two-threshold hysteresis (show ≥4s, hide <2s) so a jittering per-second measurement doesn't flicker it.
- **`stopped` is absorbing and checked first in `reattach()`** — once true nothing attaches, whatever `src`/`whepUrl` say. This is what makes an explicit Stop hold regardless of backend flapping (the `/live` "stop freezes the app" bug was the frontend amplifying a backend problem, not merely suffering it).
- **The detection overlay resolves a model key from `Detection.label`'s prefix, never `Detection.modelId`** — every detection in a composite batch carries the same comma-joined composite id, so `modelId` cannot distinguish members even though it's on the wire.
- **HiDPI:** `canvasBackingSize` + `ctx.setTransform(dpr,…)` scale the *backing store* only; the CSS box stays `clientWidth/Height` so all hit-testing stays in CSS-pixel space. Redraw is driven by `requestVideoFrameCallback` where available, a 200ms interval otherwise, plus a `ResizeObserver` on the `<video>`.
- **`Number('') === 0`, not `NaN`.** Every numeric draft form must parse via `Number(raw.trim() === '' ? NaN : raw)` or a blank field silently validates as zero.
- **Re-entrancy flag idiom**: set a flag immediately before a programmatic write, check-and-clear it in the listener that write would otherwise re-trigger (`replay#videoDrivenUpdate`, `TacticalMap`'s auto-fit suppression). Used wherever a control and its subject drive each other.
- **`GeoStore.disabled` is sticky within a `track()` session** and clears only on `track()`/`reset()` — a transient network blip must never read as "the feature is off".
- **Leaflet:** its CSS is a hand-maintained copy at `public/leaflet/leaflet.css` injected as a runtime `<link>` (importing it would trip the 8/10 kB `anyComponentStyle` budget); `::ng-deep` is required for Leaflet-owned DOM (Angular never stamped `_ngcontent-*` on it); `allowedCommonJsDependencies: ["leaflet"]` silences UMD warnings. **`leaflet` sits in `devDependencies` despite being imported at runtime** — fine for the bundled build, fatal for `npm ci --omit=dev`.
- **Tile-cache `fetch()` reads need CORS**; all four current providers send `Access-Control-Allow-Origin: *`. A fifth layer whose host doesn't still renders (falls back to plain `<img src>`) but silently never caches.
- **`PollScheduler`'s heartbeat is 1s** — register only multiples of 1000 ms (all current callers use 1s/2s/5s).
- **`JpaTelemetryRepository#findByUsage` returns the *first* `limit` samples, not the latest** (deliberately mirroring the old in-memory semantics). At 1 Hz a usage older than ~200s makes `?limit=200` return a frozen earliest window forever — the OSD's staleness indicator is telling the truth. Backend behavior, not a frontend bug.
- **Dev-parity trap:** with `vision.auth.enabled=false` the synthetic principal's `Root` group id (`UUID(0,1)`) does not match seeded users' real random-UUID Root group, so a pilot picker legitimately finds zero matches. That's an honest empty state, not a bug.
- **`shared/player/sample-box-editor-logic.ts` deliberately forks `player.ts`'s letterbox math** rather than importing it — the live player's hot render path is read-only and stays simple.
- **`flight-state-logic.ts#derivePreflight`'s GPS-fix/Battery rows are vehicle-kind-aware** (FLEET-RADIO-PLAN.md F14/R4c): `vehicleKind === 'ROVER'` is the *only* carve-out (a rover/boat drives with no GPS fix at all; its low-battery bar is a separate, lower, named-table entry — `BATTERY_LOW_PERCENT_BY_KIND`). Every other kind, **and `undefined`** (capabilities not loaded yet, or the fetch failed), falls through to the original copter-shaped rule — an unresolved kind never silently inherits the rover's laxer treatment. `cockpit-facade.ts` threads `this.capabilities()?.vehicleKind` through; no other call site of `derivePreflight` exists.
- **Preflight thresholds are a per-kind table in the logic layer, not a client-config read** — FLEET-RADIO-PLAN.md's D7 wants server-sourced UI thresholds, but no client-config endpoint exists anywhere in this codebase (nothing serves UI thresholds from `application.yaml`); building one is properties + a controller + a client store across three modules, deferred as its own wave. The rover's 25% battery bar is a reasoned placeholder (documented in `flight-state-logic.ts`'s own comment), not a measured constant.

## Status

Everything listed under API surface is implemented and covered by the suite (~138 spec files). Notable non-implemented / conditional surfaces:

- **Deleted, kept only as redirect stubs:** `features/warehouse/**` (→ `/assets`), `features/map/**` (→ `/command`). `shared/map/live-map/**` and `fleet-map/**` are gone entirely — `TacticalMap` replaced both.
- **Drawing vertex-dragging** is not implemented (`DrawingsStore.setGeometry` is the ready seam); label/colour/delete on a selected drawing work.
- **Point/box target lock** — `TargetLockRequest.pointX/pointY` exist on the DTO; nothing populates them. Click-to-follow locks by `trackId` only.
- **Verify-cadence / follow-fps sliders** are local draft state — the wire carries no readback for them.
- **Tracking config is live-only**: `StartStreamRequest` has no `tracking` object, so a starting mode can't be requested, only PATCHed after start.
- **Feature-flagged off by default server-side** — the UI is built and degrades honestly (endpoints answer 409 naming the flag): `vision.training.enabled`, `vision.geo.fixed-camera.enabled`, `vision.geo.visual.enabled`.
- Pre-existing, unfixed: the initial bundle sits a few kB over its 390 kB budget **warning** (error threshold 440 kB); canvas/Leaflet draw colors don't react to a theme flip without a `getComputedStyle` + re-style mechanism nobody has built.
