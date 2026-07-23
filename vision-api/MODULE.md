# vision-api

REST driving adapter: asset-first + device/stream/discovery endpoints over the domain's use-case ports.

**Depends on:** vision-domain, vision-application (compile) · spring-boot-starter-web · test: spring-boot-starter-test (JUnit 5, Mockito, MockMvc, Hamcrest)
**Used by:** vision-app (wires beans in and packages the jar; the frontend, vision-web, is a separate sibling dependency of vision-app — see Gotchas)
**Build/test:** `./mvnw -B -pl vision-api test`

## API surface

### `com.drones.vision.api` — controllers

| Controller | Method | Path | Success | Failure |
|---|---|---|---|---|
| AssetController | POST | `/api/assets` | 201 `AssetDetailsResponse` | 400 validation (incl. 0-device asset) |
| AssetController | GET | `/api/assets` | 200 `List<AssetSummaryResponse>` | — |
| AssetController | GET | `/api/assets/{id}` | 200 `AssetDetailsResponse` | 404 unknown id, 400 bad UUID |
| AssetController | POST | `/api/assets/{id}/stream` | 201 `StartStreamResponse` | 404 unknown asset, 400 ambiguous device/bad UUID |
| AssetController | DELETE | `/api/assets/{id}/stream` | 204 | idempotent no-op |
| AssetController | GET | `/api/usages/{usageId}/telemetry?limit=` | 200 `List<TelemetrySampleResponse>` | — (unknown usage → empty list) |
| CategoryController | GET | `/api/categories` | 200 `List<CategoryResponse>` | — |
| DeviceController | POST | `/api/devices` | 201 `DeviceResponse` | 400 validation |
| DeviceController | GET | `/api/devices` | 200 `List<DeviceResponse>` | — |
| StreamController | POST | `/api/devices/{deviceId}/stream` | 201 `StartStreamResponse` | 400 bad UUID/validation, 404 unknown device, 409 already streaming |
| StreamController | GET | `/api/streams` | 200 `List<ActiveStreamResponse>` | — |
| StreamController | DELETE | `/api/streams/{streamId}` | 204 | never fails (documented no-op) |
| SimulationController | POST | `/api/simulations` | 201 `SimulationResponse` | 400 bad/nonexistent `videoPath`, unrecognized `transport`, or (docs/CYCLES-PLAN.md §3, §5) a `transport=rtsp`/`mjpeg` spec no registered `FeedTransmitterPort` supports; 409 `simulated` category not seeded |
| SimulationController | DELETE | `/api/simulations/{assetId}` | 204 | idempotent no-op (unknown/already-stopped asset); 400 bad UUID |
| DiscoveryController | POST | `/api/discovery/scan` | 200 `ScanResultResponse` | 400 unknown method name |
| HlsProxyController | GET | `/hls/{streamId}/**` | proxied upstream status (typically 200), `Content-Type`/`Set-Cookie` passed through | 502 upstream unreachable; 404 if no `{streamId}` segment (unmapped, Spring's default) |

`ApiExceptionHandler` (`@RestControllerAdvice`) mapping table (body `{"error","message"}`):

| Exception | Status | code |
|---|---|---|
| `IllegalArgumentException`, `UnsupportedProtocolException` | 400 | `BAD_REQUEST` |
| `NoSuchElementException` | 404 | `NOT_FOUND` |
| `IllegalStateException` | 409 | `CONFLICT` |
| `HlsUpstreamUnavailableException` | 502 | `BAD_GATEWAY` |

`SpaResourceConfiguration` — `WebMvcConfigurer` registering `/**` over `classpath:/META-INF/resources/`, `classpath:/static/`, `classpath:/public/` with an SPA-fallback `PathResourceResolver` (serves `index.html` for extension-less, non-`api/`/`actuator/` paths). See Gotchas — nothing populates those classpath locations from this module's own build.

### `com.drones.vision.api.dto`

Request DTOs (each validates + converts via a `toX()`/`toRegistration()`/`toScanRequest()` method):
`RegisterDeviceRequest(name, protocol, uri, options?, capabilities?)` · `CreateAssetRequest(displayName, category, attributes?, devices:List<DeviceSpec>)` + nested `DeviceSpec(name, protocol, uri, options?, capabilities?)` · `StartStreamRequest(confidenceThreshold?, inferenceFps?)` · `StartAssetStreamRequest(deviceId?, confidenceThreshold?, inferenceFps?)` · `ScanRequestDto(timeoutMs?, methods?)` · `StartSimulationRequest(displayName?, videoPath, latitude?, longitude?, autoStart?, transport?)` (docs/CYCLES-PLAN.md §1c, §3, §5; `toSpec()` defaults `autoStart` to `true` and `transport` to `SimulationTransport.DIRECT` when absent — request DTOs whose default isn't "leave the domain default alone". `transport` is a `String` matched case-insensitively against `SimulationTransport` names (`"direct"`/`"rtsp"`/`"mjpeg"`); an unrecognized value throws `IllegalArgumentException` listing the valid values — dynamically enumerated from `SimulationTransport.values()`, so this DTO needed no code change when `MJPEG` was added, only its javadoc — the same idiom as `CapabilityParsing`).

`capabilities` (both `RegisterDeviceRequest` and `DeviceSpec`) is an optional `List<String>`, matched case-insensitively against `Capability` names via the package-private `CapabilityParsing.parse(List<String>)` helper shared by both records; `null`/empty defaults to `Set.of(Capability.VIDEO)` (the old hardcoded behavior, now just the default), and an unrecognized name throws `IllegalArgumentException` listing the valid values (→ 400 via `ApiExceptionHandler`). This is what lets `POST /api/assets` register a `TELEMETRY`-capable device so `UsageTracker` (vision-application) records positions/sampleCount for it — previously `CreateAssetRequest.DeviceSpec#toRegistration()` hardcoded `Set.of(Capability.VIDEO)`, so asset devices could never carry telemetry through the API even though the domain/use-case layer always supported arbitrary `Set<Capability>`.

Response DTOs (`@JsonInclude(NON_NULL)` unless noted — see Conventions), each with a static `from(domainType)` mapper:
`DeviceResponse(id, name, capabilities:List<String>, protocol, uri, options)` (no `NON_NULL`) · `DiscoveredDeviceResponse(method, name, address, suggestedCategory?, protocol?, uri?, details)` · `ScanResultResponse(devices:List<DiscoveredDeviceResponse>, failedMethods:Set<String>)` (no `NON_NULL`) · `StartStreamResponse(streamId, viewUrl?)` · `ActiveStreamResponse(streamId, deviceId, startedAt, viewUrl?)` · `AssetSummaryResponse(assetId, displayName, category, categoryName, owner, status, lastUsedAt?, lastKnownPosition?, attributes)` · `AssetDetailsResponse(...AssetSummaryResponse fields, devices:List<DeviceResponse>, recentUsages:List<AssetUsageResponse>)` · `AssetUsageResponse(usageId, startedAt, endedAt?, startPosition?, lastPosition?, sampleCount)` · `CategoryResponse(slug, name, parent?, attributeHints:List<String>)` · `GeoPositionResponse(latitude, longitude, altitudeMeters?)` · `TelemetrySampleResponse(at, latitude?, longitude?, altitudeMeters?, headingDegrees?, batteryPercent?)` · `SimulationResponse(assetId, streamId?, viewUrl?)` (docs/CYCLES-PLAN.md §1c; `streamId`/`viewUrl` both absent when the simulation wasn't auto-started) · `ErrorResponse(error, message)` (no annotation).

### `HlsProxyController` — HLS reverse proxy

`HlsProxyController(URI hlsUpstreamBase)` (constructor-injected raw `URI`, wired by `vision-app`'s `WiringConfiguration` from `VisionPublishProperties.Mediamtx#hlsBase()` — see Conventions for why this one controller deviates from the "ports only" rule). `GET /hls/{streamId}/**` forwards the request to `hlsUpstreamBase + "/" + <raw remainder after "/hls/">`, so browsers never talk to the mediamtx sidecar directly — see `StreamPublisherPort#viewUrl`'s new app-relative contract below.

- **Raw pass-through**: the forwarded path/segment name comes from `HttpServletRequest#getRequestURI()` (servlet-spec-guaranteed undecoded), not the decoded `@PathVariable`, so percent-encoded segment names are never decoded-then-re-encoded.
- **Redirects**: followed server-side via `java.net.http.HttpClient` (`Redirect.NORMAL`) — the browser only ever sees this app's origin and a final status, never mediamtx's own `302`.
- **Cookies**: a fresh `CookieManager`/`CookieStore` per incoming request (not shared across browser requests) is seeded from the incoming `Cookie` header and installed as the `HttpClient`'s cookie handler, so a `Set-Cookie` mid-chain (mediamtx's viewer-pinning cookie) rides along to the next redirect hop; every `Set-Cookie` seen across the whole chain (via `HttpResponse#previousResponse()`) is relayed back to the browser, oldest hop first. **Gotcha**: seeded cookies must be built with `HttpCookie#setVersion(0)` — the `HttpCookie(name, value)` constructor defaults to RFC 2965 version 1, which `CookieManager` then re-serializes as the legacy `$Version="1"; name="value";$Path="/"` header instead of the plain `name=value` a real server expects; found by an actual failing test, not by inspection.
- **Body**: buffered fully as `byte[]` (`HttpResponse.BodyHandlers.ofByteArray()`) — no true streaming; acceptable at this scale (playlists tiny, fMP4 segments at most a few MB) per KISS.
- **Failure**: only a failure to reach upstream at all (`IOException`/interrupted) throws `HlsUpstreamUnavailableException` → 502; a normal non-2xx actually received from upstream (e.g. a segment not ready yet) passes through verbatim, same as `Content-Type`/status on success. No caching headers are added beyond whatever upstream already sent.
- **404 without `{streamId}`**: `/hls` or `/hls/` simply doesn't match the `@GetMapping` pattern and falls through to Spring's ordinary unmapped-route 404 — no special-case code.

## Conventions

- Controllers: constructor-injected with driving use-case interfaces only, plus (only where no driving use case exists for the read) two driven ports used read-only: `StreamPublisherPort` (viewUrl) and `TelemetryRepositoryPort` (telemetry trail). Never an adapter — ArchUnit-enforced in vision-app. **One documented exception**: `HlsProxyController` takes a raw `URI` (not a port) because it isn't a use-case-facing controller at all — it's a byte-level reverse proxy with no domain concept to depend on; `vision-app` supplies the `URI` as its own bean (`hlsProxyUpstreamBase`) so the controller itself stays a plain component-scanned bean like every other controller here, rather than being hand-constructed in `WiringConfiguration` (which would collide with component-scanning's own auto-registration of the same `@RestController` class — see vision-app/MODULE.md).
- Mapping/validation happens on the DTO records themselves (`toRegistration()`, `toSpec()`, `toScanRequest()`) — no mapper library, controllers stay thin. The one shared exception is `dto.CapabilityParsing` (package-private), factored out purely to avoid duplicating the same case-insensitive `Capability` name lookup + error message between `RegisterDeviceRequest` and `CreateAssetRequest.DeviceSpec`.
- `@JsonInclude(Include.NON_NULL)` on every response DTO that has an optional field — omits absent fields from JSON entirely rather than serializing `null`.
- Ids in path variables are canonical UUID strings, parsed via `XId.of(String)` — its `IllegalArgumentException` on a malformed UUID surfaces as 400 through the same mapping as domain validation.
- 404-vs-400 for "unknown asset": both `GetAssetDetailsUseCase` and `StartAssetStreamUseCase` throw `IllegalArgumentException` for *both* "unknown id" and real validation failures (ambiguous device). `AssetController.fetchDetailsOrNotFound` calls `getAssetDetailsUseCase.details(id)` first and translates only that call's `IllegalArgumentException` into `NoSuchElementException` (→404) before any ambiguous call runs.

## Gotchas

- **Jackson 3** (Spring Boot 4, `tools.jackson.*`): DTOs still import `com.fasterxml.jackson.annotation.JsonInclude` — only the databind package moved; the annotations package is unchanged and shared with Jackson 2.
- **No static resources ship from this module.** `vision-api/src/main/resources` does not exist. `SpaResourceConfiguration` configures resource-serving paths, but nothing in vision-api's own build populates `classpath:/META-INF/resources/` etc. — the Angular frontend (`vision-web`) is a *sibling* module that only **vision-app** depends on and packages alongside this jar (see docs/WEB-PLAN.md). Running vision-api's tests or jar standalone serves no UI.
- The old Phase-1 static console (`static/index.html` + `app.js`: register form, device table, scan section, hls.js player) referenced in older plans/docs is **gone from source**. `target/classes/static/**` in a not-yet-cleaned build directory is a leftover artifact from before this cleanup — don't trust it as a source-of-truth.
- Tests use MockMvc `standaloneSetup(new XController(mock(UseCase.class), ...)).setControllerAdvice(new ApiExceptionHandler())` — no Spring context, use-case ports mocked directly with Mockito, request bodies as raw JSON text blocks, assertions via `jsonPath`/Hamcrest. This means these tests do **not** catch DI wiring gaps — see vision-app's Status.

## Status

**Green**, verified by a from-`clean` `./mvnw -B -pl vision-api clean test`: main and test sources compile with zero errors, 76/76 tests pass (AssetControllerTest 21, StreamControllerTest 13, DiscoveryControllerTest 8, DeviceControllerTest 10, CategoryControllerTest 2, HlsProxyControllerTest 6, SimulationControllerTest 16 — added for docs/CYCLES-PLAN.md §1c's one-call simulation endpoint, extended for §3's `transport` field and `DELETE /api/simulations/{assetId}`, and for §5's `"mjpeg"` transport value).

docs/ASSET-MODEL-PLAN.md's **M3** ("Asset-first API + UI", scope `vision-api/src/**`) is functionally complete on the API side: `AssetController`/`CategoryController` exist; `RegisterDeviceRequest`/`DeviceResponse` carry no `DeviceType` field; `DiscoveredDeviceResponse` already exposes `suggestedCategory` (not `suggestedType`); `StreamController`/`AssetController` parse ids correctly via `DeviceId.of`/`StreamId.of`/`AssetId.of`. (Earlier guidance describing this module as still red against `DeviceType` was stale — a `clean` rebuild disproves it; a non-`clean` `mvn test` here can be misled by pre-refactor `target/` artifacts, see vision-app's Gotchas.) The "UI rework" half of M3 is **not** done in this module — see the static-console gotcha above; that work belongs to vision-web (docs/WEB-PLAN.md W2 done, W5 pending), not vision-api.

vision-app's wiring for these controllers is complete (docs/ASSET-MODEL-PLAN.md M4 closed — `AssetWiringTest` asserts every bean); the earlier caveat about a non-bootable context is resolved. Remember these MockMvc tests still mock use-case ports directly, so future DI gaps surface only in vision-app's context tests, not here.
