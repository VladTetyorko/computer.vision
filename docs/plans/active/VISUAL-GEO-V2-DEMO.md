# Visual geolocation v2 — demo transcript (wave H7)

Spec: [VISUAL-GEO-V2-PLAN.md](VISUAL-GEO-V2-PLAN.md) §5 H7. This document is a **record of what was
actually executed** on 2026-08-20, not a script of what should work. Everything marked ✅ was run
against a live server and its real output pasted in. Everything marked ⛔ was **not** run, or could
not complete, and says why. Failures below are findings, not obstacles that were worked around.

The precedent and the mandate is G6's closing line in
[FIXED-CAMERA-GEO-DEMO.md](FIXED-CAMERA-GEO-DEMO.md): *five waves of careful reading and a green
full-reactor build did not catch it; one `curl` did.* That happened again here, twice.

---

## 1. Scoreboard

| Step (§5 H7) | What | State |
|---|---|---|
| 1 | Stack up — postgres + mediamtx + cv-service + vision-app | ✅ executed (pragmatism recorded in §2) |
| 2 | Flag off: all six `/api/geo/**` endpoints answer 409 | ✅ executed, all six pasted |
| 3 | Flag on, POST the Maidan region, watch to done, paste `RegionResponse` | ✅ executed; phase *sequence* only partly observable (§4.2) |
| 4 | Clip as a stream + SITL homed at the clip's coordinates | ⚠️ **half executed** — clip yes, **SITL failed** (Defect 3), scripted telemetry substituted |
| 5 | Observe corrections + SSE with real `inlierCount`/`radiusMeters` | ✅ executed — and the honest answer is **`NO_FIX`, 353/353** |
| 6 | Divergence: offset 300 m, latch the alarm, `POSITION_DIVERGENCE` | ⛔ **not reachable** — no `CONFIRMED` fix can exist on this footage (§6) |
| 7 | `corrections?usageId=` after stopping + replay page | ✅ executed at API level; browser check not run (§7) |

**Four defects found**, listed in §8. Two of them are the kind that a green test suite cannot see:
one silently ends geolocation forever, one silently disables rectification on every gimbal-less
aircraft.

---

## 2. ✅ Step 1 — the stack, and what actually ran

Honesty about composition, because it is not what §5 step 1 literally says:

| Component | What ran | Note |
|---|---|---|
| postgres | **Already running**, host systemd `postgresql` on `:5432` | The app's own default `jdbc:postgresql://localhost:5432/vision`. Not started by this demo, not torn down by it. |
| mediamtx | **Already running**, `vision-mediamtx-1` (`bluenviron/mediamtx:1.19.3`) on `:8554` | From the pre-existing `vision` compose project. Reused, never `down`ed. |
| cv-service | **Host venv, not the Docker image** — `CV_PORT=50052 .venv/bin/python -m cv_service.grpc.server` | The image build pulls torch + ultralytics + openvino (several GB). The plan permits this; it is recorded because it is a deviation. |
| vision-app | Built jar, `java -jar station/vision-app/.../vision-app-0.0.1-SNAPSHOT.jar --server.port=8080` | Web bundle **included** (see below), auth off by default so bare `curl` works. |
| SITL | `infra/sitl/up.sh`, image `vision-sitl:4.7.0` already built locally | Started, and **failed** — §5.1. |

`vision.auth.enabled` defaults to `false`, so the Spring Security chain is permit-all and the dev
principal is an unbounded admin. Every `curl` below is bare, exactly as G6's was, and for the same
reason — a property, not a profile.

A second cv-service (pid 38643, port 50051) was already running on this host from an earlier
session, and **predated the H4 geo commit**. Rather than kill someone else's process, this demo ran
its own on `:50052` and pointed the station at it with `--vision.cv.endpoint=localhost:50052`. The
stale one was left untouched and is still running.

Build, from a clean checkout of `feat/visual-geo-v2`:

```
$ ./mvnw -B -pl station/vision-app -am package -DskipTests
[INFO] vision-web ......................................... SUCCESS [ 14.983 s]
[INFO] vision-app ......................................... SUCCESS [  3.954 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  33.858 s
```

The jar on disk before this was from 14:03 the previous day — **older than H5's own commit**, so it
did not contain a single geo endpoint. Anyone re-running this demo against a stale jar will get 404s
and conclude the feature does not exist. Rebuild first.

On first boot the migration ran, unprompted and unconditionally:

```
Migrating schema "public" to version "23 - track corrections"
Successfully applied 1 migration to schema "public", now at version v23
```

## 3. ✅ Step 2 — the feature is invisible until switched on

Started on default config (`vision.geo.visual.enabled` absent ⇒ `false`). All six §3.3 endpoints:

| Request | Response |
|---|---|
| `GET /api/geo/regions` | `409 {"error":"CONFLICT","message":"visual geolocation is disabled (vision.geo.visual.enabled)"}` |
| `POST /api/geo/regions` | `409` — byte-identical body |
| `DELETE /api/geo/regions/kyiv-maidan` | `409` — byte-identical body |
| `GET /api/geo/regions/kyiv-maidan/progress` | `409` — byte-identical body |
| `GET /api/geo/corrections/live` | `409` — byte-identical body |
| `GET /api/geo/corrections?usageId=…&limit=2000` | `409` — byte-identical body |

The 409 is the *first* statement of every method, so a malformed body never gets a 400 that would
leak the shape of a disabled feature. Verified structurally, not just by response:

- `grep -icE "visualgeorunner|visual geolocation" app.log` → **0**
- thread named `visual-geo-runner` → **absent**
- `select count(*) from track_corrections` → **0 rows, table exists**

That last line is the interesting one. The *schema* ships unconditionally while the *behaviour* does
not — V23 runs whether or not the flag is on. That is the right split (a flag flip must never need a
migration), and it is worth stating because "the table is there" is otherwise easy to misread as
"the feature is on".

## 4. ✅ Step 3 — a region, ingested for real from live imagery

Restarted with `--vision.geo.visual.enabled=true`. The 409s became `{"regions":[]}` and
`{"corrections":[]}` — the empty list is a correct 200, proxied from cv-service's `ListRegions`.
Esri was reachable (`HTTP 200 in 0.176s`), so the tiles below are real satellite imagery fetched at
demo time, not a fixture.

```
$ curl -X POST /api/geo/regions -d '{"name":"kyiv-maidan","north":50.45488102098095,
    "south":50.444387601083015,"east":30.532379150390625,"west":30.515899658203125,"zoom":17}'
202 {"regionId":"kyiv-maidan","name":"kyiv-maidan","zoom":17,…,"status":"BUILDING"}
```

Final `RegionResponse`, verbatim:

```json
{
  "regionId": "kyiv-maidan", "name": "kyiv-maidan", "zoom": 17,
  "north": 50.45488102098095, "south": 50.444387601083015,
  "east": 30.532379150390625, "west": 30.515899658203125,
  "status": "READY", "tileCount": 49, "neverAcceptCells": 0,
  "encoderId": "eigenplaces_r18_512",
  "acceptSimilarity": 0.8700000047683716, "acceptMargin": 0.0,
  "holdoutRecallAt1": 0.25, "holdoutMedianErrorMeters": 194.48715209960938,
  "builtAt": "2026-08-20T04:26:19.894Z"
}
```

`holdoutRecallAt1: 0.25` and `holdoutMedianErrorMeters: 194.5` reproduce H0's spike numbers for this
region **through the production path**, which is the useful result: the ported pipeline measures the
same thing the bake-off did. This is deliberately one of the hard regions.

### 4.1 Two H4 claims, verified on disk rather than believed

§9.10 items 6 and 8 assert that the persisted index covers the **full** tile set (not the 90 %
calibration split — §9.8's Defect 1) and that a per-cell never-accept table now exists. Both checked
directly against the artefacts the run produced:

```
kyiv-maidan:   descriptors shape=(49, 512)  dtype=float16  tileCount=49   full set indexed: True
kyiv-pozniaky: descriptors shape=(252, 512) dtype=float16  tileCount=252  full set indexed: True
```

and `kyiv-pozniaky` reported `"neverAcceptCells": 8`. Both production fixes are real, not aspirational.

### 4.2 What could *not* be observed: the phase sequence

§5 step 3 asks to watch `receiving → extracting → encoding → indexing → calibrating → done`. For
Maidan (49 tiles) the **entire ingest finished inside the first poll** — the first `progress` call
already returned `"phase":"done","state":"SUCCEEDED"`. So a second, larger region (`kyiv-pozniaky`,
252 tiles) was ingested purely to observe the sequence, polling every 0.4 s:

```
04:26:50.49  {"phase":"receiving",  "done":0,"total":0,  "state":"RUNNING"}
04:27:05.28  {"phase":"encoding",   "done":1,"total":227,"state":"RUNNING"}
04:27:10.67  {"phase":"calibrating","done":1,"total":25, "state":"RUNNING"}
04:27:11.57  {"phase":"done",       "state":"SUCCEEDED","stats":{"tileCount":252,"neverAcceptCells":8,…}}
```

Four of six phases seen. **`extracting` and `indexing` were never observed** — at these region sizes
they are shorter than any practical poll interval. This is not a defect; it is a note for anyone who
writes a UI progress bar against these six strings and expects to render all of them.

`kyiv-pozniaky` was then deleted (`DELETE … → 204`, and `GET /api/geo/regions` confirmed only
`kyiv-maidan` remained) so that the localisation run below had exactly **one** `READY` region —
§9.10 item 7 degrades both IPM rectification and the sequence filter off when `region_id=""`
resolves more than one.

## 5. ⚠️ Step 4 — the clip became a stream; SITL did not become a drone

The 12 committed Pexels Maidan frames (`cv/cv-service/spikes/geo/fixtures/maidan-video-frames/`,
1280×720, ground truth **50.4502431 / 30.5240622** from their `manifest.jsonl`) were encoded into a
12 s / 25 fps H.264 clip and registered as a `file`-protocol device, alongside a `mavlink` telemetry
device, in one call:

```
201 {"assetId":"8f90acbe-…","devices":[
      {"name":"maidan-clip","protocol":"file","uri":"file:///…/maidan.mp4","options":{"loop":"true"}},
      {"name":"sitl-telemetry","protocol":"mavlink","uri":"udp://0.0.0.0:14550","options":{"sysid":"1"}}]}

$ curl -X POST /api/assets/{id}/stream
201 {"streamId":"8dae3f4e-…","viewUrl":"/hls/8dae3f4e-…/index.m3u8","burnedIn":true}
```

The full chain came up, each link verified rather than assumed:

- stream state `LIVE`
- **mediamtx really carries it**: `ffprobe rtsp://localhost:8554/8dae3f4e-…` → `h264 1280x720`.
  This matters because the geo session's `source_url` is literally `rtsp-base + "/" + streamId`
  (O12) — the worker pulls from mediamtx, so a stream that never reaches mediamtx produces a
  session pointed at nothing.
- a usage opened itself: `GET /api/usages/by-stream/{streamId}` → `dd240210-…`
- the runner opened a session:
  `GeolocationSession : Opening geolocation stream for StreamId[value=8dae3f4e-…]`

### 5.1 ⛔ SITL never produced a position — Defect 3

`SITL_HOME="50.4502431,30.5240622,180,0" TAKEOFF_ALT_M=120 infra/sitl/up.sh 1` started
`vision-sitl-1` (image `vision-sitl:4.7.0`, already built) in about 60 s. It armed and took off. It
never delivered a position:

```
[autofly] Arm: Gyros inconsistent
[autofly] Arm: AHRS: waiting for home
[autofly] Arm: Need Position Estimate      (x9)
[autofly] Arming motors
[autofly] armed, taking off
[autofly] takeoff did not reach target altitude in time, continuing anyway
```

The platform therefore saw telemetry with a flight state and **no position at all**:

```json
{"deviceId":"5c5ba055-…","at":"2026-08-20T04:31:27Z",
 "flightState":{"firmware":"ardupilot","mode":"Guided","armed":true,"failsafe":false}}
```

`lastKnownPosition` stayed `null`, and **0 of 99** corrections carried a raw fix. Confirmed at the
wire with a second, independent SITL instance on a spare port sniffed by `pymavlink`: over 25 s it
emitted `{'HEARTBEAT': 26, 'TIMESYNC': 3}` and nothing else — no `GLOBAL_POSITION_INT` — and it kept
emitting nothing else even after a `REQUEST_DATA_STREAM(MAV_DATA_STREAM_ALL)`. The EKF never got a
position estimate, so ArduPilot had no position to stream. Host load was ~5.6 on 12 cores with
cv-service, ffmpeg and the app all running; SITL's soft-real-time sim loop is a plausible casualty,
but this was **not** proven and should not be assumed away.

Per §5 step 4's own escape hatch, SITL was torn down and replaced with a scripted `pymavlink`
sender (`scratchpad/telemetry_sender.py`) emitting `HEARTBEAT`, `GLOBAL_POSITION_INT`, `GPS_RAW_INT`,
`ATTITUDE`, `VFR_HUD` and `GIMBAL_DEVICE_ATTITUDE_STATUS` at 5 Hz, homed at the clip's ground truth.
Raw positions immediately began attaching to corrections (`"rawLatitude": 50.4502431`).

**This is the plan's §9.9 amendment 5 fiction, and it is worth restating in the plain:** the video is
real footage of Maidan; the telemetry is asserted, not flown. The aircraft's reported position is
correct *by construction* because a script says so. Real footage with real logged telemetry has
still never been measured together, and this demo did not close that gap — it re-opened it one level
further, because even the SITL half was substituted.

## 6. ✅ Step 5 / ⛔ Step 6 — the system refuses, honestly and unanimously

Corrections flowed at roughly one per 2 s runner tick. SSE, `curl -N '/api/live?topics=geo:<assetId>'`,
delivered the frozen envelope with the `CorrectionResponse` verbatim as payload:

```json
id:155
data:{"seq":155,"assetId":"8f90acbe-…","type":"geo","payload":{
  "assetId":"8f90acbe-…","usageId":"dd240210-…",
  "frameAt":"2026-08-20T04:30:31.833Z","computedAt":"2026-08-20T04:30:33.319639890Z",
  "status":"NO_FIX","source":"VISUAL_HEAVY","divergent":false,
  "regionId":"kyiv-maidan","tileId":"17/76646/44198",
  "matchCount":295,"inlierCount":15,"inlierRatio":0.05084745762711865,
  "rerankMargin":0.06666666666666667,"reprojectionRmsPixels":2.155652457034006,
  "rectified":false,"sequenceSpreadMeters":66.33958174096793,"sequenceUpdates":75,
  "refusal":"G-b_inlier_ratio"}}
```

`radiusMeters` and `latitude`/`longitude` are **absent**, not null — §3.3's `@JsonInclude(NON_NULL)`
contract honoured, and the one record serving a `NO_FIX` row carrying only its refusal.

**Every single frame refused. 353 of 353.**

```
 status |     refusal      | count
--------+------------------+-------
 NO_FIX | G-b_inlier_ratio |   353
```

`G-b_inlier_ratio` is `rerank.py`'s gate G-b: MAGSAC kept fewer than `CV_GEO_MIN_INLIER_RATIO`
(default **0.35**) of the winning candidate's correspondences. Measured across the run:

| | rows | min ratio | mean | max | mean matches | mean inliers |
|---|---|---|---|---|---|---|
| unrectified | 323 | 0.0429 | **0.0610** | 0.0894 | 280.5 | 17.0 |
| rectified | 30 | 0.0610 | **0.0833** | 0.1212 | 198.7 | 16.2 |

This is exactly what the plan predicted and is therefore **not a disappointment — it is the design
working.** O1 says XFeat's default "stands on latency grounds alone; H4 should not assume XFeat
delivers working single-frame fixes on real footage," and §9.9 amendment 3 declines to promise
single-frame `CONFIRMED` on real footage. Measured in production: XFeat on this clip agrees
geometrically at ~6 % and the gate refuses all of it. Nothing wrong was ever published. A system
that had "worked" here would have been the bug.

### 6.1 ⛔ The divergence alarm cannot be reached on this footage

The telemetry was offset 300 m north (`50.4502431 → 50.4529380`). The offset is visibly attached:

```json
"rawLatitude":50.452938,"rawLongitude":30.5240622,"divergent":false, … "refusal":"G-b_inlier_ratio"
```

and after 45 s on the always-on `event` topic: **0 `POSITION_DIVERGENCE` events**, 0 divergent rows,
0 rows with a separation, 0 `CONFIRMED`.

That is correct behaviour, and `DivergenceRule`'s javadoc says why in one line: *"Only `CONFIRMED`
corrections participate at all."* No `CONFIRMED` fix ⇒ no arming ⇒ no alarm, forever.

To find out whether the alarm path works **at all**, a separate, deliberately-instrumented run was
made with the geometric gates opened (`CV_GEO_MIN_INLIER_RATIO=0.02`, `MIN_RERANK_MARGIN=0.0`,
`MAX_REPROJECTION_RMS_PX=20`) and the Java radius/agreement gates widened. **This is not the
shipping configuration and no conclusion about accuracy is drawn from it.** It got further:

```
  status  |       refusal        | count | with separation
----------+----------------------+-------+----------------
 NO_FIX   | G-f_footprint_sanity |    14 |    0
 PROBABLE |                      |    36 |   36
```

Separations were computed for real — min 173 m, mean 600 m, max 1158 m against a mean σ of 10.1 m,
which comfortably qualifies under `sigma: 3.0`. And still no alarm, because status stopped at
`PROBABLE`. `DefaultTrackCorrectionService` is explicit: `CONFIRMED` additionally requires
`evidence.sequenceConverged() && evidence.cellCalibrated()`, and the sequence filter does not
converge on this clip — precisely O4's measured finding (§9.5) that a geometric field never converges.

So the chain is: no sequence convergence → no `CONFIRMED` → no divergence → no `POSITION_DIVERGENCE`.
Every link is deliberate. But the consequence must be stated plainly for H8:

> **`EventType.POSITION_DIVERGENCE` has never once been emitted by a running system.** The enum
> value exists, the rule is unit-tested, and the end-to-end path is unexecuted.

Two smaller things the instrumented run exposed, both to the feature's credit: the widened run
produced fixes claiming a **1.4 m mean radius**, which the shipping `gate.min-radius-meters: 5.0`
would reject outright as a claim too good to believe — D5's floor is not decoration. And a second
refusal string, `G-f_footprint_sanity`, appeared once fixes got past G-b, so the later gates are
live too.

## 7. ✅ Step 7 — replay reads back correctly

After stopping the stream (`204`), which closed the usage:

```
$ curl '/api/geo/corrections?usageId=dd240210-…&limit=2000'
rows: 323          ordering oldest->newest: True

$ curl '/api/geo/corrections?usageId=778fef3a-…&limit=2000'
rows: 41           rectified true: 41
```

Boundary behaviour, all as frozen in §3.3:

| Request | Response |
|---|---|
| `…&limit=0` | `400 {"error":"BAD_REQUEST","message":"limit must be within [1,10000]: 0"}` |
| `…&limit=10001` | `400 {"error":"BAD_REQUEST","message":"limit must be within [1,10000]: 10001"}` |
| `?usageId=<unknown uuid>` | `404 {"error":"NOT_FOUND","message":"unknown usage: 99999999-…"}` |

`GET /api/geo/corrections/live` still served the last correction after the stream stopped — correct,
since "latest per asset" is not "latest while streaming".

⛔ **The replay page was not opened in a browser.** The SPA is served (`GET /` → `200 text/html`,
`<title>Vision</title>`, `<app-root>`, web bundle rebuilt in this very build), so the check was
possible; it was traded for the deeper API-level investigation in §6.1. H6's map rendering of a
corrected track therefore remains **visually unverified**. Given that every correction on this
footage is a `NO_FIX` with no position, the page would in any case have had nothing to draw — which
is itself the thing worth checking, and was not.

---

## 8. Defects found

**1 — a transient cv-service outage ends geolocation permanently, and silently.**
When cv-service restarted, `CvChannelSupervisor` did its job and recovered the channel in 12 s
(`cv-service at localhost:50052 reachable again after 12s (3 reconnect attempt(s))`). Geolocation
never came back. Zero corrections for the following 5 minutes while the stream stayed `LIVE` and the
usage stayed open; the last row was frozen at the moment of the outage.

Cause, `station/vision-app/.../geo/VisualGeoRunner.java`: `LatestFixSubscriber.onError` only logs.
It never removes the asset from `openSessions`. `ensureSessionOpen` then hits

```java
OpenSession existing = openSessions.get(assetId);
if (existing != null && existing.streamId().equals(streamId)) {
    return;                       // <-- a dead session looks exactly like a healthy one
}
```

and returns early on every subsequent tick, forever. Recovery required stopping and restarting the
stream. The failure is invisible from the outside: no error surfaces, the stream is healthy, the
corrections simply stop. Note the irony — the channel supervisor exists precisely so cv-service can
bounce, and the layer above it cannot take advantage.

**2 — camera pitch never crosses the wire for any aircraft without a gimbal, so rectification
silently never runs.**
`cv/grpc/.../GeoFixCodec.java` sources `camera_pitch_deg` exclusively from
`Attitude.gimbalPitchDegrees()`. In `drone-link/mavlink`, that field is populated **only** by
`GIMBAL_DEVICE_ATTITUDE_STATUS` (#285) or the deprecated `MOUNT_ORIENTATION` (#265). Airframe
`ATTITUDE` (#30) lands in `Attitude.pitchDegrees()`, which nothing on the geo path reads, and there
is no fallback. `localize.py`'s guard `telemetry.camera_pitch_deg is not None` then fails and
rectification is skipped — while `cv.proto`'s own comment says *"Absent = nadir assumed"*, which
silently mis-models a 45° oblique camera as pointing straight down.

Proven, not inferred. With `ATTITUDE` only: `rectified: false` in **323/323** rows. After the sender
was switched to MAVLink 2 so #285 actually went out — the only change — `rectified: true` in
**41/41**, and mean inlier ratio rose from 0.0610 to 0.0833 (+36 %). The two symptoms are one bug:
the un-rectified oblique query being matched against nadir reference tiles is exactly the failure
rectification exists to prevent.

The kernel already solved this case and the geo path did not inherit it: `GeoProjection.aimFrom`
takes an explicit `fallbackDepressionDegrees` for "no usable gimbal pitch reading". `GeoFixCodec`
has no equivalent. A fixed-camera mount-pitch fallback is the fix; §1.3 and D6 freeze the #285/#265
sources without ever saying what a gimbal-less platform does.

**3 — `infra/sitl` produced no position estimate on this host** (§5.1). Whether this is an
ArduPilot/CPU-contention issue or an `infra/sitl` configuration one was not determined. It is
recorded because it blocked step 4 as written and forced the fallback, and because the SITL-backed
integration tests in `drone-link/mavlink` would presumably be affected by whatever it is.

**4 — the two booleans that decide `PROBABLE` vs `CONFIRMED` are neither persisted nor on the wire.**
`CONFIRMED` requires `evidence.sequenceConverged() && evidence.cellCalibrated()`, but
`track_corrections` stores only `sequence_spread_meters` and `sequence_updates`, and
`CorrectionResponse` carries neither flag. An operator — or the next engineer — looking at a run full
of `PROBABLE` rows cannot tell *which* condition withheld promotion without attaching a debugger.
Diagnosing §6.1 required reading the service source. Minor, but it is exactly the observability the
rest of this feature otherwise gets right (`refusal` is a model of how to do it).

### Not a defect, but a correction to the record

The eval regions under `cv/cv-service/spikes/geo/regions/` are frequently described as *committed*.
They are **gitignored** (`cv/cv-service/.gitignore`) and exist only on this machine's worktree.
Nothing about this demo depended on them — the region here was ingested from live Esri imagery
through the production path — but a clean clone will not find them.

A second, smaller one: `CV_GEO_DATA_DIR` defaults to `<cv-service>/geo`, and **that path is not
gitignored** — only the `spikes/` copy is. The first real region ingest therefore drops ~4 MB of
binary artefacts (`descriptors.npy`, `verify_tiles.npz`, 49 JPEG tiles) into the working tree as
untracked files, where they are easy to `git add -A` by accident. This demo deleted them on teardown.
One line in `cv/cv-service/.gitignore` fixes it; it was left alone because H7's scope forbids
touching anything but this document.

---

## 9. What is left after this

- **Both §8 defects 1 and 2 are H8's call**, not fixed here. Defect 2 in particular changes what the
  measured numbers in §9 of the plan mean for any fixed-camera platform.
- **The headline outcome remains unproven in the direction that matters.** Nothing wrong was
  published, which is the safety half. That a *correct* fix can be published on real footage was not
  shown, and on this clip cannot be — the plan already knew this (§0.1 scopes out the no-telemetry
  corner; §9.9 amendment 5 names the missing fixture). The open item is unchanged and now has a
  measured floor under it: **an operator-recorded flight with real logged telemetry.**
- `POSITION_DIVERGENCE` end-to-end, and the H6 map rendering of a corrected track, are both
  unexecuted paths (§6.1, §7).
- **Teardown was complete.** Stream stopped, demo asset deleted (`devicesDeleted: 2`), both SITL
  containers removed, vision-app and the demo cv-service killed, ports 8080 and 50052 released, and
  the generated `cv/cv-service/geo/` region data deleted. Re-running the ingest costs ~10 s. The
  pre-existing Postgres, mediamtx and the older cv-service on `:50051` were left exactly as found —
  none of them was started by this demo, and none was `down`ed by it.
