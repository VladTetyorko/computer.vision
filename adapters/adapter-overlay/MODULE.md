# adapter-overlay

Frame annotation adapter: burns detection boxes/labels and a telemetry OSD onto video frames, pure Java2D.

**Depends on:** vision-domain · **Used by:** none yet (`StreamPipeline` wiring is `docs/MVP1-PLAN.md` §C8 bullet 2, a later task)
**Build/test:** `./mvnw -B -pl adapters/adapter-overlay test` — 10 tests, `Java2DOverlayRendererTest`, no docker/network needed.

## API surface
### `com.drones.vision.adapter.overlay`
- `final class Java2DOverlayRenderer implements OverlayPort` — plain class, no Spring, no constructor args (stateless).
  - `VideoFrame render(AnnotatedFrame annotated)` — the `OverlayPort` method. Zero-cost path: if `detections` is empty and `telemetry` is `null`, returns `annotated.frame()` itself (same instance, no copy). Otherwise dispatches on `frame.format()`:
    - `BGR24` — pixels copied into a fresh `BufferedImage.TYPE_3BYTE_BGR` backing array (packed BGR, no row padding — the same layout `adapter-publish-hls`'s `FrameConverter` assumes), drawn on, returned as a new `BGR24` frame.
    - `JPEG` — decoded via `ImageIO.read`, drawn on, re-encoded via an explicit `ImageWriteParam` at quality `0.8f`.
    - Anything else (`RGB24`/`YUV420P`/`H264_PACKET`/`UNKNOWN`), or a JPEG payload `ImageIO` can't decode/re-encode — **passed through unchanged, never throws**. Overlay is a cosmetic add-on; it must never be the reason a stream breaks.
  - `static Color colorForLabel(String label)` — package-private, hashes `label` (via `String.hashCode()`, JDK-specified formula, stable across JVM runs) into an 8-color fixed palette (`Math.floorMod(label.hashCode(), 8)`). Exposed at package level purely so tests can assert color assignment without rendering a full frame.

## Conventions
- **Never mutates the input frame.** `BGR24`: the new `BufferedImage`'s backing byte array is freshly allocated and only ever `.get()`-copied from `frame.data()` (a read-only duplicate per `VideoFrame`'s own contract), so drawing on it can't reach back into the caller's buffer. `JPEG`: `ImageIO.read` always returns a fresh `BufferedImage` decoded from a copy of the bytes, never aliasing anything.
- **Box → pixel conversion:** each `BoundingBox` component is independent (`x`,`y`,`width`,`height` all `[0,1]` on their own axis; the domain does not guarantee `x+width <= 1`). Pixel `x`/`y` are clamped to `[0, dimension]`; pixel `w`/`h` are `Math.min(round(component * dimension), dimension - pixelCoordinate)` so a box is silently clipped to the frame, never thrown on, even sitting exactly on the `1.0` edge (a zero-size clipped box is simply not drawn — `w <= 0 || h <= 0` short-circuits).
- **Box border is drawn as four filled strips (`drawInsetBorder`), not a `java.awt.Stroke`.** A centered `BasicStroke` straddles the path and its exact rasterized pixels depend on anti-aliasing/line-join rules; an inset border (`fillRect` on each of the four edges, entirely inside `[x,x+w) x [y,y+h)`) is byte-exact and independent of any rendering hint — this is what makes the box-corner pixel-probe tests deterministic. Stroke thickness: `max(2, min(frameWidth, frameHeight) / 200)`.
- **Label text** (`"person 0.87"`, `%.2f` confidence) is drawn on a filled bar in the label's own color, positioned above the box; if there's no room above (`boxY - barHeight < 0`, i.e. the box touches/is near the frame top), the bar falls back to the box's own top-left corner instead of going negative. Font: `SansSerif BOLD`, size `max(12, frameHeight / 45)` (16px at 720p), `KEY_TEXT_ANTIALIASING` on. Text color is black or white, whichever contrasts more with the label color (luminance threshold 140, standard `0.299r+0.587g+0.114b`).
- **Telemetry OSD** is a small monospaced (`size 12`) block anchored at `(4,4)` with a semi-transparent dark background (`rgba(0,0,0,160/255)`, alpha-blended so it also works over non-black frames). One line per present field, each independently skippable: `lat`/`lon` (5 decimals, combined on one line when both present), `alt …m` (1 decimal), `batt …%` (0 decimals). No lines → nothing drawn (distinct from the top-level "no detections and no telemetry" fast path — this covers "telemetry object present but every field is `null`").
- Rendering hints: `KEY_ANTIALIASING` intentionally left unset (shapes are axis-aligned `fillRect`s, always crisp regardless); only `KEY_TEXT_ANTIALIASING` is turned on, for readable label/OSD text.

## Gotchas
- `BufferedImage.TYPE_3BYTE_BGR`'s raster backing array is genuinely byte-for-byte `PixelFormat.BGR24` (B,G,R per pixel, packed, no stride padding) — this is what lets the BGR24 path skip any per-pixel conversion loop and just `System`-style bulk-copy into `((DataBufferByte) image.getRaster().getDataBuffer()).getData()`. This assumption is shared with `adapter-publish-hls`'s `FrameConverter` (see its Javadoc) — if that platform-wide convention ever changes, both adapters need to change together.
- A decoded JPEG `BufferedImage` is assumed opaque (no alpha) for re-encoding; `encodeJpeg` defensively flattens onto `TYPE_INT_RGB` first if `getColorModel().hasAlpha()` is ever true, since JPEG writers throw `IIOException` on an alpha-bearing image type — belt-and-braces, not expected to trigger in practice (JPEG has no alpha channel).
- Label bar / OSD block *pixel positions* are not asserted exactly in tests (only their fill color at deterministic, geometry-only-dependent probe points is) — their width/height depend on `FontMetrics`, which can vary slightly by JDK/fontconfig; only the box border (pure integer geometry, no font involved) is probed at exact pixel coordinates.

## Status
`Java2DOverlayRenderer` implements `docs/MVP1-PLAN.md` §C8 bullet 1 in full (box burn-in, per-label palette, telemetry OSD, pass-through-never-throws for unsupported formats). Not yet wired into `StreamPipeline` (§C8 bullet 2, a later task) or the `PipelineConfig.overlayTelemetry` config flag that will gate the OSD in the pipeline — this renderer itself always draws the OSD when `telemetry != null` is passed to it; the pipeline-side decision of *whether* to pass telemetry is out of scope here.
