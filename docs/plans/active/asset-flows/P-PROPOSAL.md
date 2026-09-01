# P — ASSET-FLOWS ranked proposal (Fable, 2026-09-01)

Ranking of `O1-SYNTHESIS.md`'s catalog by **pilot impact ÷ cost**, honoring every ordering
constraint in O1 §5. Nothing here is scheduled until the user picks; sizes are O1's.

## Tier 1 — safety fixes, buildable now (recommend: all four, one wave-set)

The system currently lets a pilot do things it already knows are wrong. All four have no
upstream dependency (S3 needs only the D6 micro-decision, recommendation below).

| id | What ships | Size |
|---|---|---|
| **S1** | Grounding/flight-blocking maintenance gates `arm/disarm/mode/RTH` + session-open; cockpit and readiness pages *say* the vehicle is grounded and why | S–M |
| **S4** | Link-lost notification (consume the already-merged FLEET-RADIO typed link-failure event) + battery-low system event — both enter the bell taxonomy | S |
| **S3** | One severity source for battery/attention: cockpit consumes the fleet attention verdict; thresholds move out of TS into configuration (see D6) | S |
| **S6** | mediamtx ingest+playback auth — flagged in four docs over three weeks, never scheduled | S–M |

## Tier 2 — quick wins, zero dependencies (recommend: same cycle, fills agent idle lanes)

| id | What ships | Size |
|---|---|---|
| **B4** | Drone picker groups by `GET /api/me/assignments` (endpoint exists, zero callers) — cheapest real win in the survey | XS |
| **B2** | Readiness pages ↔ cockpit two-way navigation (today: dead ends) | XS |
| **D1** | `pilot` on `AssetUsage` — flight records stop being anonymous; CREW-CONTROL later extends it, doesn't block it | S |
| **D3** | "Replay last flight" reaches any recent usage, not only the latest | XS |
| **A3** | Discovery inbox distinguishes "mediamtx down" from "nothing found" | XS |
| **C4** | Verify + merge `feat/controller-setup-c15` (built, green, unmerged) — unblocks two FLEET-RADIO web halves | S |
| **C5** | `supports()` stops claiming verbs the firmware can't do | S |

## Tier 3 — the pilot-value features (next cycle, order matters)

1. **S5 → B3**: live-telemetry readiness (GO means GO) first, then the asset-owned checklist +
   per-kind readiness rollup on top of it. Sequenced after S1 (same cockpit/readiness surface).
2. **C1**: sent-vs-acked command monitor — the 2026-08-26 audit's "biggest missing capability";
   pairs naturally with the retry/ack work just merged in MAVLINK-COMMANDS.
3. **E2 → E1 → E4**: field issue report (the `AssetNote` port is already built, dead), then the
   usage-driven maintenance queue, then anomaly→ticket bridging.
4. **A2**: simulate-flow unification onto `DeviceOrigin` (spec-ready, blocker already shipped).
5. **D2 (post-flight)**: plain-language "what went wrong" table over existing replay data.

## Tier 4 — gated on user decisions (O1 §3, recommendations attached)

| # | Decision | Fable recommendation |
|---|---|---|
| D1 | Missions 4-way contradiction | **Tasking-only** (MASTER-MATRIX M1): `goto` + small verb set rides the just-merged command layer; full M1–M8 stays retired until a real mission need appears. Reconcile the four docs in writing either way |
| D2 | Control-authority model | **Ship CREW-CONTROL (S2) as its own next plan** — it is the only real fix for dual-command, and F9 hard-blocks C2 behind it. The advisory "someone else is commanding" banner is worth adding inside S2's first wave, not instead of it |
| D3 | `probe.enabled` default | **Flip on** behind a verification wave — passport/config-drift is pure pilot value; O9/O10 + RC Phase 2 stay operator-gated |
| D4 | Pilot self-onboard | **Yes, scoped**: pilot may onboard into their own visibility scope; manage stays manager-only |
| D5 | Geofence AMSL/AGL | Per-vehicle-kind: AGL for rovers/copters, AMSL selectable for fixed-wing — needs your confirmation |
| D6 | Battery thresholds placement | **Configuration file now** (application.yaml, per-category override), DB+cache only if you actually want runtime editing |
| D7 | Retention policy | Needs your numbers; propose 30d telemetry / 90d detections / 1y audit as a starting point |

## Not proposed

Camera-first rework (declined — only A3/S6 salvage), asset-unit leases (design-ahead, W3),
a crew `Role` constant (declined in OPS-UX), NATS W2/W4/W5 (separate migration track),
missions full-XL (pending D1).

## Suggested cycle cut

**ASSET-FLOWS cycle 1 = Tier 1 + Tier 2** (~9 items, mostly XS/S, disjoint scopes, one branch
`feat/asset-flows-1` with sub-waves). Tier 3 becomes cycle 2 after the D-decisions land.
