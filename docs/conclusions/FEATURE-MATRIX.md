# FEATURE-MATRIX — effort / value / scalability by user category

Status: decision document (2026-07-25). Inputs: internal backlog (UX-REWORK, REALTIME, CV-MODELS,
MVP4 candidates) + an 8-product capability survey (QGC/Mission Planner, DJI FlightHub 2/Pilot 2,
Auterion, FlytBase, DroneDeploy, Skydio Cloud, DroneSense, Esri Flight). "Table stakes" = 5+/8
products have it. Personas: **P** pilot · **M** manager · **MoM** manager-of-managers · **C** crew/referee.

Effort: S ≤2 days · M ≤2 weeks · L ≤6 weeks · XL multi-cycle.
Scalability: how the feature's value grows with fleet/org size (↑ = grows, → = flat per-user).

## Tier 1 — quick wins on existing plumbing (do now, any order)

| Feature | Affected | Effort | Value | Scale | Need (new) | Rework | Future |
|---|---|---|---|---|---|---|---|
| **RTH/failsafe banners** in Fly (battery low, GPS lost, link degraded, RTH active) — table stakes 5/8 | P | S | High — safety-critical awareness we already have the data for | → | Threshold config; banner component | Fly OSD reads existing telemetry signals | Auto-actions (pause detection alerts during RTH) |
| **Telemetry-verified pre-flight checklist** strip (feed OK, telemetry OK, GPS fix, battery %, storage) — 5/8 | P | S | High — go/no-go in one glance | → | Checklist logic per capability set | Fly start-flow gains a gate state | Custom checklist items per category (U-d attributes) |
| **Weather go/no-go chip** (wind aloft, gusts vs airframe limit) — built-in in ~1/8: cheap differentiator | P, M | S | Med-high | → | External weather API client + config | Fly header + Command panel chip | Forecast timeline; auto no-go policy per group (post U-e) |
| **Event → replay deep link** (click event, land scrubbed to that moment) | M, C | S | Med — closes the evidence loop | ↑ | Timestamp→usage resolution | Notification bell + events list links | After-action review packet (below) |
| **Small-items batch** (SSE hasImage, suggestedCategory, downscale check, 10s undo toast, replay strip cap, composite box hues) | all | S | Hygiene | → | — | Listed sites | — |

## Tier 2 — the operational core (next; each independently shippable)

| Feature | Affected | Effort | Value | Scale | Need (new) | Rework | Future |
|---|---|---|---|---|---|---|---|
| **Geofencing** (keep-in/keep-out, altitude ceiling; draw on map) — table stakes 6/8 | P, M | M | Very high — the #1 missing safety feature | ↑ | Zone domain model + breach detection (pure telemetry math); events on breach | Map draw tooling exists (flight-plan dialog); events/notifications carry breaches | MAVLink fence upload for autopilot-side enforcement (adapter-mavlink TX); per-group zone policies (U-e) |
| **Recording + clip export** (MVP4 candidate) | M, C | M | Very high — evidence is the product for C | ↑ | Retention policy config; clip-export endpoint; storage accounting | mediamtx native recording does the heavy lifting — enable + index segments; replay page gains video alongside telemetry | Bookmark-while-live (whitespace: ~0/8 products have it — cheap differentiator once recording exists) |
| **Telestration** — command draws on map/video, pilots see it live — 1/8: genuine industry gap | MoM, C → P | M | High — real-time coordination; big demo value | ↑ | Annotation model + TTL | Rides the existing SSE live channel + map/player overlay layers | Freehand on video frames; annotation history in after-action |
| **ADS-B / air-traffic overlay** — 3/8, fast-growing | P, M, C | S-M | Med-high (region-dependent) | ↑ | Feed client (ADS-B Exchange or similar), map layer | Fleet-map layer system takes a traffic layer | Onboard receiver ingest as a device capability |
| **Battery health per airframe** (cycles, health trend, swap alerts) — ~5/8 | P, M | M | High for real fleets | ↑ | Battery identity in domain (attributes or entity); cycle counting from usages | AssetUsage already tracks sessions; asset-detail gains a battery panel | Predictive replacement; per-battery telemetry (MAVLink BATTERY_STATUS serials) |
| **Maintenance log** (flight hours per component, scheduled alerts) — fleet-tool table stakes | M | M | High for real fleets | ↑ | Maintenance schedule model + alerts via notifications | Flight hours derivable from AssetUsage today | Work-order integrations |

## Tier 3 — needs the U-e foundation (users/roles/groups first)

| Feature | Affected | Effort | Value | Scale | Need (new) | Rework | Future |
|---|---|---|---|---|---|---|---|
| **U-e itself**: users, roles, group tree, scoped visibility, invite ≤ own scope | all | XL | Foundation — everything below + per-level warehouse | ↑↑ | User/Role/Group domain, auth, org screens | CurrentUser replaces DevPrincipal (designed for it); role-based landing | SSO/OIDC; API tokens per user |
| **Pilot currency & flight-hours dashboard** — fleet-tool table stakes | M, MoM | M (post U-e) | High for org accountability | ↑↑ | Certification records; currency rules | Usages already carry per-flight data; group scoping from U-e | Regulator-format logbook export (GUTMA) |
| **Control/watch handoff** between operators — 4/8 | P, MoM | L (post U-e) | High for 24/7 ops | ↑↑ | Session ownership + transfer protocol | Fly watch-mode + stream sessions exist; needs ownership semantics | True C2 transfer for MAVLink-commanded airframes |
| **Audit trail** (who watched/commanded/changed what) | MoM | M (post U-e) | Compliance | ↑↑ | Audit log store + viewer | AuditTrailPort seam already decorates services | Retention/export policies |
| **Equipment checkout/assignment** (pilot ↔ airframe) | M | S-M (post U-e) | Med | ↑ | Assignment records | Is U-e's pilot→asset assignment surfaced in Warehouse | Shift scheduling |

## Tier 4 — bigger product bets (choose deliberately)

| Feature | Affected | Effort | Value | Scale | Need (new) | Rework | Future |
|---|---|---|---|---|---|---|---|
| **Rules & alert-noise editor** (test-before-arm "would have fired 47×", one-tap corrections) | M, C | L | Very high long-term trust; feeds labeling | ↑ | Rules engine + replay-against-history | Detection events exist; notifications carry alerts | The correction stream becomes the Studio's label queue |
| **Click-to-follow tracking** (click a detection → camera/crop follows) | P, C | L | High wow; DJI/Skydio table stakes | → | Track-association over detections | CV pipeline emits per-frame detections already | Gimbal/PTZ steering when hardware supports it (adapter-onvif) |
| **Training studio** (datasets, fine-tune, promote/rollback) | ML-tinkerer, M | XL | The differentiator claim of UX-DESIGN | ↑ | Training pipeline in cv-service; dataset mgmt | Model registry (built) is the foundation; proto has TrainingProgress | orion-style specialist models trained in-product |
| **Integrations** (MQTT / Home Assistant / Telegram / webhook) | M | S-M each | Med-high adoption lever | ↑ | Publisher adapters per channel | EventPublisherPort exists — presentation work | Connector marketplace page (UX-DESIGN §5.5) |
| **Mission/waypoint execution** — 8/8 table stakes in GCS products | P | XL | High, but overlaps QGC; needs C2 | ↑ | MAVLink command TX (we only ingest today), mission model | Flight-plan editor exists for sim; would become real mission planning | Terrain-follow, corridor scans |

## Deliberately not pursuing (for the record)

- **Photogrammetry/orthomosaic** — serves a surveyor persona we don't have (DroneDeploy/Esri territory).
- **LAANC/Remote-ID compliance** — US-regulatory specific; low relevance to current deployment region; revisit if that changes.
- **Hardware comms (PTT radios)** — DroneSense-style hardware play; out of scope. Command-side chat could ride the SSE channel someday.

## Recommended sequence

1. **Tier 1 now** (one mixed batch, ~3–4 days total) — pilot-facing safety + hygiene.
2. **Geofencing + Recording** next (Tier 2's two "very high" rows) — the strongest value/effort in the whole matrix, both mostly on existing plumbing.
3. **U-e** as the following cycle (unlocks Tier 3), with **telestration** as the parallel frontend-flavored work.
4. Then choose the Tier-4 bet: **rules editor** (trust/noise — compounding value) is my recommendation over missions (crowded space, C2-heavy).
