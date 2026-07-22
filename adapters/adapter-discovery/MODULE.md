# adapter-discovery

ONVIF WS-Discovery, mDNS/DNS-SD, and V4L2 local enumeration scanners for finding candidate devices.

**Depends on:** vision-domain, org.jmdns:jmdns · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl adapters/adapter-discovery test` — 19 tests, verified via a live run: 0 failures, 0 skipped.

## API surface
### `com.drones.vision.adapter.discovery.mdns`
- `final class MdnsScanner implements DeviceDiscoveryPort` — `method()` → `"mdns"`. `MdnsScanner()` (default interface), `MdnsScanner(InetAddress bindAddress)` test seam. `scan(Duration timeout): List<DiscoveredDevice>` — browses `_rtsp._tcp.local.` and `_http._tcp.local.` concurrently (one virtual thread each).
  - `_rtsp` hits → category `"ip-camera"`, `suggestedStream` protocol `"rtsp"`, URI `rtsp://host:port/`.
  - `_http` hits hinting ESP32 (name/host matches regex `esp32|espressif|esp-cam`, case-insensitive) → category `"esp32-cam"`, `suggestedStream` protocol `"mjpeg"`, URI `http://host:port/`, `details["note"]` warns the real MJPEG path is `:81/stream`, not the advertised port.
  - Other `_http` hits → returned with `suggestedCategory`/`suggestedStream` both `null` (still surfaced for manual registration).
  - package-private pure mapping methods (unit-testable without jmdns): `static DiscoveredDevice mapRtspHit(String name, String host, int port)`, `static DiscoveredDevice mapHttpHit(String name, String host, int port)`.
### `com.drones.vision.adapter.discovery.onvif`
- `final class OnvifWsDiscoveryScanner implements DeviceDiscoveryPort` — `method()` → `"onvif"`. `OnvifWsDiscoveryScanner()` (multicast `239.255.255.250:3702`), `OnvifWsDiscoveryScanner(InetSocketAddress probeTarget)` test seam. `scan(Duration)` sends one WS-Discovery SOAP `Probe` UDP datagram and collects `ProbeMatch` replies until the deadline; hand-rolled regex/string XML extraction (no SOAP/XML stack — deliberate KISS). Every candidate → category `"ip-camera"`, `suggestedStream` always `null` (needs an authenticated `GetStreamUri` call, added by `adapter-onvif` in Phase 4).
  - package-private `static Optional<DiscoveredDevice> parseProbeMatch(String xml)` — pure parser, unit-testable on canned XML; never throws, returns empty on malformed/unusable input.
### `com.drones.vision.adapter.discovery.v4l2`
- `final class V4l2Scanner implements DeviceDiscoveryPort` — `method()` → `"v4l2"`. `V4l2Scanner()` (real `/dev`, `/sys`), `V4l2Scanner(Path devBase, Path sysBase)` test seam. `scan(Duration)` lists `videoN` entries directly under `devBase`; friendly name read from `<sysBase>/class/video4linux/videoN/name` if readable, else the node name. Every candidate → category `"usb-camera"`, `suggestedStream` protocol `"v4l2"`, URI **always** `file:/dev/videoN` — the real path, regardless of `devBase` (that parameter only redirects *where the scanner looks*, e.g. in tests).

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Every `scan(Duration)` budgets against `deadlineNanos = System.nanoTime() + timeout` and returns an empty list rather than throwing on "nothing found" or a filesystem/parse hiccup; genuine failures (socket bind, jmdns setup) still propagate per `DeviceDiscoveryPort`'s contract.
- `System.Logger` used for best-effort diagnostic logging in `OnvifWsDiscoveryScanner`/`V4l2Scanner` (`DEBUG` level only — never surfaces as a scan failure).

## Gotchas
- **`MdnsScanner` timeout budget math.** `DiscoveryService` (caller, in `vision-application`) hard-bounds its wait at `timeout + 200ms` grace. `JmDNS.create()` setup and `JmDNS.close()` teardown both cost real wall-clock time on top of the two concurrent `list()` calls: `listWindowMillis()` computes the `list()` window as `timeoutMillis - setupElapsedMillis - JOIN_GRACE_MILLIS(150) - SAFETY_MARGIN_MILLIS(50)`, clamped to a floor `MIN_LIST_WINDOW_MILLIS`=50 — that cushion is carved *out of* the timeout, not added on top (the pre-fix bug). See `MdnsScannerLoopbackTest.scanReturnsWithinTimeoutPlusCallerGrace` for the regression test.
- **`JmDNS.close()` cost, and `closeWithinBudget`.** jmdns 3.5.9 hard-codes its internal cancellation state machine to allow itself up to `DNSConstants.CLOSE_TIMEOUT` (5s), entirely unaware of this scanner's own timeout. `closeWithinBudget()` starts `close()` on its own virtual thread and joins it only up to the scan's remaining deadline (`remainingMillis`); if the budget runs out first, `scan()` returns anyway and the close finishes (releasing the multicast socket) in the background — an accepted "leaked but harmless daemon-ish virtual thread" tradeoff, the same one `DiscoveryService` already accepts for a hanging adapter.
- **`Onvif` deadline is enforced per receive**, not once at the top of the loop: `collectResponses` sets `socket.setSoTimeout` to the exact remaining budget before *every* `receive()` call, so a scan fielding many small/garbage responses still terminates on time.
- All three scanners key `DiscoveredDevice` identity differently: mdns/v4l2 return a fresh list per call with no dedup; onvif dedupes by `address()` within one scan (`LinkedHashMap<URI, DiscoveredDevice>`, `putIfAbsent`) since multiple `ProbeMatch` datagrams can arrive for the same device.

## Status
Fully implemented, all 19 tests green (confirmed via a live `./mvnw -B -pl adapters/adapter-discovery test` run). `MdnsScannerLoopbackTest` depends on the sandbox correctly routing multicast over loopback — its own javadoc documents the fallback if it starts flaking in CI: `@Disabled` it and rely on `MdnsScannerTest`'s pure mapping-logic tests instead. ONVIF candidates never carry a usable stream URI yet (needs `adapter-onvif`'s credentials flow, Phase 4); V4L2 candidates point at a real `/dev/videoN` but registering one today 400s (no `"v4l2"`-supporting `VideoSourcePort` adapter yet, also Phase 4) — returned anyway, deliberately, so discovery shows what physically exists on the host.
