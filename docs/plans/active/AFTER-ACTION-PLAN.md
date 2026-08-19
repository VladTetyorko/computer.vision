# AFTER-ACTION-PLAN — the evidence package (row C13)

**Status:** authoritative spec (2026-08-19). Branch `feat/after-action` off `master` @ `554fc105`.
**Context:** [AFTER-ACTION-CONTEXT.md](AFTER-ACTION-CONTEXT.md) — what exists, verified. Not repeated here.
**Row:** `docs/main/MASTER-MATRIX.md` §5 C13 · S–M ~40 h · D0 · €0 · depends on recording (HAVE) + I7 passport (HAVE).
**Persona:** the **referee/judge** of `docs/main/UX-DESIGN.md` §2 — the one person who opens this app
to answer *"what actually happened, and can you prove it?"*

---

## 1. The one-sentence product

**One request against one finished flight returns everything the platform knows about it, in open
formats, with an explicit account of what is missing and what is approximate.**

The second half is the feature. Any system can zip up what it has; the thing a referee needs is a
package that cannot quietly omit. Every design decision below serves that.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| **D1** | Assembled in `vision-api`, in a **framework-free** `AfterActionAssembler`, not in any context module | Context §3.1 — `vision-events` is a sink and stays one; an evidence bundle is a presentation concern. Precedent: `api/support/RemediationOrchestrator` |
| **D2** | **Two endpoints**: a JSON manifest and a ZIP archive | You see what you would get — including the gaps — before downloading. The manifest is also what the UI renders |
| **D3** | A part is never silently absent. Every part reports a `state` of `PRESENT` \| `ABSENT` \| `TRUNCATED` \| `FORBIDDEN`, each with a human-readable `note` | The whole point of the row. `ABSENT` and `FORBIDDEN` are different facts and a referee must be able to tell them apart |
| **D4** | Video is **referenced, never embedded** | It lives in mediamtx, it is gigabytes, and a stale URL is more honest than a copy that silently diverged |
| **D5** | Marks are filtered to `createdAt ∈ [startedAt, endedAt]` **and** carry a note saying a mark is not bound to a flight | Context §3.2 — the link does not exist in the model; claiming it would be a lie in an evidence document |
| **D6** | Built through the **requesting viewer's** scope, and the manifest names that viewer | Context §3.3. A package is evidence *as seen by someone*; anonymising that would misrepresent it |
| **D7** | Telemetry thinning is **detected and declared**, not fixed | Context §3.4. `count == requested maxPoints` ⇒ thinned. Fixing it needs a time-bounded query on another context's port — a separate wave, recorded in §7 |
| **D8** | No new Maven dependency. `java.util.zip`, hand-written CSV, hand-written GeoJSON, Jackson 3 for JSON | 40 h means assembly, and every format here is a few lines |

## 3. FROZEN WIRE CONTRACT

Both waves code against this verbatim. **W1 implements it; W2 consumes it. Neither may change it** —
if a wave believes it is wrong, it stops and reports rather than adapting.

### 3.1 `GET /api/assets/{assetId}/usages/{usageId}/after-action` → `200 application/json`

```json
{
  "assetId": "d971a335-...",
  "assetName": "Rover-1",
  "usageId": "6b1f...",
  "startedAt": "2026-08-19T09:14:02Z",
  "endedAt": "2026-08-19T09:48:31Z",
  "open": false,
  "generatedAt": "2026-08-19T15:02:11Z",
  "scopedTo": "referee@example.org",
  "parts": [
    { "part": "telemetry",  "state": "TRUNCATED", "count": 2000,
      "note": "thinned to the 2000-point ceiling; the source series is larger" },
    { "part": "detections", "state": "PRESENT",   "count": 214,  "note": null },
    { "part": "marks",      "state": "PRESENT",   "count": 6,
      "note": "marks created inside the flight window and visible to you; a mark is not bound to a flight" },
    { "part": "recording",  "state": "ABSENT",    "count": 0,
      "note": "no recording is configured for this stream" },
    { "part": "passport",   "state": "PRESENT",   "count": 1,    "note": null },
    { "part": "audit",      "state": "FORBIDDEN", "count": 0,
      "note": "your role cannot read the audit trail" }
  ],
  "complete": false,
  "caveats": [
    "telemetry was thinned to 2000 points; this package is not a raw log",
    "no recording is configured for this stream"
  ]
}
```

**Rules.**
- `parts` is always all six, always in that order — `telemetry, detections, marks, recording, passport, audit`. A part is never omitted; `state` carries the truth.
- `state` ∈ `PRESENT | ABSENT | TRUNCATED | FORBIDDEN`. `count` is `0` for `ABSENT`/`FORBIDDEN`.
- `note` is `null` only when `state == PRESENT` and there is nothing to qualify.
- `complete == true` **iff** every part is `PRESENT`. `caveats` is the ordered list of every non-null `note` from a non-`PRESENT` part — empty when `complete`.
- `endedAt` is `null` and `open` is `true` for a still-running flight; the package is still served, with `endedAt` treated as *now* for windowing, and a caveat saying so.
- `@JsonInclude(NON_NULL)` is **not** used here: `endedAt: null` and `note: null` are meaningful and must appear on the wire.

### 3.2 `GET /api/assets/{assetId}/usages/{usageId}/after-action/archive` → `200 application/zip`

`Content-Disposition: attachment; filename="after-action-<usageId>.zip"`

Entries, always all eight, in this order. **A part that is `ABSENT` or `FORBIDDEN` still gets its
file** — containing a single explanatory line, never a zero-byte file and never a silent omission:

| Entry | Format |
|---|---|
| `manifest.json` | byte-identical to §3.1 |
| `README.txt` | plain text: what this package is, that video is referenced not embedded, and every caveat repeated |
| `telemetry.csv` | `at,latitude,longitude,altitudeMeters,groundSpeedMps,headingDegrees,batteryPercent` |
| `detections.csv` | `capturedAt,label,confidence,x,y,width,height,trackId` |
| `marks.geojson` | RFC 7946 `FeatureCollection`; `Point` geometry; properties `id,kind,label,status,source,createdAt,verification` |
| `passport.json` | the existing `FlightPassportResponse` payload verbatim |
| `audit.csv` | `at,actor,action,entity,entityId,detail` |
| `recording.txt` | `url`, `start`, `durationSeconds` one per line — or a single line stating there is none |

CSV: RFC 4180, `\r\n`, header row always present, `"` doubled inside quoted fields, UTF-8, no BOM.
Instants ISO-8601 UTC. Empty numeric cells are empty, never `0`.

### 3.3 Errors

| Case | Response |
|---|---|
| unknown `usageId` | `404 {"error":"NOT_FOUND","message":"No usage <id>"}` |
| `usageId` does not belong to `assetId` | `404`, same shape — never leak that it exists elsewhere |
| asset not visible to the viewer | `404`, same shape — **not** `403`; existence is itself scoped |
| caller may see the asset but not export | `403 {"error":"FORBIDDEN","message":"..."}` |

`ErrorResponse(String error, String message)` is the real envelope, from `ApiExceptionHandler`.
Do not invent a `detail` field — a previous wave lost a day to exactly that.

## 4. Waves

Disjoint file scopes. **W1 and W2 run in parallel against §3**, which is why §3 is frozen.

### W1 — backend (`spring-integrator`)

**Scope, exclusive:**
```
station/vision-api/src/main/java/com/drones/vision/api/support/afteraction/**   (new)
station/vision-api/src/main/java/com/drones/vision/api/controller/AfterActionController.java   (new)
station/vision-api/src/main/java/com/drones/vision/api/dto/AfterAction*.java   (new)
station/vision-api/src/test/java/com/drones/vision/api/**/AfterAction*   (new)
station/vision-app/src/main/java/com/drones/vision/app/config/wiring/   (bean only)
station/vision-api/MODULE.md, station/vision-app/MODULE.md
```

1. `AfterActionAssembler` — **no Spring annotations, no Jackson**, constructor-injected collaborators only, so its tests are plain JUnit with hand fakes. It returns an `AfterActionPackage` domain-ish record; DTO mapping is the controller's job.
2. Part resolution, one method per part, each returning `(state, count, note)`. `FORBIDDEN` is produced by catching the authorization failure the underlying service already throws — never by re-deriving the policy here.
3. Thinning detection per D7: request `maxPointsCeiling`, compare the returned count to it.
4. `AfterActionController` — the two endpoints, `@PreAuthorize` consistent with `UsageTimelineController`, ZIP streamed via `StreamingResponseBody` (never buffered whole in memory).
5. Wire the bean. Feature ships **on** — it exposes nothing that was not already exposed, only re-shaped, so there is no flag.

**Done when:** `./mvnw -B -pl station/vision-api,station/vision-app -am test` green in the **foreground**, every §3.3 error case has a test, and the manifest for a usage with no recording and no passport is asserted byte-for-byte.

### W2 — web (`web-ui`)

**Scope, exclusive:**
```
station/vision-web/src/app/core/after-action/**   (new)
station/vision-web/src/app/features/replay/**   (the existing replay page — additive only)
station/vision-web/MODULE.md
```

1. `after-action-logic.ts` — pure: manifest → view model, caveat ordering, part iconography. Fully spec'd.
2. A panel on the existing replay/usage page: the six parts with their state, the caveats listed in plain language, and a **Download package** button.
3. The button is a plain anchor to §3.2. Do **not** fetch the ZIP into memory.
4. `TRUNCATED` and `FORBIDDEN` render as visible, non-alarming statements of fact — not as errors, not hidden behind a disclosure.
5. Three-file components (`.ts`/`.html`/`.css`), never inline templates.

**Done when:** `npx tsc --noEmit` clean on both tsconfigs, `npm test` green with new specs, `ng build --configuration production` green (two pre-existing budget warnings are expected and are not yours).

### W3 — reconciliation (orchestrator, after W1+W2)

MODULE.md updates verified, `MASTER-MATRIX.md` C13 struck, this plan marked done with defects found.

## 5. Hazards, named up front

1. **`UsageTimeline` thinning silently halves an evidence file.** D7. If a wave finds itself writing a package that does not mention this, it has built the wrong thing.
2. **Marks have no flight link.** D5. Do not join on anything; filter on time and say so.
3. **Audit may legitimately refuse.** `FORBIDDEN` is a correct outcome, not an error to swallow.
4. **A still-open usage is a valid input.** Do not 404 it.
5. **ZIP entry order and presence are part of the contract.** A missing entry is a silent omission — the exact failure this row exists to prevent.
6. **`vision-events` must not gain a dependency.** If a wave feels it needs one, it stops and reports.

## 6. Verification, non-negotiable

Foreground builds only — a backgrounded build dies with the agent's turn and the wave is reported
green having never compiled. Never grep a Maven log anchored on `[INFO]`: a failing surefire summary
prints as `[ERROR] Tests run:`. Use `-am` when building with `-pl`, or stale `~/.m2` sibling jars
resolve instead of your changes.

## 7. Follow-ups this cycle deliberately does not do

- A time-bounded `TelemetryRepositoryPort#findByUsage(usageId, from, to)` — the real fix for §3.4, and a `vision-flight` change.
- A raw (unthinned) telemetry export path.
- `UsageTimeline#detections`'s stale field javadoc ("always empty today") — a one-line fix in another module's scope.
- Signing or hashing the package. A real evidence chain wants a digest; that is a decision about legal posture, not an afternoon's work.
