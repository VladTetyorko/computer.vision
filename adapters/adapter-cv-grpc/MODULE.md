# adapter-cv-grpc

gRPC client to the Python CV service: `GrpcDetectionPort implements DetectionPort` over the generated `Inference/DetectStream` bidi stub (`proto/vision/v1/cv.proto`).

**Depends on:** vision-domain, vision-proto (which brings io.grpc:grpc-{netty-shaded,protobuf,stub}, com.google.protobuf:protobuf-java) · **Used by:** none yet (vision-app wiring is a later task, `vision.cv.enabled` per `docs/MVP1-PLAN.md` §C7.4)
**Build/test:** `./mvnw -B -pl adapters/adapter-cv-grpc test` — 9 tests, `GrpcDetectionPortTest`, in-process gRPC server, no Python/docker needed.

## API surface
### `com.drones.vision.adapter.cvgrpc`
- `final class GrpcDetectionPort implements DetectionPort, AutoCloseable` — plain class, no Spring.
  - `GrpcDetectionPort(String host, int port)` — convenience ctor; builds a plaintext `ManagedChannel` (the CV service is reached over a private/internal docker network, so plaintext is deliberate).
  - `GrpcDetectionPort(ManagedChannel channel)` — bring-your-own-channel seam (tests use an in-process channel); `close()` shuts this channel down too, regardless of who built it.
  - `CompletionStage<DetectionResult> detect(VideoFrame, PipelineConfig)` — the `DetectionPort` method; never blocks. See **Correlation design** and **Failure semantics** below.
  - `void streamEnded(StreamId)` — optional lifecycle hook (see **Stream lifecycle**); idempotent.
  - `void close()` — idempotent; ends every open stream, then shuts down the channel (awaits termination 5s, then `shutdownNow()`).

## Correlation design
`DetectStream` is bidirectional streaming, so there is one open call per `StreamId`, not per frame:
- A private `StreamSession` (one per `StreamId`, held in a `ConcurrentHashMap<StreamId, StreamSession>`) owns the request `StreamObserver<FrameRequest>`, lazily opened on the first `detect()` for that stream.
- Each frame is sent as one `FrameRequest`, keyed in a `ConcurrentHashMap<Long, CompletableFuture<DetectionResult>>` by `sequence` (`VideoFrame.sequence()`, per-stream-monotonic). The matching `DetectionResponse` (matched back by its own `sequence` field) completes that specific future — proven under out-of-order server replies (`detectCorrelatesInterleavedResponsesBySequence`).
- **The mapped `DetectionResult.streamId()` is the session's own (domain) `StreamId`, not a re-parse of the response's wire `stream_id` string.** Correlation never depends on the server echoing that field correctly, and this avoids a `StreamId.of(...)` parse failure on a malformed echo turning into a crash instead of a clean per-frame failure.
- gRPC stream observers are not thread-safe: every write to a session's request observer (including the lazy open) is serialized through that session's own monitor (`synchronized` block). Concurrent `detect()` calls for the *same* stream from multiple threads are safe (`concurrentDetectCallsForSameStreamCompleteWithoutCorruption`); different streams never contend on the same lock.

## Failure semantics
- **Transport failure** (`onError` from the server — e.g. the CV service process restarting): every still-pending future for that stream fails immediately with the transport exception (`StatusRuntimeException`), and the `StreamSession` entry is dropped from the map. The next `detect()` for that `StreamId` transparently opens a fresh call — no explicit reconnect step needed by the caller. This is the recovery mechanism a caller's retry/backoff (`docs/MVP1-PLAN.md` §C7.3, not yet built) depends on.
- **Server ends the stream normally** (`onCompleted` with no error): treated the same as a transport failure — pending futures fail with `IllegalStateException`, session dropped — because a live bidi call is never expected to complete server-side on its own.
- **Hung service** (no error, just silence): a bidi call has no natural per-call deadline, so every pending future instead gets its own `RESPONSE_TIMEOUT_SECONDS`=2s timeout via `CompletableFuture.orTimeout` (package-private constant, not a Spring property — this is an adapter-internal safety net, not user-tunable config). A response arriving after the timeout is silently ignored (its sequence is no longer in the pending map).
- **Unsupported `PixelFormat`** (only `JPEG`→`IMAGE_ENCODING_JPEG` and `BGR24`→`IMAGE_ENCODING_BGR24` are mapped; `RGB24`/`YUV420P`/`H264_PACKET`/`UNKNOWN` are not): fails fast with `IllegalArgumentException` — no session is opened/touched, no gRPC call is made at all.
- **Malformed response** (e.g. a confidence/box value outside the domain's validated range, causing the `DetectionResult`/`Detection`/`BoundingBox` record constructors to throw): caught per-response and fails just that one future — never crashes the stream or affects other pending futures on the same session.
- **Port already closed**: `detect()` returns an already-failed stage (`IllegalStateException`) without touching any session.

## Stream lifecycle
Chose the **explicit hook** over idle eviction: `streamEnded(StreamId)` fails any still-pending futures for that stream with a `CancellationException`, half-closes the request observer (`onCompleted()`, best-effort), and drops the session. It is optional — an ended stream that's never told so just keeps its call open until `close()`; the next `detect()` for that id would simply keep reusing it. **A future wiring/resilience task should call `streamEnded` when a `StreamPipeline` stops**, so the CV-side call doesn't linger; this class does not (and cannot, from here) know when a stream ends on its own.

Note: `CompletableFuture.get()` reports a future completed via `completeExceptionally(new CancellationException(...))` by throwing that `CancellationException` **directly**, not wrapped in `ExecutionException` (a JDK special case for cancellation-shaped completions) — callers using `.get()`/`.join()` on a `streamEnded`-cancelled future should catch `CancellationException`, not `ExecutionException`.

## Conventions
- Plain class, no Spring, no framework annotations — matches every other adapter in this repo.
- Logging via `System.Logger` (not java.util.logging/SLF4J directly), same idiom as `adapter-publish-hls`'s `MediamtxStreamPublisher`: INFO on opening a stream, WARNING on transport error/unexpected completion (with cause), DEBUG for a best-effort `onCompleted()` failure during `endAndClose()`.
- Field-by-field proto↔domain mapping, no shared DTOs: `BoundingBox`/`Detection` both exist as distinct domain and generated-proto types in the same file; proto's `com.drones.vision.proto.v1.Detection`/`BoundingBox` are always referenced fully-qualified (or via `var`) at the two or three call sites that need them, since Java has no import aliasing and the domain names are the ones used everywhere else.
- `ModelRef` for a mapped `Detection` comes from the *response*-level `model_id`/`model_version` fields (the wire `Detection` message itself carries no model info) — the same `ModelRef` instance is reused for every detection in one response.

## Gotchas
- `io.grpc:grpc-core` (which carries `io.grpc.inprocess.InProcessServerBuilder`/`InProcessChannelBuilder`, used only in tests) is **not** pinned by this repo's own `${grpc.version}`=1.64.0 property — it comes from Spring Boot 4.1's imported `io.grpc:grpc-bom` (`spring-boot-dependencies` pins `grpc-java.version`=1.80.0), since the root pom only overrides `grpc-netty-shaded`/`grpc-protobuf`/`grpc-stub` explicitly. The `grpc-inprocess` test dependency in this module's `pom.xml` is deliberately left unversioned so it resolves via that same BOM to 1.80.0, matching whatever `grpc-core` version actually lands on the classpath — do not add an explicit version pinned to `${grpc.version}` here, it would mismatch. See `vision-proto/MODULE.md`'s Gotchas for the analogous (harmless) protobuf Java/Python version-line divergence.
- `FrameRequest`/`DetectionResponse` carry `timestamp_millis` (a `long`), so `VideoFrame.capturedAt()`'s sub-millisecond precision (`Instant.now()` often has nanosecond resolution) does not round-trip — expected and harmless in production (millisecond precision is enough), but tests must build frames with a millis-truncated `Instant` or round-trip equality assertions will flake.
- Proto `float` fields (`confidence`, `BoundingBox.x/y/width/height`) widen to `double` on the domain side with normal float-to-double imprecision (e.g. `0.1f` != `0.1d` bit-for-bit) — tests assert those fields with a small delta, not record `.equals()`.
- `pending.values().forEach(future -> future.completeExceptionally(cause))` (used by both the transport-error and `endAndClose` paths) relies on each future's own `whenComplete`-registered self-removal (attached when the future was created in `send()`) to actually drain the `pending` map — there is no separate explicit `pending.clear()`, so if that `whenComplete` registration were ever removed, failed sessions would leak map entries.

## Status
Implements `docs/MVP1-PLAN.md` §C7 bullet 2 in full: correlation, both failure paths (transport error + hung service), unsupported-format fast-fail, and the `streamEnded`/`close()` lifecycle. Not yet wired into `vision-app` (§C7 bullet 4) and not yet exercised against the real `cv-service` (only an in-process test double) — both are later tasks. `PixelFormat.YUV420P` (used by simulation sources) is not sendable to the CV service today; `StreamPipeline`'s frame path would need to convert to `BGR24`/`JPEG` before sampling for detection, or this adapter would need to grow a conversion step — whichever the wiring task decides.
