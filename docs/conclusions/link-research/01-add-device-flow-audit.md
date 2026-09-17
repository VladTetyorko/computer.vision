# Add-device flow audit (code-grounded, 2026-09-17)

Scope: the current experience of adding a camera/drone/rover, every entry point into it, how a
`Device` is identified today, what "Links" means in the UI, and where "simulate" competes with real
options. Read-only audit for `docs/plans/active/` link-abstraction design work. All claims are
`file:line`; no build was run.

## 1. The onboarding wizard, step by step

Six-step machine (`WizardStep`), three entrances (candidate/manual/equipment), rendered by
`station/vision-web/src/app/features/onboarding/onboarding.html:13-32`. Steps: `source → prove →
identify → attach → (sysid) → handover`; `sysid` only appears on a claim collision
(`docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md:242`).

### Source step (`source-step.html`)

First choice — **four fork tiles**, always shown (`onboarding-facade.ts:89-110`):

| Tile label | Hint shown |
|---|---|
| "It comes to us" | "Passive listening — the standing MAVLink lobby and the mediamtx push path pick this up on their own." |
| "We go to it" | "Type in a stream or telemetry address you already have…" |
| "Find it for me" | "Scan the local network for cameras, and listen for nearby drones already broadcasting telemetry." |
| "Nothing to connect" | "A battery, propeller, case, or other piece of gear…" |

Plus a fifth, always-rendered tile, "Provision Wi-Fi over USB" (`source-step.html:21-33`), disabled
with an explained reason outside Chrome/Edge.

Once a mode is picked, **two rows** are shown — Sense (telemetry) and Sight (video)
(`fit-out-logic.ts:17-29`, labels/hints at `:21-29`). Each row's tile grid
(`source-step.html:169-183`) offers, per role (`fit-out-logic.ts:41-44`):

- Sense: "Find nearby drones" (`listen`), "Add a real drone" (`drone`)
- Sight: "Enter a stream address" (`register`), "Find cameras on my network" (`discover`)
- **Every row also unconditionally shows "Use a test source"** (`source-step.html:178-182`,
  confirmed unconditional by `onboarding-facade.ts:159-162`) — simulate is never narrowed out, on
  any of the four fork tiles.

`register` opens a **Protocol dropdown with 8 real values + "Custom…"** (9 options total,
`protocols.ts:58-111`: `rtsp, mjpeg, srt, udp, v4l2, file, sim, mavlink`), a Stream-address field
(placeholder varies per protocol), and — only in Settings→advanced mode — a free-form
key/value "Stream options" editor (`source-step.html:257-304`). `discover` shows a scan-timeout
dropdown + Scan button + a results table (Method/Name/Address/Suggested category/Details/Use).
`listen` scans for MAVLink heartbeats and lists every vehicle heard with a "Use" per row
(`source-step.html:389-442`). `drone` is a **guided sub-wizard**: Firmware segmented control
(ArduPilot/INAV/Betaflight, `drone-config-logic.ts:22,27,30-34`) × Link segmented control (ELRS
backpack / ESP32-WiFi bridge / Companion computer, `:25,28,36-40`) → a compatibility verdict → a
config-copy screen with **this platform's own LAN address baked into the copy-paste block**
(`source-step.html:524-556`, block builders `drone-config-logic.ts:213-268`).

### Prove, Identify, Attach, Sysid, Handover

- **Prove** (`prove-step.html`): "Run test" (frame/telemetry probe) + optional "Observe vehicle"
  (full MAVLink profile listen) per unproven row; skipped entirely for a `passive`-entrance row
  (`onboarding-store.ts:505-514` doc comment) or `simulate`/`none` rows (`fit-out-logic.ts:131-133`).
- **Identify** (`identify-step.html`): Display name*, Category* (dropdown), Registration/tail
  (optional), Serial number (optional), Make (optional), Model (optional), Photo (optional,
  auto-downscaled). Only name+category are required (`identify-step.html:132-134`); name is often
  pre-filled from the candidate/vehicle name (`onboarding-store.ts:578-580,634-636`).
- **Attach** (`attach-step.html`): New-vehicle vs. existing-asset segmented toggle; existing-asset
  path adds one more dropdown.
- **Sysid** (`sysid-step.html`): one numeric field, only on a claimed-sysid collision.
- **Handover** (`handover-step.html`): pilot radio list + optional Location field, or "Leave in
  stock".

### Shortest-happy-path counts

| Scenario | Screens visited | Required fields | Notable clicks |
|---|---|---|---|
| (i) IP camera, RTSP/ONVIF discover | source → prove → identify → attach → handover (5) | Category only (name auto-filled from scan result) | "Find it for me" → "Find cameras on my network" → Scan → Use → Next → Run test → Next → (category) → Next → Create asset → Leave in stock ≈ **10 clicks** |
| (ii) MAVLink rover already broadcasting on Wi-Fi, heard passively | source → identify → attach → handover (**4**, Prove skipped) | Category only | "It comes to us" → Use (heard) → Next → (category) → Next → Create asset → Leave in stock ≈ **7 clicks** |
| (ii-b) same rover, manual scan instead of passive | source → prove → identify → attach → handover (5) | Category only | "Find it for me" → "Find nearby drones" → Scan → Use → Next → Run test → Next → (category) → Next → Create asset → Leave in stock ≈ **10 clicks** |
| (iii) drone with both video + telemetry | same 5 screens, **both rows filled in one visit** (`fit-out-logic.ts:202-209`) — no second pass needed | Category only | one `discover`/`register` action for Sight + one `listen`/`drone` action for Sense, then shared Prove/Identify/Attach/Handover |

The **worst-case first-time-ESP32 path** (scenario ii, but the rover isn't broadcasting yet, or is
a third-party FC behind a UART bridge) routes through the "drone" sub-wizard: 2 segmented picks
(Firmware, Link) → read a compatibility chip → copy 1-2 config blocks containing this platform's
own IP+port (`drone-config-logic.ts:213-219`, `:232-252`) → physically flash/paste onto hardware
outside the browser → "Continue — listen for it" → Scan → Use → Prove → Identify → Attach →
Handover. This is the path that re-embeds a from-scratch address into the device.

## 2. Other entry points

| Entry point | Creates | Identity key | Backend | UI |
|---|---|---|---|---|
| Inventory Found-devices card → Add dialog | `Device` (+ `Asset` via `createFromCandidate`) from a `DiscoveryCandidate` | `DiscoveryCandidate.identityKey` = `method\|address[\|sysid=n]` (see §3) | `POST /api/discovery/inbox/{id}/attach` (`DiscoveryInboxController.java:162-163`); dialog fields: Name (prefilled), Category (`add-candidate-dialog.html:8-37`) | `station/vision-web/.../inventory/found-device-card.ts:1-41`, `add-candidate-dialog.ts:1-63` |
| Discovery Inbox (list/register/dismiss/restore) | `DiscoveryCandidate` rows; `register` creates a `Device`/`Asset` | same `identityKey` | `GET /api/discovery/inbox`, `POST .../register`, `.../dismiss`, `.../restore` (`DiscoveryInboxController.java:89,114,131,181`) | Found-devices cards on `/assets` (per plan §3.1 OQ2, `SOURCE-ONBOARDING-2-PLAN.md:676-678`) |
| `/provision-wifi` (Improv Serial over Web Serial) | **nothing server-side** — pure USB→device Wi-Fi handoff, no `VisionApi` call at all (`provisioning-facade.ts:34-36`) | none — deliberately address-free: "the device is never told this platform's own address" (`provisioning.html:5,105-106`); the device announces itself afterward and is picked up by discovery | no backend endpoint | `station/vision-web/.../provisioning/provisioning.html` |
| `POST /api/simulations` | a whole `SimulatedAsset` (asset + device) | random `AssetId`, fixed `simulated` category — network-address-free by construction | `SimulationController.java:100-113` | onboarding's legacy Simulate sub-form (`source-step.html:595-687`) and Inventory's empty-state "Add the simulated source" button (`vehicles-table.html:15-18`, `inventory-facade.ts:444`) |

Only the Improv-Wi-Fi path and the simulation path have identity independent of network address by
design. Every discovery-based path's identity is `method|address[|sysid]` — for a camera, `address`
is the camera's own current URI/host (drifts with DHCP); for MAVLink, `address` is this platform's
own fixed bind URI, not the vehicle's peer IP (see §3) — the one already-robust case.

## 3. Identity model — what breaks on an address change

`Device` (`contexts/vision-warehouse/src/main/java/.../domain/model/Device.java:28`) has no
carrier/address field of its own — identity is `DeviceId` (random UUID) plus whatever the device's
`StreamDescriptor stream` (protocol+URI+options) happens to hold; that URI **is** the reachability
fact. `DeviceOrigin` (`core/vision-kernel/.../DeviceOrigin.java:18-24`) is LIVE/SIMULATED only — no
carrier notion. `Capability` (`core/vision-kernel/.../Capability.java`) is VIDEO/TELEMETRY/PTZ/AUDIO
— no CONTROL yet (open non-goal N5, `SOURCE-ONBOARDING-2-PLAN.md:661`). `DeviceCategory.connected`
(`contexts/vision-warehouse/.../domain/model/DeviceCategory.java:26`) only says whether a category
requires ≥1 device at all — equipment vs. vehicle, unrelated to carriers. `ProbeConnectionDraft`
has no Java counterpart at all — it is a frontend-only interface,
`{protocol, uri, options?}` (`station/vision-web/.../onboarding/onboarding-logic.ts:206-209`), the
wizard's in-memory mirror of the same `StreamDescriptor` triple.

**Two independent dedup checks exist, both keyed on address, neither on anything address-stable:**

1. `DiscoveryCandidate.identityKeyFor`
   (`contexts/vision-warehouse/.../domain/model/DiscoveryCandidate.java:92-99`):
   `method + "|" + address`, plus `"|sysid=" + n` when a sysid is present. `DefaultDiscoveryInboxService
   #report` recomputes this key **fresh on every report** and looks it up
   (`.../application/discovery/DefaultDiscoveryInboxService.java:84-89`) — sysid is appended, it
   never *replaces* address in the key. This gates whether a re-scan reuses an existing inbox row.
2. `AssetService#findDuplicateDevice` / the private `matchDevice`
   (`.../application/asset/DefaultAssetService.java:152-176`): walks every active `Device` for a
   literal `(protocol, uri, sysid)` triple-equality match, used both to block onboarding a true
   duplicate and to answer "have we seen this before" for discovery-candidate registration. Its own
   javadoc names the check `sameAirframe` (`DefaultAssetService.java:159-170`) — but it is address
   equality, not airframe identity: a camera re-added after a DHCP IP change fails this match (new
   `uri`) and onboarding silently creates a **second, orphan `Device`** for the same physical
   camera rather than erroring or updating the first.

- **Camera (ONVIF/mDNS/RTSP)**: `address` is the camera's own IP/URI. A DHCP-issued new IP produces
  a new `identityKey` → a brand-new `DiscoveryCandidate` row, unlinked from the old one, which goes
  stale silently. The registered `Device.stream.uri` (the old IP) is never auto-updated. Backend
  *can* re-point it — `PATCH /api/devices/{id}` explicitly supports "rename, re-point at a new URI,
  retune options" (`station/vision-api/.../controller/DeviceController.java:124-145`) — but **no
  frontend surface exposes a URI-edit field**: the Devices/Links table and Asset Detail both render
  `device.uri` read-only with a Copy button (`station/vision-web/.../devices/devices.html:113,362-363`;
  `.../asset-detail/asset-detail.html:450`). The only user-visible remedy today is re-running
  onboarding against the camera's new address, orphaning the old `Device`.
- **MAVLink (ESP32/FC over Wi-Fi)** — actually the **robust** half: the scanner's `address` is this
  platform's own fixed listen socket (`URI.create("udp://" + DEFAULT_BIND_HOST + ":" + port)`,
  `drone-link/mavlink/.../MavlinkHeartbeatScanner.java:277`), not the vehicle's ephemeral source
  IP/port — so the `identityKey` and the registered `Device.stream.uri` never change when the
  vehicle's IP changes. Claiming is by **sysid alone**
  (`VehicleClaimPolicy.claim(int sysid)`, `drone-link/mavlink/.../VehicleClaimPolicy.java:209-232`);
  the peer address used to send commands back is tracked live per claim and re-derived on every
  heartbeat, never persisted. `docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md:96-104` (P1)
  names this exact design goal — "DHCP moves stop mattering" — and its Z2b wave shipped the GCS
  heartbeat reply that makes lock-on possible (`ZERO-CONFIG-ONBOARDING-CONTEXT.md:338,366-367`).

**What actually breaks, concretely:**

| Scenario | Breaks | User must redo |
|---|---|---|
| ESP32 rover gets a new DHCP IP, *already broadcasting to the lobby's fixed `:14550` socket* | Nothing — sysid claim and command routing self-heal | Nothing |
| ESP32/FC bridge configured via the wizard's "Add a real drone" flow (ELRS/ESP32-bridge/Companion) | The **target address baked into the copy-paste config on the device itself** (`esp32TargetBlock`/`elrsTargetBlock`/`companionMainConfBlock`, `drone-config-logic.ts:201-260`) — this is the station's LAN IP, not the vehicle's | Re-open the wizard's "drone" config screen, re-copy the new block, re-flash/re-paste onto every such device individually — **no central override** |
| Station itself moves to a different Wi-Fi/subnet | Every device provisioned via the config-copy recipe above now targets a dead IP; `VISION_WEBRTC_HOST` (`.env:12-19`, one value, one place) is the one config item this app already gets right by contrast | Regenerate + re-apply config on **each** bridge/backpack/companion device by hand |
| Camera (RTSP/ONVIF) gets a new IP | `Device.stream.uri` (old IP) stops resolving; old `DiscoveryCandidate` goes stale, a new one appears unlinked; re-onboarding it creates a **second orphan `Device`** since `matchDevice`'s `(protocol,uri,sysid)` check no longer matches the old row (`DefaultAssetService.java:164-170`) | Re-run onboarding against the new address (no in-app rebind/edit path exists), then find and delete/deactivate the stale old `Device` by hand (`DELETE /api/devices/{id}` exists, `DeviceController.java:181`, but nothing surfaces "this looks like a duplicate of X") |
| `/provision-wifi` (Improv) device joins a new Wi-Fi | Nothing address-shaped was ever written to the device | Nothing — it re-announces and is re-discovered |

The Z2b "lobby" fix already solves the *listening* half of the address problem for MAVLink; the
still-open gap (named "Z6 firmware", explicitly deferred — `ZERO-CONFIG-ONBOARDING-CONTEXT.md:345-346`)
is the *device* side actually implementing "broadcast until heard, then lock on" — and the
wizard's own "drone" config-copy recipe (§1) still generates the older, hardcoded-target style of
config, unchanged by Z2b. Two mechanisms for the same problem coexist in the same app today.

## 4. What "Links" means today

Inventory's "Links" tab (`station/vision-web/.../inventory/inventory.html:147`) is a plain label on
the tab bar (`:147-160`) that, when selected, renders `<vision-devices [sel]="sel()"
[embedded]="true" />` (`inventory.html:174-176`) — **the Devices page**, verbatim
(`station/vision-web/.../devices/devices.ts`, selector `vision-devices`). It lists `Device` rows —
name, protocol, URI, capabilities, owning asset — searchable "by name, protocol, URI, or owning
asset" (`devices.html:8-9`). It is not a distinct entity or endpoint; "Links" is simply the
product's chosen display name for the Devices/warehouse table.

Grepping `contexts/` and `core/` for genuine `Link`/`Carrier`/`Transport`/`Radio` **type**
declarations turns up nothing that models an interchangeable device carrier:

| Type | File:line | What it actually is |
|---|---|---|
| `EventLink` | `contexts/vision-perception/.../WorldObject.java:55` | Correlates a detection event to a world object — unrelated |
| `DetectionRateWindow.Transport` | `contexts/vision-perception/.../DetectionRateWindow.java:51` | Internal enum for CV rate-control bookkeeping — unrelated |
| `ManualControlLink` | `contexts/vision-flight/.../domain/port/ManualControlLink.java:15` | Opaque handle to one **engaged RC relay session** (operator↔vehicle command channel) — closest in spirit, but scoped to the live manual-control session, not to reaching/identifying a device |
| `LinkLossNotifier` | `contexts/vision-flight/.../application/alerting/LinkLossNotifier.java:44` | Alerts on loss of the above session |
| `SimulationTransport` / `TelemetryTransport` | `contexts/vision-simulation/.../application/SimulationTransport.java:10`, `TelemetryTransport.java:13` | Enumerate simulation output modes — unrelated |

**Honest conclusion: there is no first-class carrier/link abstraction in the Java domain model
today.** Reachability is fully baked into each `Device`'s own `protocol`+`uri`+`options` string
triple; nothing represents "this device is reachable via Wi-Fi, or via an nRF24 radio, or over
direct serial" as an interchangeable, swappable concept. A generic `Link`/`Carrier` port sitting
between `Device` identity and its current transport would be new work, not a rename of something
that already exists.

## 5. Where "simulate" competes with real options

| Surface | file:line | How it appears |
|---|---|---|
| Onboarding source step, every row, every fork mode | `source-step.html:178-182`; unconditional per `onboarding-facade.ts:159-162` | "Use a test source" tile, styled identically to the real finder tiles, always present regardless of whether the operator chose "It comes to us"/"We go to it"/"Find it for me" |
| Onboarding legacy Simulate sub-form | `source-step.html:595-687` | A 4-mode picker (direct/RTSP/testDrone/synthetic) with its own field set, rendered inline below the two role cards whenever Sight is set to simulate |
| Inventory empty state | `vehicles-table.html:15-18` | "Register the built-in simulated source…" message + "Add the simulated source" button, shown before any real vehicle exists |
| `/fly` drone picker | `drone-picker.html:18-26,97-103` | A separate "Simulated (N)" group with a **"Hide simulated" checkbox**, persisted per-browser in `localStorage` (`drone-picker-facade.ts:23,109,157`) |
| Cockpit | `cockpit.html:265-267,351-352` | "Simulated feed" / "· Simulated" badges on an already-open asset |

No backend or config-level flag exists to suppress simulate options for a whole deployment: a
repo-wide grep of `.env`/`.env.example` and `station/vision-app/src/main/resources/application.yaml`
for `simulation.enabled`/`vision.simulation` finds nothing. The only mitigation shipped is the
**client-side, per-operator, default-off** "Hide simulated" toggle on the `/fly` picker
(`drone-picker-facade.ts:109`: `readPersistedFlag(HIDE_SIMULATED_KEY, false)`) — it has no
equivalent on the onboarding wizard or the Inventory page, where simulate tiles/buttons render
unconditionally next to real ones.

## Verified defects / surprises

1. **Two contradictory MAVLink-address stories coexist.** The lobby (Z2b, merged) makes station-side
   MAVLink identity address-independent by design; the wizard's own "Add a real drone" sub-wizard
   (§1) still hands the operator a copy-paste block with the station's *current* LAN IP baked in
   (`drone-config-logic.ts:213-260`) — the exact failure mode Z2b was built to remove, reintroduced
   by an unrelated, unconverted code path in the same feature.
2. **A backend capability with no UI.** `PATCH /api/devices/{id}` can re-point a device's URI
   (`DeviceController.java:124-134`), but neither the Devices/Links table nor Asset Detail exposes a
   URI-edit control — only Copy. A camera that gets a new DHCP IP has no in-app fix path at all
   today.
3. **The dedup key is not sysid-first.** `DiscoveryCandidate.identityKeyFor` always includes
   `address` and only *appends* sysid; it does not prefer sysid when present
   (`DiscoveryCandidate.java:92-99`). It happens to work for MAVLink today only because the scanner's
   "address" is the platform's own fixed bind socket, not the vehicle's address — a fragile
   coincidence, not a designed invariant.
4. **"Links" is a naming choice, not a concept.** Anyone reading the Inventory tab bar and
   expecting a carrier/transport model will find the Devices table instead
   (`inventory.html:147,174-176`). There is genuinely nothing to migrate from — a new Link/Carrier
   abstraction has no legacy shape to preserve.
5. **Simulate is structurally inseparable from real onboarding**, not merely visually adjacent: the
   "Use a test source" tile is rendered by the exact same unconditional branch as the real finder
   tiles (`source-step.html:169-183`), so hiding it for a production deployment would need a new
   gating mechanism, not a config flip of something that already exists.
6. **A moved camera silently forks into two devices, not one broken one.** Re-onboarding a camera
   after its IP changes doesn't fail loudly or update the old record — `matchDevice`'s
   `(protocol, uri, sysid)` equality (`DefaultAssetService.java:164-170`) simply no longer matches,
   so a second, independent `Device` is created for the same physical camera. Nothing in the product
   flags the old one as probably-stale; it just sits in Inventory looking like a second camera.
