# CV-PULL-SPIKE — M0 measurements for MEDIA-SOT-PLAN

**Status:** spike complete, 2026-08-12. Wave M0 of
[MEDIA-SOT-PLAN.md](../plans/done/MEDIA-SOT-PLAN.md) §8. Gates M3/M5/M6. No product code
changed; every number below comes from `cv-service/spikes/pull/` run against a throwaway
mediamtx container, this laptop, and the GB4005 box. Numbers not measured this session are
marked **not measured** rather than estimated — CV-RATE-BUDGET.md §5's standing lesson.

## 1. Setup (reproducible)

**mediamtx.** A second, throwaway `bluenviron/mediamtx:1.19.3` container (`mtx-spike`), not
the product's `docker-compose.yml` service — that container already holds host port 8554.
Host ports used instead: `18554` (RTSP), `9997` (Control API).

```
docker run -d --rm --name mtx-spike \
  -v cv-service/spikes/pull/results/mediamtx-spike.yml:/mediamtx.yml:ro \
  -p 18554:8554 -p 9997:9997 -p 8000-8001:8000-8001/udp \
  bluenviron/mediamtx:1.19.3
```

`mediamtx-spike.yml` is the image's own default config with two additive overrides (`api:
yes`, a widened `authInternalUsers` entry) — see §4's contract correction for why the second
one was necessary. Everything else, including the `paths.all_others` catch-all that lets an
arbitrary path name be published without pre-registration, is the shipped default.

**Synthetic source.** `cv-service/spikes/pull/push_sources.sh` publishes two **moving**
`testsrc` patterns (not a still image, so decode/inference cost isn't artificially cheap) via
`ffmpeg -re`, H.264, baseline profile, ~4 Mbps, 2 s GOP (`g=60` @ 30 fps) — one 1280×720, one
1920×1080 — into `rtsp://localhost:18554/push720` and `.../push1080`.

**Inference.** `yolo26n.pt` (the repo's `DEFAULT_MODEL`) via `cv_service.inference.detector.
YoloDetector` — the same class the product uses, not a reimplementation. Decode and inference
costs are measured as two separate timers around two separate calls, never blended into one
number.

**Hardware.**

| Box | CPU | Cores | Notes |
|---|---|---|---|
| laptop | AMD Ryzen 5 5600U | 12 (6c/12t) | runs mediamtx-spike + both ffmpeg publishers + the puller under test |
| GB4005 | Intel Celeron J4005 @ 2.0 GHz | 2 | remote, `vlad@192.168.0.106`, no CUDA, reached over LAN, existing venv reused read-only |

**Scripts** (`cv-service/spikes/pull/`): `push_sources.sh` (publish), `sampler.py`
(`DeadlineSampler` + `LatestOnlyMailbox` port), `decode_bench.py` (decode + inference cost),
`rate_achieved_bench.py` / `rate_stall_bench.py` (rate discipline + D8), `clock_drift_bench.py`
(§6), `clock_offset_probe.py` (app↔worker offset). Raw JSON output for every run is under
`results/`; the full mediamtx `curl` transcript is `results/mediamtx_api_transcript.txt`. The
`opencv`/`ffmpeg` backends run from `cv-service/.venv` (already has `cv2` + `ultralytics`); the
PyAV backend runs from an isolated, disposable, NOT-checked-in venv (`python3.12 -m venv
spikes/pull/.venv-pyav && spikes/pull/.venv-pyav/bin/pip install av psutil numpy`) with no
`ultralytics` installed, so it is decode-only by construction, not by discipline.

---

## 2. Decode + inference cost

Three decoder candidates, per D7: `cv2.VideoCapture(url, cv2.CAP_FFMPEG)` (expected default,
zero new dependency), an ffmpeg subprocess piping raw `bgr24` frames, and PyAV. 120 frames per
run (90 for 1080p PyAV) after a 10–15 frame warmup discard, on the laptop pulling from the
local mediamtx-spike container.

### Decode cost (ms), laptop

| backend | resolution | p50 | p95 | max | process RSS max | CPU% avg (12-core box) |
|---|---|---|---|---|---|---|
| **opencv** (`CAP_FFMPEG`) | 720p | **2.34** | 4.77 | 7.36 | 394 MB | 756% |
| **opencv** (`CAP_FFMPEG`) | 1080p | **4.14** | 9.94 | 13.37 | 452 MB | 649% |
| ffmpeg subprocess | 720p | 5.91 | 10.90 | 12.58 | 352 MB | 650% |
| ffmpeg subprocess | 1080p | 11.00 | 14.10 | 24.78 | 371 MB | 635% |
| PyAV | 720p | 33.01 | 34.55 | 75.89 | 74 MB | 19% |
| PyAV | 1080p | 32.79 | 35.08 | 37.61 | 88 MB | 34% |

RSS is dominated by the inference stack (torch + ultralytics), which is why PyAV's decode-only
process sits an order of magnitude lower — that column is not a fair backend comparison, it's
evidence the RSS budget is inference, not decode, whichever decoder wins.

**PyAV's number needed a second look before trusting it.** The naive per-frame time (`next()`
on the decode generator) was ~31 ms; explicitly setting `codec_context.thread_type = "AUTO"`
made no measurable difference (33.4 ms). That rules out the well-known "PyAV defaults to
single-threaded decode" footgun — the real cause is almost certainly that this spike's encode
(baseline profile, 1 slice/frame, no B-frames, real-time single-frame delivery) gives FFmpeg's
frame- and slice-level parallelism nothing to parallelize over, for *any* consumer of the same
libavcodec decoder. `opencv`'s own backend is also FFmpeg-based, so the 2.3 ms vs 33 ms gap is
not "OpenCV decodes faster than libavcodec" — it is most likely OpenCV's `CAP_FFMPEG` reader
pipelining decode ahead of the blocking `read()` call in a way this spike's PyAV loop (a plain
`container.decode()` generator, the documented high-level API) does not. This was not tracked
down further within M0's scope; it is reported as measured, with the caveat that a
production PyAV implementation using lower-level demux/decode calls might close some of this
gap. **1280×720/1920×1080 CBR single-slice baseline H.264 is also not what a real IP camera
necessarily sends** — H1 camera behaviour should be re-checked once one is on hand (TWO-TARGETS
plan), the number here is for this synthetic source only.

### Inference cost (ms), `yolo26n.pt`, separate from decode

| box | resolution | p50 | p95 | max |
|---|---|---|---|---|
| laptop (Ryzen 5 5600U) | 720p | 36.0 | 55.2 | 138 |
| laptop (Ryzen 5 5600U) | 1080p | 57.5 | 163.1 | 437 |
| GB4005 (Celeron J4005) | 720p | 197.0 | 200.0 | 227 |
| GB4005 (Celeron J4005) | 1080p | 199.0 | 201.1 | 232 |

GB4005 figures are PyTorch CPU (same `YoloDetector` path as the laptop, not the OpenVINO IR
export) — comparable methodology to the laptop column. `cv-service/DEPLOY-GPU.md` separately
documents this same box at ~230 ms/frame PyTorch CPU and ~135–150 ms/frame after exporting to
OpenVINO IR; this spike's 197–199 ms sits in the same neighbourhood as that PyTorch figure
(not re-measured here, cited for context only — OpenVINO was not re-tested in this spike). At
~200 ms/frame the GB4005 box tops out around **5 fps**, matching that doc's existing guidance to
keep `inferenceFps ≤ 5` against this box.

### Decode cost, GB4005 (pulling over LAN from the laptop's mediamtx-spike, `opencv` backend only)

| resolution | p50 | p95 | max | RSS max | CPU% avg (2-core box) |
|---|---|---|---|---|---|
| 720p | 1.94 | 5.05 | 5.15 | 360 MB | 101% |
| 1080p | 4.34 | 7.26 | 7.44 | 380 MB | 100% |

Decode itself is cheap even on the 2-core box (~2–4 ms, in the same range as the laptop) — the
GB4005 ceiling is entirely inference, not decode. GB4005 was reached over the LAN (laptop
`192.168.0.104` → GB4005 `192.168.0.106`, both directions reachable, ping ≈ 70 ms first-hop but
a warm TCP connection reads in single-digit ms — the decode numbers above include that network
read time, which is realistic for the pull architecture this plan targets).

---

## 3. Rate discipline (ported deadline sampler + D8 latest-wins)

`sampler.py` ports `StreamPipeline.sampleDue`/`armScheduleAt` (feat/cv-rate-control) to
Python, preserving the two properties call out in the task: **one clock read per frame**
(`sample_due(now_ns)` never reads the clock itself, the caller does, once) and the **`max(now,
…)` debt clamp** (a schedule more than one interval late restarts from `now + interval` instead
of accumulating catch-up debt, counting the skipped deadlines instead of firing them as a
burst).

### Achieved fps against a 10.0 target

30 fps synthetic source, 20 s run, no artificial detector delay:

| target fps | achieved fps | source fps measured | missed deadlines | sample interval p50 / min / max |
|---|---|---|---|---|
| 10.0 | **9.984** | 30.85 | 0 | 99.9 ms / 57.7 ms / 142.3 ms |

Matches the Java side's own measured 9.998/10 (CV-RATE-BUDGET.md) closely enough to call the
port behaviourally faithful.

### D8 proof: stalled detector, 720p, 25 s run, simulated 800 ms detector stall

`rate_stall_bench.py` runs a reader thread pulling frames as fast as they arrive into a
`LatestOnlyMailbox` (single slot, overwrite-and-count, never queues) while the main loop
consumes on the deadline schedule and then blocks for `--stall-ms` to simulate a slow
detector.

| metric | value |
|---|---|
| source frames seen | 786 |
| samples consumed | 31 (25 s / 0.8 s stall ≈ expected 31) |
| missed deadlines (100 ms interval, absorbed by the debt clamp during each 800 ms stall) | 217 |
| **dropped frames (final)** | **754** |
| served-frame age at consumption — p50 / max | **4.5 ms / 36.6 ms** |

The dropped-frame count climbs monotonically throughout the run (40 at 0.8 s → 730 at 24.8 s,
full timeline in `results/rate_stall_720p.json`), proving the reader never blocks waiting for
the stalled consumer. At the same time, every one of the 31 stalled "detections" received a
frame that was at most ~37 ms old — not a frame from several seconds back in a growing queue.
That combination — drops counted and climbing, served frames staying fresh — is exactly D8.

One simplification versus the Java pipeline, noted rather than hidden: this harness is a
single consumer thread checking `sample_due` in a poll loop, not the Java code's async
in-flight-bounded submission with separate `DROPPED_IN_FLIGHT`/`DROPPED_OUTAGE` counters. The
217 "missed deadlines" here are the debt-clamp's own count of deadlines that elapsed while the
thread was blocked in the simulated stall, which is a fair analogue but not a byte-identical
mechanism — wave M3 should decide deliberately whether the worker's real loop is single- or
multi-threaded, this spike does not answer that.

---

## 4. Capture clock (§6)

**Scope caveat, read before trusting these numbers.** The synthetic source runs on the *same
host* as mediamtx and (for the laptop leg) the puller — `ffmpeg -re`'s PTS timebase is itself
derived from this host's own clock. There is no independent camera oscillator in this rig, so
this spike cannot exercise genuine RTP/RTCP clock-domain drift between a physical camera and
the worker. What follows is the *software-observable* component (jitter-buffer/decode latency
stability, `cv2`'s PTS bookkeeping) — a measured floor on real-world drift, not a ceiling. A
real H1 camera is needed to close this gap (TWO-TARGETS plan).

10-minute run, 720p, sampled every 3 s (198 samples per candidate,
`results/clock_drift_720p_10min.json`):

| candidate | steady-state (median) | spread (stdev / min–max) | trend over 10 min | verdict against the 100 ms/10 min gate |
|---|---|---|---|---|
| **anchor** (`anchor_wallclock + (pts − anchor_pts)`, never re-anchored for the full run) | **568.9 ms** offset from `arrival` | stdev 15.2 ms; range 489.6–570.3 ms | **flat.** 19/198 samples dropped below 550 ms (intermittent decode-hiccup spikes, each ~20–80 ms below the steady band), scattered across the whole run (9 s, 163–293 s cluster, 435 s, 595 s) — not concentrated at the start or the end, i.e. no sign of a monotonically growing gap | **holds, with margin** — the entire observed spread (worst spike to steady state) is ~80 ms, under the 100 ms budget on its own, and the actual drift (first-minute vs last-minute steady value) is a few ms, not tens |
| **arrival** (baseline the others are compared against) | 0 ms by construction | n/a | n/a | not applicable — this candidate defines the reference |
| **ffmpeg subprocess, `-use_wallclock_as_timestamps 1`** (see contract correction below — actually measured as: subprocess raw-pipe decode, pipe-backlog vs. expected 30 fps cadence) | median 701.2 ms backlog | stdev 19.9 ms for the first ~590 s | **flat for ~590 s** (699–710 ms band), then **rises to 715 → 765 → 784 ms in the final ~10 s** | **inconclusive, not clean** — this uptick coincides with this laptop also running the concurrent GB4005 SSH/rsync measurements (§6) at that point in the timeline, a real confound this single run cannot rule out. Would need an isolated rerun (no concurrent load) to tell "the pipe is accumulating backlog" apart from "the host was briefly busier." Not blocking, since this is not the recommended candidate |

The anchor candidate's own worst-case single spike (489.6 ms, i.e. ~80 ms below the ~569 ms
steady band) is itself smaller than the plan's 100 ms/10 min gate, so **`anchor` passes the gate
this rig can measure**, and PyAV's fallback trigger (D7 §6: "if anchored drift exceeds 100 ms
over 10 minutes, PyAV is adopted") is not tripped.

**Contract correction: the `ffmpeg_wallclock` candidate does not do what §6 assumes.**
`-use_wallclock_as_timestamps 1` was A/B-tested directly against an RTSP source using
`ffmpeg … -vf showinfo`: `pts_time` was numerically indistinguishable with and without the
flag (both track the stream's own PTS, ~1.6–1.7 s into a fresh open, nowhere near an absolute
epoch time). This matches FFmpeg's own documented behaviour — the flag only takes effect for
demuxers that report `AVFMT_NOTIMESTAMPS`, which the RTSP demuxer does not; RTSP already
supplies real (RTP-derived) timestamps, so the flag is silently a no-op here. **What this
spike measured under that label is therefore not "wallclock-as-timestamps" at all** — it is an
ffmpeg-subprocess decode pipeline, receipt-timestamped in the parent process exactly like
`arrival`. Anyone implementing `CV_PULL_CLOCK_MODE=ffmpeg` against §6 as written would build
something that silently behaves like `arrival`, not like a third independent candidate. §6/§5.5
should either drop this candidate or redefine it explicitly as "arrival, via an ffmpeg
subprocess decoder" rather than implying it reaches real wallclock-anchored timestamps FFmpeg
does not actually provide for RTSP.

`cv2.VideoCapture` also confirmed, as §6 already anticipated, to expose **no** RTCP sender-report
mapping — `CAP_PROP_POS_MSEC` is stream-relative elapsed time from open, not an NTP-anchored
capture instant. The `anchor` candidate above is therefore the best `cv2`-only approximation of
what §6 describes, not the real thing; genuine RTCP-anchored capture time (if the 100 ms/10 min
gate is ever failed against a real camera) would need lower-level access neither `cv2` nor a
plain ffmpeg subprocess gives — PyAV's low-level API is the most plausible path there and was
not explored further in M0 (out of scope once the gate above was measured to hold).

---

## 5. mediamtx Control API (v3) vs §5.3

Full transcript: `cv-service/spikes/pull/results/mediamtx_api_transcript.txt`. Summary:

| Operation | §5.3 says | 1.19.3 measured |
|---|---|---|
| create path, already exists | 400 "already exists" | **confirmed**: `{"status":"error","error":"path already exists"}`, HTTP 400 |
| readiness get, missing path | (implied 404) | **confirmed**: `{"status":"error","error":"path not found"}`, HTTP 404 |
| delete, missing/already-deleted path | 404 → treat as success | **confirmed**: same 404 body, both for a just-deleted path and one that never existed |
| create path, success | `{"status":"ok"}` | **confirmed** |
| patch (the "already exists → fall through to patch" path) | implied, not spelled out | **confirmed reachable**: `POST` semantics via `PATCH /v3/config/paths/patch/{name}` returns `{"status":"ok"}` on an existing path |

**Contract correction: the Control API is IP-gated by mediamtx's own default config, and
§5.3 does not mention this.** The very first call against a freshly-started `MTX_API=yes`
container returned `401 {"status":"error","error":"authentication error"}` for *every* one of
the three operations. mediamtx's baked-in `authInternalUsers` default grants unauthenticated
`api`/`metrics`/`pprof` access **only when the caller's IP is `127.0.0.1`/`::1`** — publish/
read/playback (what viewers and the recorder use today) have no such restriction, which is why
this has never surfaced before. A docker-published host port does not preserve the container's
view of `127.0.0.1` (the container sees the docker bridge gateway IP, not loopback), so `curl
localhost:9997` from the host already 401s — and **`vision-app` calling mediamtx from a sibling
container on the compose network would 401 for the same reason**, since it is not the
mediamtx container's own loopback either. `MTX_API: "yes"` alone, which is all §2 and §5.3
mention, is not sufficient.

The fix this spike verified: mount a custom `mediamtx.yml` (env var override of the nested
`authInternalUsers` list, e.g. `MTX_AUTHINTERNALUSERS=<json>`, was tried first and had **no
effect** — the request still 401'd, so that shortcut does not work) that widens the `api`
user's `ips` to `[]`, or alternatively defines an explicit user/password for the `api` action.
Wave M7 (which owns `docker-compose.yml`) needs to add a config file or credentials for this,
not just the env var the plan currently lists — a real, blocking correction for that wave, not
a cosmetic one.

---

## 6. GB4005 leg

**Not blocked** — SSH key auth works (`vlad@192.168.0.106`), the box has docker, a `.venv`
with `opencv-python`, `torch` (CPU), `ultralytics`, and `openvino` already installed, and
`yolo26n.pt` already present at `~/vision/cv-service/`.

One real obstacle, worked around without touching the live deployment: `~/vision/cv-service`
on that box is a **live, `systemctl`-active** `cv-service.service` on an **older, pre-package-
split checkout** (flat `cv_service/inference.py`, no `cv_service/inference/detector` module) —
importing the current repo's `cv_service.inference.detector` against it fails. Rather than
rsync the current checkout over a running production service, a fully separate tree
(`~/vision-spike-m0/cv-service/`, current repo's `cv_service/` + this spike's scripts only) was
rsynced alongside it, reusing the existing venv's installed packages read-only via
`PYTHONPATH=~/vision-spike-m0/cv-service` (the venv's own editable install otherwise resolves
`cv_service` to the old live tree first). The live service was not restarted, stopped, or
otherwise touched; the temporary tree was removed after measurement.

Decode + inference cost: §2's tables above (GB4005 rows). **App↔worker clock offset** (§6),
measured NTP-style over an SSH round trip (multiplexed connection to remove per-call SSH
handshake overhead, which otherwise dominated the very first, discarded measurement at
600–1200 ms RTT):

| metric | value |
|---|---|
| best-probe round trip | 19.4 ms |
| **offset estimate** (GB4005 relative to laptop) | **−7.9 ms** (GB4005 slightly behind) |
| uncertainty (half round trip) | ±9.7 ms |

Small enough to treat both clocks as effectively synchronized (consistent with both boxes
running NTP) — `capture_skew_millis` in the real implementation is still the correct
instrument to keep watching this in production, this spike only confirms today's offset is
small, not that it always will be.

---

## 7. GO / NO-GO

**GO.** Nothing measured here falsifies the plan; two corrections are needed before M6/M7 code
against §5.3/§6 as currently written (both captured above, restated below for visibility).

| Decision | Value | Chosen by |
|---|---|---|
| `CV_PULL_DECODER` | **`opencv`** (`cv2.VideoCapture(url, cv2.CAP_FFMPEG)`) | §2 — fastest decode on both boxes (2.3–4.1 ms laptop, 1.9–4.3 ms GB4005), zero new dependency, honours invariant P1 trivially (already the pinned `cv` extra). PyAV remains the named fallback (D7) — its aarch64 wheel was verified by actual download (`av-18.0.0-cp311-abi3-manylinux_2_28_aarch64.whl`, plus a `manylinux2014`-tagged 14.2.0 for older glibc), so switching later stays P1-clean, but nothing in this run makes switching necessary |
| `CV_PULL_CLOCK_MODE` | **`anchor`** | §4 — the only 10-minute drift number this rig could actually produce; see the caveat that this spike cannot exercise real camera-oscillator drift. Revisit against a real H1 camera before shipping the 100 ms/10 min gate as a settled fact |

**Corrections for later waves:**

1. **M7 (docker-compose.yml):** `MTX_API: "yes"` alone will not make the Control API callable
   from `vision-app`'s container — a mounted config (or explicit API credentials) widening the
   default IP-gated `authInternalUsers` entry is also required. §5.3 as written under-specifies
   this.
2. **M6/§6:** the `ffmpeg_wallclock` clock candidate as named does not do what its name implies
   against an RTSP source (`-use_wallclock_as_timestamps` is a no-op there) — either drop it or
   rename/redefine it as "arrival via an ffmpeg-subprocess decoder."
3. **§5.3 PATCH path** (`POST /v3/config/paths/patch/{name}`, method `PATCH`) is confirmed to
   exist and to be the correct idempotent-start fallback — worth stating explicitly in the
   frozen contract rather than leaving it implied by "fall through to patch."

**Not measured / explicitly out of scope for M0:** 1080p PyAV thread-tuning beyond the one
`thread_type=AUTO` check; real-camera (non-synthetic, multi-slice) decode cost; OpenVINO IR
inference cost on GB4005 (only PyTorch CPU was re-measured this session; the OpenVINO figure
in `DEPLOY-GPU.md` is cited, not reproduced); anything on an actual airframe/companion computer.
