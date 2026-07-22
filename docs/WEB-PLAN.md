# Web UI Plan — Angular SPA (`vision-web`)

Companion to [ARCHITECTURE.md](../ARCHITECTURE.md) and [UX-DESIGN.md](UX-DESIGN.md). Delivers the product surfaces of the UX design as a single-page Angular application with tabbed top-level navigation, built and shipped as part of the Maven reactor.

**Milestone:** `./mvnw verify && docker compose up` serves a real product UI at `http://localhost:8080` — connect a device, watch it live, tune it — with the Phase-1 dev console demoted to a debug tab.

---

## 0. Decisions

1. **Angular 21** (`21.2.x` LTS), standalone components, **signals** for all component state, **zoneless** change detection, `OnPush` everywhere. No NgModules, no RxJS-heavy state layer — `httpResource`/signals cover this app's needs; RxJS only where it earns its place (WebSocket streams, debounced input).
2. **SPA with tabs.** One app shell holding a persistent tab bar; each tab is a **lazily-loaded route** (`loadComponent`), so navigation is instant and each page is its own chunk. Critically, client-side routing keeps the HLS player and (later) the detection WebSocket alive across tab switches — a full-document MPA would restart a ~6 s HLS buffer on every navigation ([UX-DESIGN §2 T1](UX-DESIGN.md)).
3. **`vision-web` is a Maven module.** `frontend-maven-plugin` provisions a pinned Node, runs the production build, and emits into `target/classes/META-INF/resources/`; Spring Boot serves it automatically from the classpath. `vision-app` takes the dependency, so the product stays **one artifact** — self-hosted software that needs a separate frontend deploy loses this audience. Dev loop is `ng serve` with a proxy to `:8080`, so hot reload is unaffected.
4. **Custom design system on Angular CDK.** Design tokens + ~10 hand-built components, using CDK only for overlay/dialog/a11y/virtual-scroll primitives. Keeps the existing dark ops-console aesthetic and the bundle small; avoids reading as a generic Material admin panel.
5. **Typed API client mirrors the Java DTOs exactly.** One `api/` layer with interfaces matching `DeviceResponse`, `ActiveStreamResponse`, `ScanResultResponse`, `ErrorResponse`, etc. No component ever calls `fetch` directly. Failures funnel through `describeHttpError()` (pure, unit-tested) and are reported once by `FleetStore.run()` — deliberately *not* an HTTP interceptor, so the layer that knows what the user was attempting is the layer that reports it, and a single failure never produces two toasts.
6. **No tab without a backend.** Tabs appear as their phase lands; we do not ship empty "coming soon" pages. Order below tracks the roadmap.

### Tabs

| Tab | Route | Lands in | Backed by |
|---|---|---|---|
| **Wall** | `/wall` *(default)* | W3 | `GET /api/streams`, `GET /api/devices` |
| **Devices** | `/devices` | W2 | `GET/POST /api/devices`, `POST /api/discovery/scan` |
| **Settings** | `/settings` | W4 | client-side profiles + `GET /actuator/*` |
| **Debug** | `/debug` | W5 | raw API console (replaces today's page) |
| *Events* | `/events` | Phase 2 | *(not in this plan)* |
| *Studio* | `/studio` | Phase 3 | *(not in this plan)* |

Non-tab routes: `/live/:deviceId` (single-device cockpit, reached from Wall/Devices), `/devices/connect` (wizard).

---

## 1. W0 — Workspace, shell, and the Maven seam

**Scope:** new `vision-web/` (Angular workspace + `pom.xml`), root `pom.xml` (module entry), `vision-app/pom.xml` (dependency).

- `ng new vision-web` — Angular 21, CSS, no SSR, zoneless.
- `pom.xml` using `frontend-maven-plugin`: pinned Node/npm, `npm ci`, `npm run build -- --configuration production`, output `target/classes/META-INF/resources/`. A `-DskipWeb` property short-circuits the frontend build for fast backend-only cycles; CI runs the full path.
- `proxy.conf.json` → `/api` and `/actuator` to `http://localhost:8080` for `ng serve`.
- App shell: header (product mark, environment badge, health dot), tab bar, `<router-outlet>`, toast host. Router configured with `withComponentInputBinding()` and a custom preloading strategy (idle-time preload of adjacent tabs).
- Design tokens (`styles/_tokens.css`) lifted from the existing console's palette so the visual language carries over: `--bg`, `--panel`, `--accent`, `--ok`, `--danger`, radii, spacing scale, type scale.
- `README` section on the dev loop.

**Done when:** `./mvnw -pl vision-web package` produces the jar with static assets inside; `ng serve` shows the shell with working tab navigation; `./mvnw -DskipWeb verify` still green.

## 2. W1 — API client, error handling, notifications

**Scope:** `vision-web/src/app/core/**`.

- `api/models.ts` — interfaces mirroring every DTO in `com.drones.vision.api.dto`, plus the `Capability` union mirroring the domain enum. (`DeviceType` was removed server-side — categories are asset-level data now, not a device-level enum.)
- `api/vision-api.ts` — one typed injectable over all three controller areas, promise-returning so components hold signals rather than subscriptions.
- `api-error.ts` — maps a failure to a sentence; **specificity is the point** (`0 → "cannot reach the backend, is the app running on :8080?"`, `409 → "is the stream already running?"`), never "request failed". Domain messages from `ApiExceptionHandler` win over generic text, because the domain knows why it rejected a URI.
- `fleet-store.ts` — the app's single poller and single source of truth for devices + streams, so Wall/Devices/Live cannot disagree.
- `toast` service + host component (replacing the console's `#toast-area`).
- Backend reachability derived from the existing poll — no extra endpoint, no actuator dependency.
- Unit tests for the error mapping table and the API service against `HttpTestingController`.

**Done when:** `npm test` green; every backend error code has a mapped message with a test.

## 3. W2 — Devices tab *(MVP core)*

**Scope:** `vision-web/src/app/pages/devices/**`.

Reaches parity with today's console, then passes it:

- **Inventory**: device cards/table — name, protocol, URI, capability chips, live/stopped state (joined against `GET /api/streams`), actions: *Watch* · *Start* · *Stop*.
- **Add device**: form with name, protocol/URI, and a Tier-3 **options** key/value editor (`StreamDescriptor.options`) behind advanced mode. No type selector — `DeviceType` no longer exists.
- **Scan**: timeout selector → `POST /api/discovery/scan`; results grouped by method, each row one-click **Use** → prefills and highlights the add form (carrying `suggestedCategory`/`protocol`/`uri`); `failedMethods` surfaced honestly rather than silently dropped.
- Empty states that teach: no devices → "Register the built-in `sim` source to try it without hardware" with a one-click button.

**Done when:** every current console capability works in the new UI, plus advanced options and honest scan-failure reporting.

## 4. W3 — Live view and Wall

**Scope:** `vision-web/src/app/pages/live/**`, `pages/wall/**`, `shared/player/**`.

- **`<vision-player>`**: HLS via `hls.js`, loaded through `@defer` so its ~90 KB never touches the initial bundle; native HLS path on Safari; retry/backoff with an explicit "waiting for first segment" state instead of a black rectangle.
- **Latency badge**: measured playback lag vs. wall clock, shown continuously (UX-DESIGN T1) with the transport named (`HLS ≈ 6 s`).
- **`/live/:deviceId`**: big player, start/stop, snapshot, stream metadata, and a quick-settings drawer for confidence/inference FPS (`StartStreamRequest`).
- **`/wall`**: responsive grid of active streams, density control (2×2 … 6×6), tiles pause playback when off-screen via `IntersectionObserver` — a 30-camera wall must not melt the browser.
- Capability-driven panels scaffolded (`TELEMETRY` → OSD slot, `PTZ` → control slot) rendering nothing until Phase 4 fills them.

**Done when:** registering a `sim` device and pressing Watch plays video in both `/live/:id` and `/wall`, with a moving latency badge.

## 5. W4 — Settings and pipeline profiles

**Scope:** `vision-web/src/app/pages/settings/**`, `core/profiles/**`.

Implements the three-tier disclosure model of [UX-DESIGN §4](UX-DESIGN.md):

- **Profiles**: named `PipelineProfile` (`Balanced` / `Low-latency` / `High-quality` + user-defined) wrapping `confidenceThreshold` + `inferenceFps` today, extensible to the full `PipelineConfig` as the API grows. Persisted client-side now; server-side when an endpoint exists.
- Preset picker shows **what it changed**; editing a field flips the badge to `Custom (based on …)` with *Revert* and *Save as new* always visible.
- **Advanced mode** is an account-level toggle stored in local settings — flip once, stays on; gates every Tier-3 control app-wide.
- Appearance (theme, grid density defaults), and a system panel (versions, mediamtx endpoints, health detail).

**Done when:** starting a stream applies the selected profile's values; advanced mode reveals Tier-3 controls consistently across Devices and Live.

## 6. W5 — Debug tab and console retirement

**Scope:** `vision-web/src/app/pages/debug/**`, `vision-api/src/main/resources/static/**`.

- Raw API console: endpoint picker, request body editor, response viewer with timings — the thing this audience actually wants when an adapter misbehaves.
- Adapter/port health list; last scan result in raw JSON.
- Delete the legacy `static/index.html` + `app.js` once parity is confirmed (they are fully superseded by Devices + Debug).

**Done when:** legacy console removed, no functionality lost.

## 7. W6 — Optimization pass

**Scope:** `vision-web/**` build config and hot paths.

Named explicitly so it does not get skipped:

- **Bundle budgets** in `angular.json` that *fail the build*: initial ≤ 250 KB, per-route chunk ≤ 120 KB (both gzipped).
- Verify real code-splitting: one chunk per tab, `hls.js` deferred, CDK pulled in only where used.
- **Zoneless + OnPush + signals** audit; no `setInterval`-driven re-render; polling via a single shared timer.
- **Virtual scroll** (CDK) on device/stream lists; `@for` with `track` everywhere.
- Off-screen player suspension; `IntersectionObserver` on wall tiles.
- Idle preloading of adjacent tabs; `preconnect` to the mediamtx origin.
- Long-cache hashed assets; `index.html` no-store so deploys take effect.
- Lighthouse pass on the Wall with 12 tiles; record numbers in the README.

**Done when:** budgets enforced in CI and the documented numbers are reproducible.

## 8. W7 — Hardening for daily use

**Scope:** cross-cutting.

- Keyboard navigation across tabs and grid; focus rings; CDK live-announcer on stream state changes.
- Responsive: phone = Wall + Live only (UX-DESIGN §7.6); tab bar collapses.
- Route-level error boundaries; offline/backend-down banner that names the cause.
- Component tests for the player state machine and profile logic; a Playwright smoke path (register sim → start → player renders) wired into CI.

**Done when:** `./mvnw verify` runs frontend unit tests, and the smoke path is green in GitHub Actions.

---

## 9. Build order summary

| Step | Ships | Usable outcome | Status |
|---|---|---|---|
| W0 | Shell + Maven seam | App loads, tabs navigate, one artifact | ✅ done |
| W1 | API + errors | Every failure is explained, not swallowed | ✅ done |
| **W2** | **Devices** | **MVP: console parity, better** | ✅ done |
| **W3** | **Live + Wall** | **Actually useful: connect and watch** | ✅ done |
| W4 | Settings/profiles | Tunable without editing properties files | ✅ done |
| W5 | Debug | Legacy console retired | ✅ done |
| W6 | Optimization | Fast on a 30-tile wall | partly — splitting + budgets in place |
| W7 | Hardening | Daily-driver quality | pending |

MVP is **W0–W3**. Everything after makes it a product rather than a demo.

### Measured after W4

Production build, `ng build`:

| Bundle | Raw | Transfer |
|---|---|---|
| Initial total | 278 kB | **78 kB** |
| `hls` (deferred, loads only when a player mounts) | 518 kB | 132 kB |
| `devices` route | 48 kB | 11 kB |
| `settings` / `live` / `wall` / `not-found` routes | 10 / 8 / 5 / 0.6 kB | ≤ 3 kB each |

The point of the split: a user who only opens Devices never downloads the video stack, which is
larger than the entire rest of the application. Budgets in `angular.json` fail the build at 360 kB
initial, ~30 % above the current figure — enough headroom for the Events tab, tight enough that an
accidental eager import of `hls.js` breaks CI rather than shipping.
