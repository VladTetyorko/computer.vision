# MOAT — what this platform is that no one else is

Status: **thinking document for review (2026-08-09)**. Companion to docs/FEATURE-MATRIX.md (which
ranks features by effort/value) — this one asks the prior question: *which of them are ours alone?*
Companion to docs/ANY-DRONE-PLAN.md (the adoption funnel).

## 0. The premise, accepted

"Computer vision and video streaming" is not a differentiator. Any DJI drone has both, in a
polished app, for less money than we will ever charge. Every capability that ships **inside a
vendor's own aircraft** is a commodity to us and always will be — we cannot win there and should
stop trying.

So the question is: what can this platform do that a vendor platform **structurally cannot**?

## 1. The four structural advantages (not features — properties)

A vendor's platform is bounded by four things it cannot change without ceasing to be itself:

1. **It only works with its own aircraft.** DJI's software serves DJI hardware. That is the business
   model, not an oversight.
2. **It is a vendor cloud.** Data leaves; the vendor is a dependency; offline/air-gapped is at best
   a degraded mode.
3. **Its CV is static and generic.** You get *their* classes, *their* model, frozen at firmware
   release. You cannot teach it your environment or your targets.
4. **It is single-sensor and single-operator-shaped.** One pilot, one aircraft, one video. Fixed
   cameras, ground observers, other people's drones — not its problem.

Every durable advantage we have is a direct inversion of one of those. That is the test I would
apply to any proposed feature: **which of the four does it exploit?** If none, it's table stakes at
best.

## 2. The pillars, ranked by how hard they are to copy

### Pillar 1 — Vision-derived geolocation (inverts #1, #2, #3)

**The strongest thing in this codebase, and the least replaceable.** Two halves, and the second is
underrated:

- **Where the drone is, from what it sees.** GPS-independent positioning (docs/VISUAL-GEO-PLAN.md,
  Waves 1–6 landed). In an environment with jamming or spoofing this is not a nice-to-have, it is
  the difference between a usable aircraft and a lost one. And because our server-side pipeline
  runs *alongside* GPS rather than instead of it, the same machinery is a **GPS-integrity monitor**:
  when vision and GPS disagree, we say so. Nobody in the consumer or prosumer market offers that;
  the systems that do are closed, defense-priced, and tied to their own airframe.
- **Where the *thing* is, from what the drone sees.** Point at an object in the video, it lands on
  a shared map with coordinates (tactical marks + `GeoProjection`, already shipped). For a large
  class of real users this is the *actual job* — not "watch a video", but "tell me where that is,
  precisely, and show everyone at once."

Why it's defensible: it needs the video, the telemetry, the reference imagery pipeline, the map,
and the CV service **in one system**. A vendor could build it; they'd be building our whole stack
to do it, for aircraft that already have working GPS. Note the honest caveat in VISUAL-GEO-PLAN
§12 — retrieval accuracy is not solved. That's an engineering gap in the most valuable asset we
have, which is exactly where effort belongs.

### Pillar 2 — CV that compounds, in the operator's hands (inverts #3)

Vendor CV is a photograph; ours is a **loop**: see a wrong or missed detection in live video →
correct it in one tap → it becomes labeled data → fine-tune → promote to live, on your own hardware
(docs/CV-TRAINING-PLAN.md, done; open-vocabulary YOLOE so day one isn't empty).

This is the **business** moat, not the technical one. A vendor's detector is the same on day 500 as
on day 1. Ours is measurably better at *your* environment, *your* targets, *your* lighting — and
that improvement lives in your data, on your box. Switching cost grows monthly, and it grows on
its own. Nothing else we build compounds like this.

The under-exploited consequence: **detection quality becomes a per-customer asset we can show**.
"Your model: 340 corrections, +18% recall on your classes since June" is a retention screen no
vendor can render.

### Pillar 3 — One verified picture from many heterogeneous sensors (inverts #1 and #4)

Not a drone app — a **command point**. Drones, RTSP cameras, ESP32-CAMs, USB/analog FPV, ground
observers, all into one map; layers with visibility grants ("область видимості"), affiliation,
verify-and-promote to the common picture, drawings, scoped live updates (docs/MAP-REWORK-PLAN.md,
done). Plus remote piloting over IP with a physical transmitter (docs/RC-CONTROL-PLAN.md) and
operator handoff as the natural extension.

The category error to avoid: we are not competing with QGroundControl (better single-vehicle GCS)
or with DJI Pilot (better single-aircraft app). We are the layer **above** them, where many people
and many sensors share one verified picture. DroneSense is the closest competitor and is closed,
US-shaped and expensive; FlightHub is DJI-only by construction.

### Pillar 4 — Any drone, no vendor lock, deployable offline (inverts #1 and #2)

docs/ANY-DRONE-PLAN.md. The person who already owns hardware — a DIY quad, an INAV wing, a
second-hand ArduPilot hexa — has **no** good platform, because every good platform belongs to a
manufacturer. Being firmware-agnostic and on-prem is not a feature list item; it is the reason such
a user can exist at all.

Offline deserves its own sentence: a field server with no internet, no vendor account, no telemetry
leaving the perimeter. For military, critical-infrastructure and SAR users in a contested region,
"the cloud" is a disqualifier and we are one of very few answers.

## 3. The cultural moat: honest instruments

This codebase refuses to fake a read. Absent data renders as unknown, and says why (poka-yoke rules
throughout FC-INTEGRATIONS, VISUAL-GEO's confidence display, the readiness report in ANY-DRONE).
A consumer vendor optimizes for a screen that always looks confident. For a referee, an
investigator, or someone deciding whether to fly, **an instrument that admits what it doesn't know
is the only trustworthy kind.** That is a positioning advantage a mass-market product structurally
cannot adopt, and it is already how we build. It should be said out loud in the product, not just
lived in the code.

## 4. The strategic link most easily missed

**Pillar 4 is not a peer of pillars 1–3. It is the funnel through which they are reached.**

Every hour a drone owner spends wondering why telemetry doesn't appear is a user who never sees
visual geolocation, never makes their first correction, never builds a common picture. The
readiness loop in ANY-DRONE-PLAN is therefore worth more than any single remaining Tier-2 feature
in FEATURE-MATRIX — not because diagnostics are exciting, but because they are the gate in front of
everything that is.

## 5. The one sentence

> **Not a drone app — a command point that turns any collection of heterogeneous cameras and DIY
> aircraft into one verified, self-improving, GPS-independent common picture, running on your own
> hardware.**

Each clause is load-bearing, and each maps to a pillar: *any / heterogeneous* (4), *verified …
common picture* (3), *self-improving* (2), *GPS-independent* (1), *your own hardware* (2 and 4).

## 6. What to stop considering (so the moat stays sharp)

- **Mission/waypoint planning.** 8/8 competitors have it, QGC does it better and free, it demands
  deep command-TX. Consumes cycles, defends nothing. (FEATURE-MATRIX already flags the caveat.)
- **Photogrammetry / orthomosaic.** A different persona and DroneDeploy's home turf.
- **Being a better ground control station.** The layer below us. Interoperate, don't replace.
- **Polished consumer flight UX.** Unwinnable against vendor-integrated hardware, and not what our
  users are choosing us for.

## 7. If I had to pick the next investment

1. **Close the ANY-DRONE loop (waves 1–2).** Cheap, zero-risk, and it is the gate in front of
   everything else.
2. **Retrieval accuracy in visual geolocation.** The most valuable asset we own is also the one
   with the honest open gap (VISUAL-GEO §12). Value per engineer-week is highest here.
3. **Make the compounding visible.** Surface the CV improvement loop as a per-customer result
   ("your model, measurably better since June"). The moat exists; it is currently invisible to
   the person it belongs to.
