# vision-web

Angular SPA (driving adapter): the product UI — Wall/Devices/Live/Settings tabs — served by Spring Boot off the classpath. No Java; see docs/WEB-PLAN.md for the full plan.

**Depends on:** none internally (talks to vision-api's REST surface only, over HTTP) · Angular 21 (`@angular/core|common|forms|router|cdk`), `hls.js`, `rxjs` (only where it earns its place — WS/preload) · dev/test: `@angular/cli`, `@angular/build`, `vitest`, `jsdom`
**Used by:** vision-app (packages this module's `dist/` alongside the API jar; vision-api itself ships no static resources — see vision-api/MODULE.md Gotchas)
**Build/test:** `./mvnw -B -pl vision-web clean install` (npm ci + `ng build --configuration production` + `ng test --watch=false`, ~15s warm / longer on first Node provisioning). `-DskipWeb=true` on any reactor build skips Node provisioning and the Angular build entirely (fast backend-only cycles); `-DskipTests` skips just the Vitest run. Dev loop: `npm start` (`ng serve`, proxies `/api` and `/actuator` to `:8080` via `proxy.conf.json`). Tests: `npm run test:ci` (`ng test --watch=false`) — **not** raw `npx vitest run`, which bypasses the Angular CLI's test builder (zoneless TestBed setup, jsdom env) and fails everything.

## API surface

### `src/app/core/api/` — typed REST client (WEB-PLAN §0.5: mirrors Java DTOs exactly, no component calls `fetch` directly)

- `models.ts` — wire types 1:1 with `com.drones.vision.api.dto`: `Device` (mirrors `DeviceResponse`: id, name, capabilities, protocol, uri, options, state), `RegisterDeviceRequest`, `ActiveStream`, `StartStreamRequest`, `StartStreamResult`, `DiscoveredDevice` (mirrors `DiscoveredDeviceResponse`; `suggestedCategory?: string` is a category slug), `ScanRequest`, `ScanResult`, `ApiErrorBody`. `Capability = 'VIDEO'|'TELEMETRY'|'PTZ'|'AUDIO'`, `DeviceState = 'ACTIVE'|'DEACTIVATED'`. **No `DeviceType`/`type` field anywhere** — removed server-side; categories are asset-level data (`CategoryResponse`/`AssetSummaryResponse`), not mirrored here because no page consumes `/api/assets` or `/api/categories` yet.
- `vision-api.ts` — `VisionApi` (`@Injectable providedIn: 'root'`): `listDevices()`, `registerDevice(req)`, `listStreams()`, `startStream(deviceId, req?)`, `stopStream(streamId)`, `scan(req?)`. All promise-returning (`firstValueFrom`) so components hold signals, not subscriptions.
- `../api-error.ts` — `describeHttpError(error: unknown): string`, pure and unit-tested; maps status codes to specific sentences, prefers the backend's `ErrorResponse.message` when present.
- `../fleet-store.ts` — `FleetStore`: the app's single poller (5s, paused when tab hidden) and single source of truth for `devices`/`streams` signals, `liveDeviceIds`, `reachable`; `register/start/stop` funnel through `run()` so a failure produces exactly one toast.
- `../toast.service.ts` — `ToastService.ok/info/error(text)`, auto-dismissing signal-backed toast list; rendered by `ui/toast-host.ts`.
- `../settings-store.ts` — `SettingsStore`: built-in + custom `PipelineProfile`s, `effective()` (draft-over-profile), persisted to `localStorage['vision.settings.v1']`.
- `../idle-preload.ts` — `IdlePreload` (`PreloadingStrategy`): preloads lazy route chunks on browser idle; opt out via route `data: { preload: false }`.

### Routes (`app.routes.ts`) — one lazy `loadComponent` chunk each

`/wall` (default) · `/devices` · `/live/:deviceId` · `/settings` · `/debug` (`data: { preload: false }` — rarely visited, not worth an idle-preload slot) · `**` → not-found.

### Pages (`src/app/pages/**`) and shared UI (`src/app/ui/**`)

`wall/` (grid of live tiles, density control, `IntersectionObserver`-suspended off-screen players) · `devices/` (inventory table, discovery scan, register form) · `live/` (single-device cockpit) · `settings/` (profiles, advanced mode) · `debug/` (raw API console + health + last-scan — see below) · `ui/player.ts` (`<vision-player>`, hls.js-backed) · `ui/toast-host.ts`.

### `src/app/pages/debug/**` — the raw API console (WEB-PLAN W5)

Deliberately outside the "no component calls `fetch` directly" rule: this page's entire purpose is issuing raw, possibly-malformed requests and showing the raw failure, so `VisionApi`/`FleetStore`'s typed shapes and single-toast error handling would work against it.

- `debug-api.service.ts` — `DebugApiService` (`@Injectable providedIn: 'root'`), the only thing in this module that touches `HttpClient` outside `core/api/`. `send({method, path, body?})` issues the request with `observe: 'response'`, `responseType: 'text'` (so any body — JSON or not — parses manually downstream) and returns `RawResponse { status, statusText, ms, headers, bodyText, reached }` for both success and error paths uniformly (a non-2xx is not a thrown exception here, it's data). `reached` is `false` only when the request never got a response at all (network failure / status 0). Request body text is sent as parsed JSON when it parses, verbatim otherwise — a malformed body is something this console must let you send, not reject client-side. Not used by any other page.
- `debug-endpoints.ts` — pure, Angular-free: `DEBUG_ENDPOINTS`, one entry per route in vision-api/MODULE.md's controller table (14 total), each with `method`/`path`/`label` and (POSTs only) a pretty-printed `exampleBody`. `prefillForEndpoint(id)` is what the endpoint `<select>` uses to fill method/path/body; path templates carry `{placeholder}` segments through untouched for the user to hand-edit. `methodHasBody(method)` gates the body textarea (POST/PUT/PATCH).
- `debug-response.ts` — pure: `formatResponseBody(text)` pretty-prints JSON with a raw-text fallback (never throws) plus an explicit `(empty body)` case; `isSuccessStatus(status)` for the 2xx badge.
- `debug-history.ts` — pure: `pushHistoryEntry(history, entry, limit = 20)`, newest-first, capped, non-mutating. Backs the last-~20-requests log; clicking a row re-fills the form (`DebugPage.replay()`) but does not re-send.
- `debug.ts`/`.html`/`.css` — `DebugPage`, three cards: the console above; Health (`GET /actuator/health` parsed for `status`/`components[].status`, up/down chips, raw JSON in a `<details>`); Last scan (`POST /api/discovery/scan` with `{}`, raw `ScanResultResponse` including `failedMethods`). All three reuse `DebugApiService.send()`.

## Conventions

- Standalone components only, `changeDetection: ChangeDetectionStrategy.OnPush` everywhere, signals for all component state, zoneless (`provideBrowserGlobalErrorListeners()`, no `zone.js`).
- `api/models.ts` is the **only** place wire shapes are declared — when a Java DTO changes, fix this file first, then chase usages (`grep -rn` the changed field name under `src/`).
- HTTP errors are reported once, by the layer that knows what the user attempted (`FleetStore.run()`), never by an interceptor.

## Gotchas

- **`ng test` ≠ `vitest run`.** This project's Vitest specs depend on Angular's zoneless `TestBed` bootstrap, which `@angular/build:unit-test` wires up; invoking `vitest` directly skips that and every spec fails with `TestBed.initTestEnvironment()`/`PlatformLocation` JIT errors. Always use `npm run test:ci` / `npm test` / the Maven `npm-test` execution.
- **Categories are not mirrored in `models.ts`.** `DiscoveredDevice.suggestedCategory` is typed as a bare `string` (slug), not an enum/union — there is no frontend list of valid categories yet (would come from `GET /api/categories`, unconsumed here). Don't reintroduce a `DeviceType`-shaped union for it.
- `frontend-maven-plugin` provisions a **pinned** Node/npm into `target/node` per the `pom.xml` properties (`node.version`, `npm.version`) — doesn't use whatever Node is on `PATH`.
- `dist/` (Angular build output) is copied verbatim into `target/classes/META-INF/resources` by `maven-resources-plugin` in `prepare-package`; nothing here compiles Java (`No sources to compile` in `mvn` output is expected, not a failure).

## Status

Green: `./mvnw -B -pl vision-web clean install` passes twice from `clean`, production build succeeds within budget, **38/38 Vitest specs pass** (`api-error.spec.ts`, `core/api/vision-api.spec.ts`, `core/settings-store.spec.ts`, `pages/debug/debug-endpoints.spec.ts`, `pages/debug/debug-response.spec.ts`, `pages/debug/debug-history.spec.ts`). Implements WEB-PLAN W0–W5 (shell, API client, Devices tab, Live/Wall, Settings/profiles, Debug tab) minus Events/Studio (Phase 2/3). `DeviceType` was removed to match the server-side drop of that enum (categories are now asset-level data); `models.ts` and every consuming page (`devices`, `live`, `wall-tile`) were realigned in the same pass — no page currently reads `Asset`/`Category` endpoints, so those DTOs are intentionally not yet mirrored here. The `/debug` route's lazy chunk is ~14 kB raw / ~4.3 kB transfer, well under the per-route budget headroom noted in WEB-PLAN §9; the app's initial bundle is unaffected since it is lazy-loaded and not idle-preloaded.
