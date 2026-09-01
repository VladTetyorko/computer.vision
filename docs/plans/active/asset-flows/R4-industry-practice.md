# R4 — Industry practice: asset lifecycle & control (web research)

Research wave for ASSET-FLOWS (see `docs/plans/active/ASSET-FLOWS-CONTEXT.md`). Web-only
survey of mature GCS/fleet products, feature patterns extracted, fit rated against a
small self-hosted tactical platform (single operator today, crew specced, Postgres-only,
Asset/Device/AssetUsage model). No product code touched.

## §1 QGroundControl + Mission Planner

**Vehicle setup/summary.** QGC's Vehicle Configuration ("Setup View") is a fixed list of
pages (Airframe, Sensors, Radio, Flight Modes, Power, Motors/Actuators, Safety, Parameters)
each carrying a red/green sidebar icon — red means "still needs configuring, don't fly."
A **Summary** page aggregates every page's status into one overview so the pilot doesn't
have to open each page to know the vehicle is flight-ready.
[Vehicle Configuration | QGC Guide](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/setup_view/setup_view.html)

**Sensor calibration status.** Each sensor (compass, gyro, accelerometer) is a button
that is green (calibrated), red (must calibrate before flight), or unlit (optional,
default-valued). Calibration itself is a guided, visual, multi-orientation wizard run
once per vehicle and persisted to the autopilot, not the GCS.
[Sensor Setup (PX4) | QGC Guide](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/setup_view/sensors_px4.html)

**Pre-flight checklist.** Built-in toggle: "Use Preflight Checklist" shows a checklist in
the Fly toolbar; "Enforce Preflight Checklist" makes completion a *precondition for
arming* — the GCS actively blocks the pilot, not just displays a reminder. Community
design discussion (still open after years) pushes further: checklists should be
**vehicle-specific data** transmitted from the vehicle to whichever GCS connects (not a
GCS-local setting), mixing automated checks (motor spin test, weather/ADS-B web calls)
with manual tick-boxes, and should persist partial progress across app restarts so a
pilot can step away mid-inspection.
[Preflight checklist concept and ideas #6515](https://github.com/mavlink/qgroundcontrol/issues/6515),
[General Settings | QGC Guide](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/settings_view/general.html)

**In-flight battery indicator.** Fly-view toolbar shows a colored battery icon
(percent and/or voltage, configurable). Clicking it while in a warning/not-ready state
expands a dropdown listing the *specific reasons* plus, per reason, a "why" and a
possible fix; each line disappears independently as its cause clears. This is a general
QGC pattern (same widget family handles GPS, RC, and link health) — status is not a
single traffic light but a **decomposed list of live blocking reasons**.
[Fly View Toolbar | QGC Guide](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/fly_view/fly_view_toolbar.html)

**Mission Planner log download & analysis.** Dataflash logs live on the autopilot
(SD card / dataflash chip / MAVLink telemetry stream) and are pulled on demand via
"Download DataFlash Log Via MAVLink" into a per-vehicle-type folder. "Review a Log"
opens the saved log locally; MAVExplorer and web tools (Flight Review-equivalent) do
deeper analysis. The pattern: **logs are pulled post-flight from the vehicle, not
pushed live**, and review is a separate deliberate action from flying.
[Downloading and Analyzing Data Logs | Mission Planner docs](https://ardupilot.org/copter/docs/common-downloading-and-analyzing-data-logs-in-mission-planner.html)

## §2 Fleet platforms (Auterion Suite, DJI FlightHub 2, DroneLogbook/Aloft)

**Maintenance schedules driven by manufacturer data.** Auterion Suite auto-generates a
maintenance schedule per airframe the moment it's registered (manufacturer recommended
intervals), lets the fleet be **sorted by maintenance-due date** (turns maintenance into
a fleet-wide queue, not a per-vehicle fact you have to remember to check), and separately
tracks **unscheduled maintenance** — ad-hoc issues logged with description + photos.
Pilots report field issues from the flight app itself; maintainers see the same report in
the fleet console with notifications/reminders, and can assign tasks to specific people.
[Auterion Suite update: maintenance schedules](https://auterion.com/auterion-suite-introduces-updates-that-centralize-the-overview-of-crucial-data-for-efficient-fleet-management/)

**DJI FlightHub 2.** A live fleet map shows every drone's position, status, telemetry,
battery, and *assigned operator* in one view. Battery management tracks usage, cycle
count, and health, and fires notifications when a battery needs service. Positioned
explicitly for multi-role use ("pilots, command staff, field operators" sharing
situational awareness), plus dock-based scheduled automated missions with system health
monitoring for the dock hardware itself.
[DJI FlightHub 2](https://enterprise.dji.com/flighthub-2)

**DroneLogbook / Aloft (compliance-oriented logbook tier).** Tracks 25+ metrics
centered on **pilot currency** (certifications, recency, medical/training expiry) with
event notifications when currency is about to lapse — this is a *personnel* readiness
gate, distinct from vehicle readiness. Flight telemetry sync auto-derives airframe hours
and battery cycles from actual flights rather than manual entry. Incident reports,
equipment, battery, pilot-currency and flight records are exportable per-entity for
audits. Aloft folds in airspace authorization (LAANC) as part of the same pre-flight
workflow — showing that "fleet platform" and "airspace/compliance gate" tend to converge
into one pre-flight surface in this market.
[DroneLogbook features](https://www.dronelogbook.com/hp/1/features.html)

**Battery retirement thresholds are an industry norm, not a custom idea.** Commercial
operators retire batteries at a cycle ceiling (commonly 150–300 cycles) or a capacity
floor (~80% of rated), whichever comes first, plus hard stops for swelling/damage.
Fleet tools surface cycle count + capacity so this becomes a **computed retirement flag**,
not manual bookkeeping.
[Drone Fleet Maintenance playbook](https://dronebundle.com/blog/how-to-manage-drone-fleet-maintenance)

**DroneSense (public-safety DFR fleet, adjacent category).** Formal **RPIC → Remote
Operator handoff**: the RPIC keeps a direct controller connection and explicit authority
to reclaim control; handoff is a tracked, named event, not an implicit "whoever's
joystick is active." Missions aggregate flights from multiple pilots (including ones
outside the home org) into one operational record — i.e. the *usage record* is
multi-pilot-aware by design, not one-session-one-pilot.
[DroneSense Pilots](https://www.dronesense.com/pilots)

## §3 ArduPilot/PX4 ecosystem: battery health, calibration, log/replay tooling

**Battery telemetry model.** ArduPilot's battery monitor is designed around per-cell
voltage (up to 10 cells, matching the MAVLink spec) plus current, consumed mAh, and a
computed **state-of-health percentage**; internal resistance can be derived by comparing
measured voltage against calculated EMF, giving an early-warning signal distinct from
raw voltage sag.
[AP_BattMonitor.h](https://github.com/ArduPilot/ardupilot/blob/master/libraries/AP_BattMonitor/AP_BattMonitor.h)

**Flight Review (PX4) — what pilots actually open post-flight.** Not one giant log dump;
a curated set of plots aimed at specific failure classes: PID tracking (setpoint vs.
actual) for tuning quality, vibration (FFT + power spectral density) for mechanical
health, GPS uncertainty/noise/jamming for nav confidence, thrust-vs-magnetic-field
correlation for EMI on the compass, an **estimator watchdog** panel that flags sensor
fusion trouble directly, and a plain **logged-messages table** of errors/warnings — the
one section most pilots read first because it's already in plain language, not a graph
they have to interpret. The design intent stated in PX4's own docs: plots are
"self-explanatory" in principle but reading them well takes experience — which is why
the message table (interpreted for you) matters as a low-effort entry point.
[Flight Log Analysis | PX4 Guide](https://docs.px4.io/main/en/log/flight_log_analysis),
[Log Analysis using Flight Review | PX4 Guide](https://docs.px4.io/main/en/log/flight_review)

**UAVLogViewer / MAVExplorer** exist as the ArduPilot-side equivalents — same shape
(pull a binary log, run it through a web or desktop viewer, get graphs + derived
event markers), reinforcing that **post-flight review is a separate, standard tool**
across both major stacks, not a bolt-on.

## §4 Military/tactical GCS patterns (ATAK/TAK ecosystem, UAS crew)

**Observer Control mode (ATAK UAS Tool).** A named delegation: the Observer can command
gimbal moves and short waypoint/orbit actions *without* per-action approval from the
primary UAS Operator — a scoped grant of a control subset, not full handoff. This is a
concrete, implementable version of "authority ≠ visibility": a second role gets a
bounded slice of control authority while the operator keeps the rest.
[ATAK UAS TOOL Integration](https://www.unmannedsystemstechnology.com/feature/atak-uas-tool-integration-for-the-vision2-gcs/)

**TAK network telemetry sharing.** The UAS Tool broadcasts point position, sensor
point-of-interest, field-of-view/footprint, and telemetry/video to *every* ATAK/WinTAK
device on the network — i.e. asset state is a **shared network fact**, not something
locked to the controlling station's UI. Multiple viewers see the same live picture
regardless of who has stick control.

**Formal crew roles and handoff.** Standard crew: pilot/RPIC (engine-start to
engine-off responsibility, checklists, comms, emergency procedures), payload/sensor
operator (owns the sensor, interprets and reports findings independent of flight
control), plus standby RPICs who can accept full mission handover. A documented
**handoff ritual** exists: the incoming crew confirms current mission tasking and fuel
(≈ our "remaining flight time/battery") state before accepting the aircraft — handoff is
a checkpoint with an explicit confirmation step, not a silent transfer.
[Crew and Staffing Requirements of UAS](https://www.faa.gov/sites/faa.gov/files/2022-07/Annotated%20Bibliography%20(1997-2021)-%20Crew%20and%20Staffing%20Requirements%20of%20Unmanned%20Aircrafts%20Systems%20in%20Air%20Carrier%20Operations.pdf)

**Readiness rolls up the chain.** Unit-level readiness (aircraft + crew currency +
compliance) is maintained by a designated role and reported upward to a supervisor —
readiness is organizational, aggregated from per-asset and per-person state, not just
a per-flight go/no-go.

**Go/no-go risk scoring.** Pre-mission Flight Risk Assessment Tools (FRAT) turn several
weighted risk factors (environment, technical, human, operational) into a single
score/color band (green/amber/red) that gates the flight — a *computed* go/no-go, not a
checklist of unweighted booleans.
[UAS Operations Safety Risk Tools](https://jdasolutions.aero/blog/uas-operations-have-well-established-safety-risk-tools-2/)

**Crew Resource Management (CRM) handoff discipline.** RPIC retains final authority even
when delegating tasks; documented practice for mid-mission handoff (e.g. drone-as-first-
responder) has a *named* handoff manager and requires the receiving pilot to positively
confirm control before the transferring pilot lets go — never an assumed silent swap.
[RPAS Pilot-in-Command | SKYbrary](https://skybrary.aero/articles/rpas-pilot-command)

## §5 RC/rover and robot-fleet practice

**Hobbyist RC telemetry (racing).** Tools like RaceCapture and Open RC Spotter log
battery voltage, lap time, and position from a small onboard sensor set and push it to a
customizable live dashboard the driver builds themselves (choose which gauges matter).
Distinct pattern from drone GCS: **no built-in pre-drive checklist or maintenance
tracking at all** — this market has not solved lifecycle/maintenance, only live telemetry
+ lap analytics. Confirms that "asset lifecycle" thinking is a drone/fleet-specific
maturity, not something to borrow from RC racing.
[RaceCapture-Track](https://wiki.autosportlabs.com/RaceCapture-Track),
[Open RC Spotter](https://www.cnx-software.com/2026/07/27/open-rc-spotter-is-an-open-source-esp32-telemetry-and-data-logger-for-rc-cars-and-toys/)

**Robot fleet ops (Formant, Foxglove).** Converges on: a live per-robot health/battery
view, remote debugging without a truck roll, and a **replay-first incident workflow**
(slice the log around the failure, convert, replay in the same viewer used for live
ops — one tool for both). The most transferable idea found: **anomaly-to-ticket
bridging** — a robot error event auto-creates a work order in the maintenance queue
carrying asset id, error code, and location, closing the loop between "something broke"
and "someone actioned it" without a human re-typing the report.
[Remote Robot Fleet Management guide](https://www.roboticscenter.ai/learn/remote-robot-fleet-management-guide),
[Foxglove × Scout AI](https://foxglove.dev/customers/scout-ai)

## §6 Pattern catalog

| # | Pattern | Seen in | Data needed | Pilot value | Fit |
|---|---|---|---|---|---|
| 1 | Setup summary page: aggregate red/green status per config area, one glance = ready or not | QGC Vehicle Config Summary | per-device config completeness flags | skip hunting through N tabs to know if a vehicle is flight-ready | **fits** — Asset already has a config/attributes surface; needs a completeness rollup |
| 2 | Sensor-calibration traffic light per required check, unlit = optional | QGC Sensor Setup | last-calibration timestamp/result per sensor | pilot knows *what specifically* is uncalibrated, not just "not ready" | **partial** — we don't own calibration (firmware does), but can surface MAVLink calibration state the same way |
| 3 | Enforced pre-flight checklist blocking arm/launch, decomposed reasons in a dropdown | QGC checklist + Fly toolbar battery/status widget | checklist item set + live blocking-reason list | can't forget a step; sees *why* blocked, not just *that* blocked | **fits** — matches OPS-UX's existing "readiness honesty" direction; we already have a not-ready reason model per operator-ux-4/5/7 |
| 4 | Checklist lives on/with the asset, not the GCS session; persists partial progress | QGC issue #6515 | per-asset checklist template + per-run progress state | resume inspection after interruption; consistent checklist regardless of who's flying | **fits** — Asset is already the long-lived entity; this is a natural attribute set |
| 5 | Maintenance schedule auto-generated from usage (hours/cycles), fleet sortable by due-date | Auterion Suite | per-asset flight-hours/cycles + interval rule | maintenance becomes a queue to clear, not a fact to remember | **fits** — AssetUsage already accumulates session data; needs an interval-rule + due-date rollup, no new telemetry |
| 6 | Unscheduled maintenance + field issue report from the flight UI, visible to whoever maintains | Auterion Suite, DJI FlightHub 2 | free-text+photo report tied to asset id, timestamp, reporter | pilot reports a problem in 10 seconds without leaving the flight app | **fits** — maintenance/crew page already exists (Warehouse UX W-series); this is a report intake feed into it |
| 7 | Battery health as computed retirement flag (cycle ceiling / capacity floor), not raw voltage only | DJI FlightHub 2, industry cycle-count norms | per-battery cycle count + rated capacity + threshold config | pilot/maintainer sees "retire this battery" instead of inferring it from a voltage number | **fits** — needs a battery/consumable sub-entity under Asset/Device plus a configured (not hardcoded) threshold |
| 8 | Post-flight review: curated plots per failure class + plain-language message/event table as the entry point | PX4 Flight Review, ArduPilot logs | vibration/GPS/estimator/battery time series + decoded warning/error events | pilot gets an interpreted "what went wrong" list before ever opening a graph | **partial** — vision-events already captures replay/timeline; needs a "flagged anomalies" summary layer, not raw plots (no FFT tooling in scope) |
| 9 | Pilot currency / personnel readiness tracked separately from vehicle readiness, with expiry notification | DroneLogbook/Aloft | per-operator cert/training/recency dates | org sees who's current to fly, not just what's airworthy | **poor-fit-because** — single-operator today, crew is specced-not-built; premature until multi-operator roles exist |
| 10 | Fleet dashboard: live map with every asset's position/status/battery/**assigned operator** in one view | DJI FlightHub 2 | live per-asset state + current assignee | command/dispatch sees the whole fleet at a glance | **partial** — Manage/Operate hub already aggregates assets; "assigned operator" column is new once crew exists, single-op version is just current state per asset (cheap now) |
| 11 | Scoped control delegation (Observer Control): second role gets a bounded action subset without full handoff | ATAK UAS Tool | per-role permitted-action set on a live session | crew member can help (e.g. gimbal/payload) without taking full control risk | **poor-fit-because** — no crew roles shipped yet; matches OPS-UX's authority≠visibility direction for *later*, not now |
| 12 | Explicit handoff ritual: incoming operator confirms mission state + remaining resource before accepting control | Military UAS crew, DroneSense RPIC→RO | current mission/task + battery/fuel state snapshot | prevents "I didn't know what state it was in" after a silent takeover | **poor-fit-because** — needs >1 concurrent controller to matter; single-operator platform has nobody to hand off to yet |
| 13 | Shared live telemetry to every viewer on the network regardless of who controls | TAK network model | live state broadcast, not gated to controller's session | crew/observers stay situationally aware without needing control | **fits** — matches existing map/SSE scoped-visibility architecture (MAP-REWORK); this is "visibility" half of authority≠visibility, already partly built |
| 14 | Computed go/no-go risk score from weighted factors (env/technical/human/operational) | Military FRAT | per-flight context inputs (weather, airspace, fatigue, etc.) | one number/color instead of mentally weighing many unknowns | **poor-fit-because** — needs external inputs (weather/airspace feeds) we don't ingest; also risks false precision for a single small team |
| 15 | Anomaly-to-maintenance-ticket bridging: an error event auto-opens a queued work item with asset id/code/location | Foxglove/robot fleet ops | error/event classification + maintenance queue | closes the loop from "detected" to "actioned" without manual re-entry | **fits** — natural extension of pattern 6 once maintenance page exists; ties vision-events anomalies to warehouse maintenance records |
| 16 | Multi-pilot usage record: one mission/session aggregates flights from several pilots/orgs | DroneSense Missions | usage record with multiple contributing operators | accurate history when more than one person touches an asset in one operation | **poor-fit-because** — AssetUsage is currently single-session/single-operator shaped; real value only once crew exists |

## §7 Top 8 recommended patterns for this platform

Ranked by pilot value achievable **now**, given single-operator-today + crew-specced-later
and the existing Asset/Device/AssetUsage/warehouse/maintenance foundation already merged:

1. **Setup/readiness summary rollup per asset** (#1) — one glance replaces hunting
   through config tabs; slots directly onto the existing Asset detail view.
2. **Decomposed live blocking-reasons on the not-ready indicator** (#3) — extends the
   readiness-honesty work already shipped in Operator UX 4/5/7 instead of introducing a
   new concept.
3. **Checklist as an asset-owned, persistent artifact** (#4) — fits the "Asset is the
   long-lived entity" model better than a session-scoped checklist would.
4. **Maintenance schedule computed from AssetUsage hours/cycles, fleet-sortable by
   due-date** (#5) — reuses telemetry already captured; turns the existing
   maintenance/crew page from a log into a queue.
5. **In-app field issue report tied to an asset, feeding the maintenance queue** (#6) —
   cheap to add, highest pilot-convenience-per-effort ratio, no new telemetry needed.
6. **Battery/consumable health as a computed retirement flag with a configurable
   threshold** (#7) — must be config-driven per CLAUDE.md rule 1, not hardcoded cycle
   counts; needs a battery sub-entity but no new ingest path.
7. **Anomaly-to-maintenance-ticket bridging** (#15) — connects vision-events' existing
   anomaly/replay capture to the maintenance queue from #5, closing detect→act.
8. **Post-flight "what went wrong" summary layer over existing replay/timeline data**
   (#8) — a plain-language flagged-events list ahead of any deep plot tooling; scoped
   down from full Flight-Review-style plotting, which is out of reach without new
   instrumentation.

Deliberately **excluded from the top 8**: pilot-currency tracking (#9), scoped control
delegation (#11), handoff rituals (#12), and multi-pilot usage records (#16) — all four
are genuinely good patterns but every one is gated on crew/multi-operator existing as a
real feature, which it does not yet (crew is "specced-only" per OPS-UX/platform audit).
They belong in a follow-up wave once/if crew ships, not in this one.
