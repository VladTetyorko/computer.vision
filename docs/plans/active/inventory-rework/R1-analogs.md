# Fleet / Device Inventory UX Research — Analog Survey

Research for redesigning the Inventory page of "vision" (fleet manager / pilot / org admin personas; Asset with Identity, Custody, InventoryState, MaintenanceRecord, flight hours, firmware, readiness GO/NO-GO, photo, notes; Devices hang off an Asset).

Every claim below cites a URL. Anything I could not confirm from a primary or credible secondary source is explicitly marked **UNVERIFIED**.

---

## 1. DJI FlightHub 2 (enterprise drone/dock fleet management)

**List/grid structure.** Administrators manage bound aircraft on a dedicated **Devices** page inside an organization; devices must first be bound via DJI Pilot 2 ("Device Binding"), after which "the administrators can manage the aircraft on the Devices page after the aircraft is bound to the organization" ([DJI FlightHub 2 User Guide PDF](https://dl.djicdn.com/downloads/DJI_FlightHub_2/20220825/DJI_FlightHub_2_User_Guide.pdf), p.9-10). On the live operations ("Team") page, devices appear in a **left panel** list rather than a table, alongside the map (same PDF, p.10).

**Status encoding.** Three-state liveness, not a status label field: device online → shown normally; offline **< 5 minutes** → row rendered in **gray**; offline **> 5 minutes** → device **disappears from the list entirely** ("its information will not be displayed") (PDF p.10; corroborated by [Device Management manual page](https://fh.dji.com/user-manual/en/organization-management/device-management.html) via search synthesis, and [Livestream Management page](https://fh.dji.com/user-manual/en/real-time-project-information/multi-stream.html) which further splits the live device list into **Docks** vs **Online Devices**).

**Verbs / where they live.** Clicking a device's map marker opens a floating window with call sign + altitude; clicking that window opens a **Device Details and Livestream panel** (transmission signal strength, satellite connection status, aircraft altitude, camera buttons per payload, a record button that auto-saves to Media Files) (PDF p.11). Through the separate **Virtual Cockpit** interface, operators can remotely trigger takeoff, return-to-home, camera zoom and gimbal orientation ([enterprise.dji.com/flighthub-2](https://enterprise.dji.com/flighthub-2)).

**Detail surface.** Device Details panel = telemetry/health snapshot + live camera feed, not a persistent tabbed profile in the material reviewed; UNVERIFIED whether a separate historical/maintenance tab exists per device beyond flight logs.

**Visibility rules (unusually rich).** FlightHub 2 has a genuine RBAC ladder, not just admin/user:
- Org level: **Super Administrator**, **Organization Administrator**, **Device Maintainer** ("manages all devices within the organization"), **Member** ("views project information, adds devices"), **Temporary Member** (limited, project-scoped).
- Project level: **Project Administrator** and **Project Member**, plus three newer customizable roles — **Project Output Admin**, **Project Device Operator**, **Project Device Admin**.
(Search synthesis of [Member Management](https://fh.dji.com/user-manual/en/organization-management/member-management/member-management.html) and [Project User Role Management](https://fh.dji.com/user-manual/en/organization-management/member-management/project-auth-management.html)). The standout idea: a role whose *entire* scope is device custody ("Device Maintainer" / "Project Device Admin") separate from general org admin.

**Supported fleet:** DJI Docks (Dock 3/2/1) + Matrice 400/350 RTK/30-series/4-series/4D-series + Mavic 3 Enterprise series ([enterprise.dji.com/flighthub-2](https://enterprise.dji.com/flighthub-2)).

**Not found / UNVERIFIED:** exact list-view column set for the Devices page (search and WebFetch on the live SPA docs returned only navigation chrome, not rendered content); bulk actions; export.

---

## 2. Skydio Cloud (Fleet Manager + Device Pages)

**List/grid structure.** **Fleet Manager** provides "federated management for distributed drone operations" ([How to use Fleet Manager in Skydio Cloud](https://support.skydio.com/hc/en-us/articles/4402745384475-How-to-use-Fleet-Manager-in-Skydio-Cloud)). Each fleet asset — drones, Docks, external radios, batteries, controllers — has its own **Device Page** showing "connectivity health, Cloud settings sync, serial numbers, software versions, lifetime flights, uptime, and sites," plus all systems connected to that device and *their* connectivity/health ([How to manage your Fleet with Device Pages](https://support.skydio.com/hc/en-us/articles/35641200297243-How-to-manage-your-Fleet-with-Device-Pages)). Direct WebFetch of these Zendesk articles returned 403 both times; content below is via Google's indexed search snippets of the same URLs, so treat wording as paraphrase-with-quotes rather than a verbatim page dump.

**Status encoding — the standout pattern.** The Fleet Page snapshot view uses a **three-tier health status**, explicitly not binary: **Healthy** (no known issues, ready for flight), **Limited Operation** (yellow — "some functionality is restricted, e.g. a battery has surpassed 300 cycles… the system can fly, but maintenance may be needed soon"), **Inoperable** (red — a critical issue preventing flight, immediate action required). **Hovering** a yellow/red status surfaces the specific cause, e.g. "Front Camera Failure," without leaving the list ([Skydio Cloud, DFR Command and Remote Ops — 3 April 2025](https://support.skydio.com/hc/en-us/articles/35583768187291-Skydio-Cloud-DFR-Command-and-Remote-Ops-3-April-2025)). This directly targets the fleet manager's "is it ready" question at the list level, before opening a detail page.

**Detail surface / verbs.** Controller Device Pages let admins rename the controller, add context-specific notes, and view metadata; Battery Device Pages show health status, firmware version, and notes (same 3 April 2025 release notes article, and [How to manage devices and batteries in Skydio Cloud](https://support.skydio.com/hc/en-us/articles/7621964659483-How-to-manage-devices-and-batteries-in-Skydio-Cloud), also 403 on direct fetch — search-snippet only).

**UNVERIFIED:** exact checkout/assignment-to-pilot flow, bulk actions, export, and non-admin visibility scoping — Zendesk's bot-blocking prevented full-article retrieval for these specifics.

---

## 3. Auterion Suite (fleet management for PX4-based vehicles)

**List/grid + role-based dashboard.** Auterion Suite is "a cloud-based web application for managing drone fleets, operations, and compliance at scale," with vehicles uploading flight logs/sensor data automatically for near-real-time fleet analytics ([auterion.com/product/suite/](https://auterion.com/product/suite/)). Critically, the **same dashboard is framed explicitly around different personas**: "Program managers can use the Dashboard to monitor fleet health and KPIs, while the Operations team can use it for deeper dives into recent flights and safety issues. Pilots can review their vehicles and flights, while security and compliance officers can quickly verify a program's compliance and download reports" (same URL) — this maps closely onto the fleet-manager / pilot / org-admin split in the target product.

**Maintenance.** A **Maintenance Schedules** feature auto-generates a maintenance plan "based on the recommendation from the manufacturer" the moment a drone is registered; users can "see the maintenance tasks due time for each vehicle and sort your vehicles based on that value," receive notifications/reminders, and assign tasks to specific Suite users ([Auterion Suite update announcement](https://auterion.com/auterion-suite-introduces-updates-that-centralize-the-overview-of-crucial-data-for-efficient-fleet-management/); corroborated by [Unmanned Systems Technology coverage](https://www.unmannedsystemstechnology.com/2024/09/new-update-for-auterion-suite-enhances-drone-fleet-management/)). Separately, **Unscheduled Maintenance** lets users log ad-hoc repairs with descriptions and images, tracked "in the vehicle's maintenance tab" — and Auterion states this will extend into **Auterion Mission Control** so pilots can report issues from the field (same announcement URL) — a roadmap item worth stealing (closes the loop between the person who notices damage and the fleet record).

**UNVERIFIED:** the actual vehicle-list column set, whether maintenance-due blocks flight (docs pages for `settings-and-maintenance` and `fleet-management/home` returned only navigation-index content or 404s when fetched directly), and precise permission enforcement (vs. UI framing) behind the four personas above.

---

## 4. Airdata UAV (fleet/flight-data platform with Enterprise asset add-on)

**Inventory model.** Airdata maintains "a central inventory of every drone and battery" ([airdata.com/features](https://airdata.com/features)).

**Maintenance.** Scheduled "by flight hours, cycles, or calendar," with alerts "before equipment service or certifications expire, so your operation stays deployment-ready" (same URL). Battery health specifically: "per-cell voltage deviation analysis and lifetime degradation trends," analyzing every cell after every flight so "when a cell begins to drift, users receive an alert early enough to replace a battery instead of losing an aircraft" (same URL).

**Checkout/checkin — the most concretely documented flow found in this survey.** Two entry points:
1. **Dashboard:** EQUIPMENT → DRONES → open drone profile → select pilot → **Check Out Drone** button.
2. **QR code:** scan the physical asset's QR code → **Check Out Drone**.
Who can do it: "Pilots with 'Edit' permissions can check out drones from ASSIGNMENT → Ownership" ([Checking out a drone](https://app.airdata.com/wiki/Help/Checking+out+a+drone)). **Location is captured automatically**: "Each time someone scans a QR code, AirData will record the location of the scan" (same page). What the record shows: a message "[Checked out this drone]" displayed next to the current custody owner (same page) — no evidence of a signature/acknowledgement step; this is UNVERIFIED as absent vs. simply undocumented. Checkin happens "via QR code or from the EQUIPMENT → DRONES page" ([QR Codes for Equipment Management](https://app.airdata.com/wiki/Help/QR+Codes+for+Equipment+Management)).

**QR code as a multi-purpose entry point (unique, worth stealing).** Scanning the *same* physical QR code shows a **menu of actions scoped to who is scanning**: an org member gets Check Out/Check In, **Perform Maintenance** (scan → Perform Maintenance → select service type → populate log → Complete Service), and (in development) defect reporting; an *external* person who finds a lost drone gets a **"lost and found" dialogue** to send an anonymous message to the owner (same QR Codes page). Check-in/out, defect recording and maintenance logging require the Enterprise plan; lost-and-found works for everyone (same page). All of this logs into the drone's **Assignment History** and **Pilot Usage History** tabs (same page) — i.e., the detail surface has history tabs fed by the physical-world QR interactions.

---

## 5. Dronedesk (asset register + maintenance log)

**Asset model.** Three asset categories: drone assets ("unlimited," pre-filled specs "for all major manufacturers"), battery assets (tracks "charge cycles and flight hours"), and other/ancillary assets (controllers, ground cameras) ([dronedesk.io/features](https://dronedesk.io/features)).

**Maintenance log.** "Record maintenance and anything else against any asset – all logs are timestamped and initialled" (same URL) — i.e., every maintenance entry carries an author + timestamp by design, not optionally. Scheduled intervals can be set "by days or flight time" with alerts when due (same URL).

**Checkout/tracking.** "Track all your assets with QR codes - check-in/out and set asset locations" (same URL) — QR-based, same pattern as Airdata.

**Unique modeling idea worth stealing.** For drones that fly with multiple batteries per session (e.g., DJI Inspire 2), Dronedesk lets you "group batteries and manage them as a single asset" (same URL) rather than tracking N battery rows that always move together.

**UNVERIFIED:** list-view column layout, detail-page tab anatomy, whether maintenance blocks scheduling/flight, bulk actions/export (feature marketing page didn't surface UI-level detail; blog posts referenced but not fetched for UI specifics).

---

## 6. DroneLogbook

**Coverage.** Tracks flight, drone, equipment, battery, location, incident and maintenance records together ([DroneLogbook Features](https://www.dronelogbook.com/hp/1/features.html), via search synthesis).

**Maintenance.** Notifies "when next maintenance or inspection is due"; supports building **inspection plans "as recommended by manufacturer"**; a fleet-wide maintenance view lets you see maintenance status "across all drones" (same URL). Maintenance is tracked down to the **component level** — "part serial number, lifespan and replacement history" — separate from the parent drone record (same URL).

**Roles/visibility.** An **Administrator** role can "set rules, transfer assets and information between groups, message users, receive notifications" on exceeded flight/ops parameters (same URL, via role-focused search). A separate **Personnel Rights model** includes a "View other personnel PII data" flag: when disabled for a user, "the logged-in user cannot see PII info of other organization team members," with organization administrators exempted from that restriction — **UNVERIFIED** exact source page (found via search synthesis referencing DroneLogbook's release notes, not independently fetched; likely at [blog.dronelogbook.com release notes](https://blog.dronelogbook.com/?page_id=134), unconfirmed).

---

## 7. Snipe-IT (generic IT asset management — checkout/checkin/audit/status reference)

**List view.** Columns are user-customizable; documentation says to "click the icon that looks like a split-pane window" to show/hide columns ([Snipe-IT Overview](https://snipe-it.readme.io/docs/overview)). Optional per-entry **color tags** render as "a square indicator next to the entry in list and detail views" for quick scanning (same page, via earlier search synthesis).

**Status labels — a clean 4-state model worth benchmarking against InventoryState.** Four underlying status *types*: **Deployable** (can be / is assigned), **Undeployable** (cannot be assigned), **Archived** (cannot be assigned, hidden from normal views), **Pending** (not yet assignable but will be). A deployable asset that is currently assigned additionally carries the *meta*-status "**Deployed**" (same Overview page). Note this cleanly separates "assignability class" (4 types) from "is it currently out" (a derived boolean) — arguably cleaner than a single flat enum.

**Checkout/checkin.** "When you checkout an asset, license or accessory, you're marking them as being in the possession of someone else"; an asset cannot be checked out to two people at once; checkins happen on staff departure or when repair is needed (same page). Best-practice guidance explicitly discourages checking assets out to a location rather than a person: "A location cannot be held responsible if an asset is broken or goes missing" ([Managing Assets](https://snipe-it.readme.io/docs/managing-assets)). Checkout form fields per GitHub issue discussion of the actual UI: Asset Name, Status (required), Location, Checkin Date (pre-filled to today), Notes, with Expected Checkin Date handled as a separate editable field ([issue #7928](https://github.com/snipe/snipe-it/issues/7928), [issue #6197](https://github.com/grokability/snipe-it/issues/6197)) — **UNVERIFIED** as current UI since sourced from dev-issue discussion, not a live screenshot; no evidence of a signature/acknowledgement capture step.

**Self-service.** "Unprivileged users [can] pick their own assets, check out assets to themselves, and add their own notes on checkout" when the feature is enabled ([issue #5994 discussion](https://github.com/snipe/snipe-it/issues/5994), via search synthesis) — i.e., checkout doesn't have to be admin-only.

**Permissions.** Two privileged tiers (Admin, Superadmin) plus granular per-permission grants (view/create/edit/delete assets, etc.), organized into **Permission Groups**; "users inherit permissions from the permission groups they are in, unless implicitly denied or granted permission on their user account" ([Permissions](https://snipe-it.readme.io/docs/permissions)). Admin/Superadmin grants **override and ignore** all other granular permissions (same page). **UNVERIFIED**: whether a non-admin's asset list is filtered to only their own checked-out assets by default, vs. read-only visibility into the whole fleet — not stated in the fetched excerpts.

**Bulk actions / export.** Built-in bulk operations across selected assets: bulk edit (status, model, location, purchase info, warranty, custom fields), bulk delete, **bulk checkout of multiple assets to one user/location/asset at once**, restore, and label generation ([Bulk Asset Operations, DeepWiki — community-maintained, not official Snipe-IT docs](https://deepwiki.com/grokability/snipe-it/2.4-bulk-asset-operations)). CSV import supports "Update Existing Values" to bulk-edit via re-upload ([Importing](https://snipe-it.readme.io/docs/importing)).

---

## 8. Fleetio (vehicle/asset fleet management — chosen over Samsara for depth of documentation retrieved)

**Detail page anatomy.** A vehicle's profile page uses **tabs**: Overview (details, activity, comments, attachments), Telematics, **Meter History** (odometer/other meters), **Service History**, **Work Orders**, Warranties ([Vehicle Overview](https://help.fleetio.com/vehiculos/vehicle-overview), [Meter Overview](https://help.fleetio.com/en_US/using-fleetio/meter-overview), via search synthesis).

**Status model.** Pre-defined, fully customizable **Vehicle Statuses**: Active, Inactive, In Shop, Out of Service, Sold; Status is a **required field**, defaulting to Active; the admin-facing Vehicle Statuses table shows a **Usage column** counting active + archived vehicles per status ([Vehicle Statuses](https://help.fleetio.com/asset-management/vehicle-statuses)). Practical framing: set a vehicle to "In Shop" purely "so you and others will know where it is and why it's not available" ([Tracking Vehicle Status blog](https://www.fleetio.com/blog/tracking-vehicle-status)) — status exists to answer "can I use this" at a glance, matching the target persona's "is it ready" question.

**Who can change status.** "Account Owners and Administrators can manage (add, edit and delete) Vehicle Statuses, while Regular Users with Role permission in the Vehicle module to Edit or to Update Status may make this change" ([Vehicle Statuses](https://help.fleetio.com/asset-management/vehicle-statuses)) — i.e., status *taxonomy* is admin-only, status *assignment* can be delegated per-role.

**Maintenance/service history.** **Service Entries** log completed maintenance: vehicle, odometer, completion date, vendor, reference number, then **Line Items** with labor/parts cost that auto-sum to a subtotal, plus custom fields, linked Issues, attachments/comments, and cost adjustments (markup/warranty/discount/tax) ([Service Entry Overview](https://help.fleetio.com/service-entries/service-entry-overview)). Completed **Work Orders auto-generate a Service Entry** — history isn't double-entered — and saving a Service Entry auto-resolves any linked open Issues (same page).

**Mobile.** The Fleetio Go app lets drivers/techs submit photo-based inspections (DVIR), scan barcodes to look up a vehicle/asset instantly, view asset profiles, and get service/renewal reminders push-style ([fleetio.com/go](https://www.fleetio.com/go), via search synthesis of app-store descriptions).

### Samsara (secondary vehicle-fleet reference)

Dashboard shows real-time vehicle locations/status and is user-customizable ([Samsara Telematics](https://www.samsara.com/products/telematics)). The **Maintenance Status report** aggregates unresolved DVIR defects, TPMS tire faults, and upcoming preventative-maintenance items in one place, sortable by asset/description/odometer/engine-hours/interval/PM-status; responding to an item offers **Resolve** (mechanic finished) or **Snooze** (defer to next interval) (search synthesis of [Review Upcoming Maintenance](https://kb.samsara.com/hc/en-us/articles/4405865438093-Review-Upcoming-Maintenance) and [Maintenance Status](https://kb.samsara.com/hc/en-us/articles/360042885132-Maintenance-Status) — both 403'd on direct WebFetch, Zendesk-blocked). Work orders are categorized: Unspecified, Preventative, Annual, Corrective, Damage Repair, Recall ([Work Orders for Maintenance Management](https://kb.samsara.com/hc/en-us/articles/32504504318989-Work-Orders-for-Maintenance-Management), same caveat).

---

## 9. VMS device lists (Frigate primary, Milestone XProtect secondary)

**Frigate.** The dashboard groups cameras into **camera groups**, each configured with a name, icon, and preferred streams; the default view is literally called **"All Cameras"** ([Live View config docs](https://docs.frigate.video/configuration/live/)). Per-camera tiles show a live/still image that is **static (refreshed once/minute) until motion or an object is detected, at which point it seamlessly switches to a live stream** — an explicit bandwidth-saving default (same page). Live status is marked by "a red dot in the upper right" of an actively-streaming tile (same page). Each camera's config is driven by FFmpeg inputs with **roles** (`detect`, `record`, `audio`) and inherits from global settings unless overridden ([Camera Configuration](https://docs.frigate.video/configuration/camera_specific/), via search synthesis).

**Milestone XProtect (UNVERIFIED depth — SPA docs, could not retrieve rendered body via WebFetch or targeted search).** Devices live under a **Devices node** in the Management Client's Site Navigation pane; selecting one shows current status in a **Preview pane** ([Devices node docs](https://doc.milestonesys.com/latest/en-US/standard_features/sf_mc/sf_ui/mc_devices_devices.htm), search-snippet only). Physical camera units are modeled as **Hardware**, with a recommended **"Add hardware wizard"** flow specifically because "Management Client will not retain the pre-configured credentials if you do not add the hardware to your system" through that wizard ([Hardware explained](https://doc.milestonesys.com/latest/en-US/standard_features/sf_mc/sf_systemoverview/mc_hardwareexplained.htm), search-snippet only). Milestone separately publishes a **Supported Device List** of 16,500+ tested devices ([milestonesys.com/support](https://www.milestonesys.com/support/software/supported-devices/)) — a compatibility reference, not a fleet UI pattern.

---

## Well-regarded UX writeups (table vs. cards, status-first design)

1. **NN/g — "Data Tables: Four Major User Tasks"** ([nngroup.com/articles/data-tables](https://www.nngroup.com/articles/data-tables/)). Tables must support: (1) find records matching criteria, (2) compare data, (3) view/edit/add a single row, (4) take action on records. Concrete guidance: give an editable row a visibly different look in edit mode so users don't accidentally edit; per-row action lists get unusable past a couple of items — "multiple single-record actions end up either crowded, with no text labels… or hidden under a hover gesture or a generic *Actions* menu, and thus hard to discover." Tables beat cards on two axes specifically relevant to a growing fleet: **scalability** (adding rows/columns is cheap) and **comparison** ("two adjacent data points are easy to compare because… users don't need to move their eyes much or store information in working memory").

2. **Pencil & Paper — "Data Table Design UX Patterns & Best Practices"** ([pencilandpaper.io/articles/ux-pattern-analysis-enterprise-data-tables](https://www.pencilandpaper.io/articles/ux-pattern-analysis-enterprise-data-tables)). Left-align text, right-align numbers; avoid zebra striping ("tricky… to effectively differentiate between disabled, hover, focused and active states" using stripes) — prefer thin 1px light-grey row dividers. **Row actions are hover-revealed** ("Table actions are typically afforded by hover states"), and **bulk-action controls only appear once rows are selected** ("Once one or more rows are selected, only then is it relevant to display said actions"). Offer column freeze/reorder/hide plus density presets (40/48/56px row heights) with persisted preferences and a reset-to-default. Distinguishes action-oriented, info-oriented, and hybrid table use-cases; cards suit lower-density data, tables win for complex/high-volume enterprise datasets.

3. **Pencil & Paper — "Dashboard Design UX Patterns Best Practices"** ([pencilandpaper.io/articles/ux-pattern-analysis-data-dashboards](https://www.pencilandpaper.io/articles/ux-pattern-analysis-data-dashboards)). Applies F/Z eye-scan patterns: put the most global/critical numbers **top-left**, general overview in the middle, detail breakdowns at the bottom. Calls out **"monitoring dashboards"** as the right model for alert-heavy views: "The point of this dashboard is to alert people to problems and anomalies." Recommends a **drawer pattern** for quick-glance detail without losing list context, reserving a full details page for deep history/records. Group related items visually so "people can understand what should be considered together and separately."

---

## Patterns that recur in 4+ products

| Pattern | Seen in |
|---|---|
| **Usage-based (not just calendar) maintenance-due tracking with pre-emptive alerts** — flight hours/cycles, odometer, engine hours, charge cycles | Airdata, Dronedesk, DroneLogbook, Auterion, Fleetio, Samsara (6) |
| **Timestamped, authored maintenance/service log entries as the atomic record**, decoupled from the reminder schedule itself | Dronedesk ("timestamped and initialled"), Auterion (unscheduled maintenance + images), Airdata (QR "Perform Maintenance" log), Fleetio (Service Entries), DroneLogbook (component-level replacement history) (5) |
| **A 3+ state health/status taxonomy encoded primarily by color**, not a flat boolean | Skydio (Healthy/Limited/Inoperable), DJI (online/offline<5min/offline>5min), Snipe-IT (Deployable/Undeployable/Archived/Pending + derived "Deployed"), Fleetio (Active/Inactive/In Shop/Out of Service/Sold), Samsara (PM status categories) (5) |
| **Per-asset detail page as a hub of sub-sections/tabs**: identity, live status/health, assignment/custody, maintenance or service history, sometimes documents | Skydio Device Pages, Fleetio vehicle profile (Overview/Telematics/Meter/Service/Work Orders/Warranties), DJI Device Details panel, Auterion vehicle maintenance tab, Snipe-IT asset page (5) |
| **QR/barcode as the bridge between the physical asset and its digital record**, used for checkout/checkin and often maintenance logging too | Airdata, Dronedesk, Snipe-IT (asset tags/labels), Fleetio (barcode scan-to-lookup in the mobile app) (4) |
| **Role-gated fleet visibility**: an org-wide admin/manager role sees everything, an operator/pilot role is scoped to "their" assigned assets or flights, with compliance/read-only roles in between | DJI (5-tier org + project roles), Auterion (program manager/ops/pilot/compliance framing), DroneLogbook (admin vs. PII-restricted personnel), Snipe-IT (self-service "my assets" mode) (4) |

## Patterns unique to one product, worth stealing

- **Skydio's hover-for-cause tooltip on a degraded status chip** (e.g. "Front Camera Failure" on hover over a yellow/red status) — answers "why isn't it ready" without leaving the list view. [Source](https://support.skydio.com/hc/en-us/articles/35583768187291-Skydio-Cloud-DFR-Command-and-Remote-Ops-3-April-2025)
- **DJI's dedicated "Device Maintainer" / "Project Device Admin" role**, scoped purely to device custody/maintenance and separate from general org administration — directly relevant to a fleet-manager persona whose job *is* device custody, not org configuration. [Source](https://fh.dji.com/user-manual/en/organization-management/member-management/member-management.html) (via search synthesis)
- **Airdata's QR code as a role-aware multi-tool**: the same physical code offers checkout/checkin + maintenance logging to an org member, but a "lost and found" anonymous-message dialogue to an external finder. [Source](https://app.airdata.com/wiki/Help/QR+Codes+for+Equipment+Management)
- **Fleetio's auto-generated Service Entry from a completed Work Order**, and auto-resolving linked Issues on save — avoids double data entry between "work order" and "history record." [Source](https://help.fleetio.com/service-entries/service-entry-overview)
- **Auterion's stated plan to let field pilots log unscheduled maintenance from the piloting app itself** (Auterion Mission Control), not just the fleet-management back office — closes the loop between whoever notices a problem and the asset record. [Source](https://auterion.com/auterion-suite-introduces-updates-that-centralize-the-overview-of-crucial-data-for-efficient-fleet-management/)
- **Dronedesk's battery-group-as-one-asset modeling** for drones that always fly with a fixed multi-battery kit — avoids tracking N rows that only ever move together. [Source](https://dronedesk.io/features)
- **Frigate's static-until-motion camera tile**: a live-video-heavy list defaults to a cheap still image and only pays streaming cost once something actually happens — a bandwidth-first default worth considering for any "watch every feed" tile view. [Source](https://docs.frigate.video/configuration/live/)

---

## Notable gaps / things this survey could NOT verify

- Exact checkout/checkin *signature or acknowledgement* capture step — not documented as present in Airdata, Dronedesk, or Snipe-IT sources reviewed; may simply be undocumented rather than absent.
- Skydio's own checkout/assignment-to-pilot mechanics, bulk actions, and non-admin visibility scoping — Zendesk (support.skydio.com) blocked all direct WebFetch attempts (403), so only search-engine-indexed snippets of those articles were available.
- DJI FlightHub 2's and Auterion Suite's actual list-view column sets — both platforms' documentation sites are JS-rendered SPAs that returned only navigation chrome to WebFetch; marketing pages don't specify columns.
- Milestone XProtect's device-list column set, per-row verbs, and detail-page anatomy — same SPA-rendering problem; only high-level structural facts (Devices node, Hardware concept, Add Hardware wizard) were recoverable via search snippets.
- Whether maintenance-due or "Inoperable"/"Out of Service" states technically *block* a flight/dispatch action in the underlying software (vs. just visually flagging it) — asserted implicitly by product marketing in several cases but not confirmed against an actual gating mechanism in any product surveyed.
