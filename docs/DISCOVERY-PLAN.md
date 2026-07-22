# Discovery Plan — Scanner for Connected Devices

Companion to [ARCHITECTURE.md](../ARCHITECTURE.md); runs between Phase 1 and Phase 2 (pulls the discovery items forward from Phase 4). **Milestone:** press "Scan" in the UI (or `POST /api/discovery/scan`) and get a list of devices found on the network/host, each one-click-registrable.

---

## 0. Design decisions

1. **Each discovery mechanism is a driven adapter** implementing one new port (`DeviceDiscoveryPort`); the application layer fans out to all of them in parallel (virtual threads), bounds them by a timeout, and aggregates. Adding a mechanism later (SSDP, BLE, MAVLink heartbeat listen) = new adapter, zero core changes.
2. **Discovery yields *candidates*, not devices.** A `DiscoveredDevice` is a suggestion (name, guessed `DeviceType`, maybe a ready-to-use `StreamDescriptor`); the user turns it into a registered `Device` through the existing register flow. Some mechanisms can't produce a full stream URI (ONVIF needs credentials for `GetStreamUri` — Phase 4); they still return the candidate with the info they have.
3. **Error isolation:** one failing scanner never fails the scan; failures are reported per-method in the result.
4. **Scanning is passive/standards-based only** — multicast discovery probes (WS-Discovery, mDNS) and local device enumeration. No subnet port-sweeping (backlog, off by default if ever added).
5. **Dedup** exact `(method, address)` duplicates within one scan; cross-method merging of the same physical device is backlog.
6. New library pin: `org.jmdns:jmdns:3.5.9` (mDNS). ONVIF WS-Discovery is hand-rolled UDP + minimal XML parsing (no SOAP stack — KISS).

---

## 1. Task D1 — Port, use case, service (+ module shell)

**Scope:** `vision-domain/src/**`, `vision-application/src/**`, root `pom.xml` (jmdns pin + module entry via adapters aggregator), new `adapters/adapter-discovery/pom.xml` (+ placeholder package-info), `vision-app/pom.xml` (add adapter-discovery dep).

- Domain `model.DiscoveredDevice`: `record(String method, String name, URI address, DeviceType suggestedType, StreamDescriptor suggestedStream, Map<String,String> details)` — method/name/address non-null (blank-checked), `suggestedType`/`suggestedStream` nullable, `details` immutable copy. Follow existing validation idiom.
- Domain `port.out.DeviceDiscoveryPort`: `String method();` (stable lower-case key: `"onvif"`, `"mdns"`, `"v4l2"`), `List<DiscoveredDevice> scan(Duration timeout);` — blocking, must return within ~timeout, empty list for "nothing found", may throw for genuine failure (caller isolates).
- Domain `port.in.ScanDevicesUseCase`: `ScanResult scan(ScanRequest request);` nested records: `ScanRequest(Duration timeout, Set<String> methods)` (empty methods = all; static `ScanRequest.defaults()` → 4 s, all), `ScanResult(List<DiscoveredDevice> devices, Set<String> failedMethods)`.
- Application `DiscoveryService`: implements the use case; runs each selected port on a virtual thread, hard-bounds waits (timeout + small grace), collects results, dedups `(method, address)`, isolates per-adapter failures/timeouts into `failedMethods`, never throws for adapter trouble. Unknown requested method → `IllegalArgumentException`.
- Tests: record validation; service — parallel aggregation, timeout enforcement (a deliberately hanging fake port must not stall the scan past its bound), failure isolation, dedup, method filtering.
- `adapter-discovery` pom: vision-domain + jmdns + junit (test). vision-app pom gains the dependency now to avoid a later pom edit colliding with wiring work.

**Done when:** `./mvnw -B -pl vision-domain,vision-application test` green and full `./mvnw -q -B install -DskipTests` still succeeds.

## 2. Task D2 — `adapter-discovery` implementations

**Scope:** `adapters/adapter-discovery/src/**` only. Package `com.drones.vision.adapter.discovery` (subpackages `onvif`, `mdns`, `v4l2`). Plain classes, no Spring.

- `onvif.OnvifWsDiscoveryScanner` (`method()="onvif"`): sends a WS-Discovery SOAP `Probe` (NetworkVideoTransmitter) via UDP multicast `239.255.255.250:3702`, collects `ProbeMatch` datagrams until the timeout, parses `XAddrs` (address = first), `Scopes` (`onvif://www.onvif.org/name/<n>` → name, else host), suggestedType `IP_CAMERA`, suggestedStream `null` (javadoc: RTSP URI requires authenticated `GetStreamUri`, Phase 4), details: scopes/types/epr. Parsing via lenient regex/string extraction on the XML — no SOAP dependency; must tolerate malformed responses (skip them). Constructor seam `(InetSocketAddress probeTarget)` for tests; default constructor uses the standard multicast address.
- `mdns.MdnsScanner` (`"mdns"`): jmdns bound for the timeout window; service types `_rtsp._tcp.local.` and `_http._tcp.local.`; `_rtsp` hits → suggestedStream `rtsp://host:port/`, type `IP_CAMERA`; `_http` hits whose name/host hints ESP32 (`esp32`, `espressif`, `esp-cam` — case-insensitive) → type `ESP32_CAM`, suggestedStream protocol `mjpeg`, `http://host:port/` with a `details` note that the standard ESP32-CAM stream path is `:81/stream`; other `_http` hits → candidate with null type/stream. Always close the jmdns instance.
- `v4l2.V4l2Scanner` (`"v4l2"`): enumerate `<devBase>/video*` (default `/dev`), read friendly name from `<sysBase>/class/video4linux/<videoN>/name` when present; type `USB_CAMERA`; suggestedStream protocol `"v4l2"`, uri `file:/dev/videoN` (javadoc: playable once `adapter-usb` lands in Phase 4; registering it today start-fails with 400 — acceptable and explicit). Constructor seam for `devBase`/`sysBase`; non-Linux or missing dirs → empty list, never throws.
- Tests: ONVIF — parser tests on canned ProbeMatch XML (well-formed, multiple XAddrs, malformed-skipped) + a loopback test with a fake UDP responder via the constructor seam; mDNS — jmdns-register a local service and scan for it (if this proves flaky in the sandbox, keep it `@Disabled` with a clear javadoc and rely on unit-level mapping tests — report which way it went); v4l2 — `@TempDir` fake `/dev` + `/sys` trees: found, named, unnamed, absent-dir cases.

**Done when:** `./mvnw -B -pl adapters/adapter-discovery test` green twice.

## 3. Task D3 — API + UI

**Scope:** `vision-api/src/**` only.

- `DiscoveryController`: `POST /api/discovery/scan`, optional body `ScanRequest{timeoutMs?, methods?}` → 200 `ScanResultResponse{devices:[{method,name,address,suggestedType?,protocol?,uri?,details}], failedMethods}`. Unknown method → 400 via existing advice (`IllegalArgumentException`). DTOs in `…api.dto`, `@JsonInclude(NON_NULL)` like the others.
- UI: "Scan for devices" button + timeout selector; results table (method, name, address, type, details) with a **Use** button per row that prefills the register form (name/type/protocol/uri when suggested) and highlights it; rows without a suggested stream prefill what they can. Errors/failed methods surface in the toast area.
- Tests: MockMvc standalone — happy path (mocked use case), defaulting when body absent, 400 on unknown method, JSON shape incl. omitted nulls.

**Done when:** `./mvnw -B -pl vision-api test` green.

## 4. Task D4 — Wiring + full verification

**Scope:** `vision-app/src/**`, full build.

- `WiringConfiguration`: beans for the three scanners + `DiscoveryService` (inject `List<DeviceDiscoveryPort>`); property `vision.discovery.enabled` (default true) gating the scanner beans (context stays clean in restricted environments).
- Context test asserting all three methods are registered; ArchUnit still green (discovery adapter is a new slice — no rule changes expected).
- Full `./mvnw -B verify`; if docker + app runnable, a quick live check of `POST /api/discovery/scan` against the running app (v4l2/mdns may legitimately return empty in a sandbox — report actual output, do not fake success).

---

## Execution order

```
D1 ──▶ { D2, D3 in parallel } ──▶ D4
```

Interleaving with Phase 1: D1 may run alongside U3 (disjoint files); **U5 runs after D1** so its full-reactor verify sees a green tree; D2/D3 after U5; D4 last.
