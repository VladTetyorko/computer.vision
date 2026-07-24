# REALTIME-PLAN — connect once, stream forever

Status: **proposed** (2026-07-24). Authoritative spec for eliminating FE↔BE request churn:
persistent WHEP video sessions + a single server-push data channel replacing per-asset polling.
Prompted by a measured ~281 req/min on `/fly` with a 10-asset fleet and WHEP sessions cycling
every ~10 s on perfectly healthy streams.

## 0. Measured baseline (what we're fixing)

One watched asset on `/fly`, fleet of N assets (investigation 2026-07-24, file:line evidence in §1–§2):

| Request class | Cause | req/min (N=10) |
|---|---|---|
| `GET /api/devices` + `/api/streams` | FleetStore 5 s snapshot poll | 24 |
| `GET /api/assets` + `/api/assets/{id}` | fly.ts own 5 s poll | 24 |
| `GET …/detections?limit=50` | 2 s poll **+ effect re-trigger every 5 s** | 42 |
| `GET …/telemetry?limit=200` | 2 s poll | 30 |
| telemetry re-resolution burst | **O(N) bug** — `(N+2)` GETs every 5 s | 144 |
| `GET /api/events?sinceMs` | 5 s poll (the only delta-based endpoint) | 12 |
| WHEP `OPTIONS`+`POST` | false-stall watchdog rebuilds session every ~10–11 s | ~11 |
| **Total** | | **~287** |

Clarifications from the investigation:
- The "`whep` 204s" in the network tab are **CORS preflights** (`OPTIONS`→204 before each cross-origin
  `POST`→201), not DELETEs — the frontend never issues WHEP DELETE at all (nor reads the session
  `Location` header, so real teardown never tells the server either — a session leak to fix in C-1).
- WHEP churn root cause (`player.ts`): `lastFrameAt` is only ever set at attach and in the one-shot
  `ontrack` (`player.ts:990,1019`); nothing observes ongoing frame delivery, so the stall watchdog
  (`STALL_WATCHDOG_MS=8000`, tick 2 s) declares a **healthy** stream stalled ~8–10 s after connect,
  tears down, reattaches after 1 s — and `SUSTAINED_PLAYBACK_RESET_MS=5000` resets the backoff before
  every false stall, so it cycles at ~10–11 s forever. Scales per `<vision-player>` instance
  (primary + every secondary tile).
- O(N) amplification bug (`fly.ts:206-223`): two `effect()`s read `this.asset()` / `this.stream()`
  object references, which are fresh objects after every 5 s poll (signals compare with `Object.is`),
  so they re-fire with unchanged ids: `detections.track()` re-entry fires an extra immediate GET +
  clears results (visible flicker); `telemetry.track()` re-entry re-runs `findOpenUsageId`
  (`telemetry-store.ts:111-123`) = `GET /api/assets` + `GET /api/assets/{id}` **for every asset in
  the fleet**, every ~5 s. `FleetMapStore.reconcileTrackers` (`map-store.ts:121-133`) already shows
  the correct idiom (reconcile by id, no-op when unchanged).
- There is **no SSE/WebSocket anywhere** in the repo today; all server-push work is greenfield.

## 1. Design principles

1. **HTTP is for mutations and initial snapshots only.** Steady-state data arrives pushed.
2. **A media session survives until the asset leaves the screen.** Transient network trouble is
   recovered *inside* the existing RTCPeerConnection (ICE restart), never by teardown+re-POST.
3. **Frequency of data ≠ frequency of requests.** Server coalesces; client renders at its own pace.
4. **Costs scale with what's on screen, not with fleet size.** Off-screen assets consume nothing.

## 2. Phase R-a — stop the bleeding (frontend-only, no new protocols)

*Est. 0.5–1 day. Cuts ~200 of the ~287 req/min and all WHEP churn.*

1. **Real stall detection** (`ui/player.ts`): poll `RTCPeerConnection.getStats()` on the existing
   2 s watchdog tick; refresh `lastFrameAt` whenever `framesDecoded`/`bytesReceived` advanced since
   the previous tick. `STALL_WATCHDOG_MS` then measures actual stalls. (HLS side already keys on
   `timeupdate` — unaffected.)
2. **Guard the fly effects on derived primitives** (`fly.ts:206-223`): track by `deviceId`/`streamId`
   value; re-enter stores only when the id actually changed (copy `map-store.ts:121-133` idiom).
   Kills the O(N) burst, the extra detections GET, and the results flicker.
3. **`TelemetryStore` must start from the asset id it already has** (`telemetry-store.ts:111-123`):
   accept `(assetId, deviceId)` instead of re-deriving the open usage from the full fleet
   (`map-store.ts:38-40` documents exactly this). Even when re-entered, cost becomes O(1).
4. **Do not tear down players on tile reorder/resize** — only when the underlying stream id changes
   (the `lastAttachKey` guard from e7cdc46 already covers most of this; verify for secondary tiles).

Exit criteria: on `/fly` with a healthy stream, network tab over 60 s shows zero WHEP re-POSTs,
zero unscheduled detections/telemetry GETs, no `GET /api/assets/{id}` bursts; watchdog still recovers
a genuinely killed feed (stop the sim feed → reconnect within ~10 s).

## 3. Phase R-b — persistent WHEP ("connect once")

*Est. 1–2 days. Requires mediamtx ≥1.19.2 (already pinned — its `session.go` shares the ICE-restart
PATCH branch between WHIP and WHEP; verified in source 2026-07-24).*

1. **ICE restart instead of teardown** (`ui/player.ts` + `player-recovery.ts`): on
   `connectionState==='disconnected'` start a grace timer (5 s); on `'failed'` or grace expiry call
   `restartIce()` and send the new offer as WHEP **PATCH** (`application/trickle-ice-sdpfrag`,
   `If-Match: "*"`) to the session URL. Media keeps flowing during restart. Adopt the
   perfect-negotiation pattern so initial offer, ICE-restart offer, and future renegotiation share
   one code path. DELETE+re-POST becomes the last resort (PATCH rejected / resource 404).
2. **Honor the session resource**: capture the `Location` header from the WHEP POST
   (`player.ts:1050-1063` currently ignores it); send `DELETE` on genuine teardown (asset leaves
   screen, page close via `sendBeacon`/`keepalive`) so mediamtx isn't left reaping leaked sessions.
3. **Stable DTLS fingerprint**: generate one ECDSA `RTCCertificate` per browser, persist in
   IndexedDB, pass via the `certificates` option to every PC. Skips keygen latency, gives a stable
   fingerprint for server-side viewer correlation. (Safari caps stored-cert lifetime ~1 week —
   regenerate transparently on expiry.)
4. **Keep N PCs for N tiles.** mediamtx WHEP is strictly one stream per session (verified in
   source); single-PC multi-track needs a custom SFU gateway — explicitly rejected at our scale.
   PC count is not the bottleneck; churn was.

Exit criteria: pull the network cable / kill and restart the sim feed → the *same* WHEP session
(same session URL, same fingerprint) resumes via PATCH; a 10-minute healthy soak shows exactly one
POST per tile, zero DELETEs; unplugging an asset's feed for >grace period falls back to HLS as today.

## 4. Phase R-c — server-push data plane (SSE)

*Est. 3–5 days across vision-api + vision-application + vision-web. Replaces all steady-state
polling: detections, telemetry, events, fleet/device/stream state.*

**Transport: SSE** (`Flux<ServerSentEvent>` in vision-api). Chosen over WebSocket/STOMP because the
flow is one-way (commands stay on REST), `EventSource` provides reconnect + `Last-Event-ID` resume
for free, and it multiplexes over HTTP/2 alongside REST on the same origin — no new port, no proxy
changes beyond the dev `proxy.conf.json` entry. WebSocket is the documented upgrade path if we ever
need client→server frames on the channel; WebTransport revisit ≥2027 (no Spring server story).

1. **One stream per tab**: `GET /api/live?topics=…` → envelope events
   `{seq, assetId?, type: fleet|telemetry|detections|event, payload}`.
   - On connect: snapshot per subscribed topic, then deltas.
   - `Last-Event-ID: <seq>` resume from a per-topic **ring buffer** (application layer, bounded,
     sequence-numbered); older than buffer → full snapshot re-sent. `/api/events`' existing
     `sinceMs` cursor generalizes into this.
2. **Subscription control**: `PATCH /api/live/{connectionId}/topics` (add/remove asset topics as
   tiles enter/leave the screen) so fan-out is pruned server-side. Fleet-level topic (asset list,
   stream/device states, events) is always on; per-asset telemetry/detections are opt-in.
3. **Server-side coalescing**: batch telemetry/detections 100–200 ms per connection before emit;
   detections emit the latest frame's boxes (no backlog); telemetry emits appended samples as
   deltas, not `limit=200` snapshots.
4. **Domain wiring**: application layer already has the in-memory state (StreamPipeline,
   UsageTracker, fleet snapshots). Add outbound port `LiveUpdatePublisherPort` (domain-free
   interfaces, hexagonal rule intact); vision-api adapter implements it as the SSE registry.
   Persistence stays untouched — the ring buffer is process-local (single-instance deployment;
   multi-instance fan-out is out of scope until we have one).
5. **Frontend**: `LiveStore` (core/) owns the single `EventSource`, exposes per-topic signals;
   `FleetStore`/`TelemetryStore`/`DetectionsStore`/`EventsStore` become projections of it.
   `PollScheduler` survives only as degraded fallback when SSE is unavailable (feature flag
   `vision.live.enabled`, default on).

Exit criteria: `/fly` steady state = 1 SSE connection + WHEP media, zero recurring GETs in a 60 s
window; killing the backend mid-session → EventSource auto-reconnects and resumes without gaps
(verified via seq continuity); command dashboard with N=10 assets stays under 5 req/min total.

## 5. Phase R-d — deferred

- **SharedWorker** to share the SSE connection + certificate across tabs (adopt when multi-tab
  operation is real).
- **WebSocket upgrade** of `/api/live` if client→server control frames are needed on-channel.
- **mediamtx bump 1.19.2 → 1.19.3+** (CORS-credentials fix #5975; re-test WHEP reconnect per
  docker-compose comment) — independent of this plan, do first in R-b's branch since R-b re-tests
  WHEP behavior anyway.
- **Multi-instance fan-out** (broker-backed) — only with a second backend instance.

## 6. Non-goals

- Single PeerConnection carrying many video streams (needs custom SFU; churn was the real cost).
- DataChannel over WHEP (not wired for WHEP reads in mediamtx; verified in source).
- Replacing HLS: it remains the fallback/scale tier; WHEP remains the low-latency tier.
