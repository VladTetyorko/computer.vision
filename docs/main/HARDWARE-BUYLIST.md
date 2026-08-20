# HARDWARE-BUYLIST — entry hardware, ranked by what it turns on in this app

Status: **buy list (2026-08-19).** The execution companion to
[`TWO-TARGETS-PLAN.md`](TWO-TARGETS-PLAN.md) §TARGET-1 (which tiers to buy, H1–H4) and
[`../conclusions/DRONE-COMPONENTS-MATRIX.md`](../conclusions/DRONE-COMPONENTS-MATRIX.md) §3
(the component ladder, ranked by *features unlocked per euro*). Those two answer **why**;
this one answers **what to put in the cart**.

Ordering is the ladder's ordering: **most-used first, single-feature last.** Prices are
approximate AliExpress street prices, EUR, August 2026.

## 0. Link policy — read once

Every link below is an **AliExpress search URL**, not a specific listing. Deliberate:

- Listing item-IDs on AliExpress rot within weeks; a search never 404s.
- Price, shipping and availability are **destination-dependent** — a search run from your
  account gives you the correct local answer, a pasted listing does not.

The search terms are chosen so the first page is the right part. The **Trap** column is
what to verify in whichever listing you pick — that is where the real value of this
document is, not the URLs.

## 1. Buy order, plainly

| Step | Tier | ~€ | Unblocks |
|---|---|---|---|
| 1 | **T1 Eyes** | 40–70 | real CV on real scenes, tracking, S2 fixed-camera geolocation |
| 2 | **T2 Telemetry link** | 5–25 | ~15 features — the best ratio in the whole system |
| 3 | **T3 Vehicle brain** (FC + GPS + ELRS + radio) | 150–250 | the entire MAVLink half, for real |
| 4 | **T4 Airframe — rover first** | 60–110 | the honest end-to-end test, at zero crash cost |
| 5 | **T5 Companion + IP link** | 60–120 | Class C/D: video **and** telemetry over one pipe |
| — | T6 Specialist sensors | 20–400+ | one feature each — buy only when that feature is the job |

**Total to a genuinely flying-shaped system: ~€260–450**, of which the first €80 unlocks
most of the platform (DRONE-COMPONENTS-MATRIX §3: *"ranks 1–3 cost under €80 total"*).

---

## 2. T1 — Eyes (buy first) · ~€40–70

Turns the platform from simulation into watching the actual world. **Zero integration work
— all three ingest paths already ship.**

| # | Item | ~€ | Turns on | Search | Trap |
|---|---|---|---|---|---|
| 1 | **USB webcam, 1080p, UVC** | 20–40 | `adapter-v4l2` — plug in, Discover, one click to a live tile | [search](https://www.aliexpress.com/w/wholesale-usb-webcam-1080p-uvc.html) | Must be **UVC** and emit **MJPEG** (not YUYV-only, which caps 1080p at 5 fps). Ignore "4K interpolated" |
| 2 | **ESP32-CAM (AI-Thinker, OV2640)** | 6–12 | `adapter-mjpeg` — **already field-verified against a real ESP32-CAM** (see `video-input/mjpeg/MODULE.md` Gotchas) | [search](https://www.aliexpress.com/w/wholesale-esp32-cam-ov2640.html) | **No USB on the board** — needs #3 to flash. Get the variant with the u.FL antenna connector. Brown-outs on a weak 5 V supply are the #1 failure |
| 3 | **USB-TTL programmer (CP2102 / CH340)** | 2–4 | flashing #2 and every ESP32 in T2 | [search](https://www.aliexpress.com/w/wholesale-cp2102-usb-ttl-adapter.html) | Get one with a **3.3 V/5 V jumper**. CP2102 has better Linux driver luck than CH340 |
| 3b | *Alt to #2+#3:* **XIAO ESP32-S3 Sense** | 14–20 | same MJPEG path, **native USB**, no programmer | [search](https://www.aliexpress.com/w/wholesale-xiao-esp32s3-sense.html) | Pricier per unit but skips the flashing pain entirely. Recommended if you value the afternoon |
| 4 | **Tripod / clamp mount** | 10–20 | **S2 fixed-camera geolocation** — a *known, fixed* camera pose is the measurement reference | [search](https://www.aliexpress.com/w/wholesale-desktop-tripod-clamp-mount.html) | Not optional for S2. A camera that drifts between sessions invalidates the geolocation demo |
| 5 | *Optional:* **ONVIF/RTSP IP camera** | 20–35 | `adapter-rtsp` RX + `adapter-discovery` ONVIF scanner — the third protocol, discovered not typed | [search](https://www.aliexpress.com/w/wholesale-onvif-rtsp-ip-camera.html) | Must expose a **plain RTSP URL** (many cheap "smart" cams are cloud-app-only — useless here) |

**Why two protocols matter:** #1 and #2 running simultaneously proves the multi-protocol
claim with hardware instead of a diagram.

---

## 3. T2 — Telemetry link · ~€5–25 · **rank 1 in the ladder**

`infra/edge/` has three recipes; two of them need hardware from this table. This is the
single best euro-to-feature ratio in the system: ~15 features (map position, geofence,
failsafe banners, preflight, battery, wind field, energy-aware return, deconfliction, link
prediction, terrain AGL, all of ANY-DRONE).

| # | Item | ~€ | Recipe | Search | Trap |
|---|---|---|---|---|---|
| 6 | **ESP32-WROOM-32 dev board** | 4–7 | `infra/edge/esp32-bridge.md` — DroneBridge for ESP32 → UDP :14550 | [search](https://www.aliexpress.com/w/wholesale-esp32-wroom-32-devkit.html) | Plain WROOM-32 devkit, **not** a `-CAM` and **not** a C3/C2 (DroneBridge targets ESP32/S3). 3.3 V UART — check your FC's telem port isn't 5 V |
| 6b | *Alt:* **Wemos D1 mini (ESP8266)** | 2–4 | same recipe, `mavesp8266` firmware | [search](https://www.aliexpress.com/w/wholesale-wemos-d1-mini-esp8266.html) | Older, telemetry-only, fine. Needs a 6-pin header soldered |
| 7 | **JST-GH 1.25 mm 6-pin cables** | 3–6 | connects #6 to `TELEM1`/`TELEM2` | [search](https://www.aliexpress.com/w/wholesale-jst-gh-1-25mm-6-pin-cable.html) | **The most-forgotten part in this document.** Buy an assorted set (4/5/6-pin), you will need all of them |
| 8 | **3.3 V ↔ 5 V level shifter** | 1–2 | insurance for 5 V telem ports | [search](https://www.aliexpress.com/w/wholesale-3-3v-5v-logic-level-shifter.html) | Buy it even if you think you don't need it — €1 against a dead ESP32 |
| 9 | *Optional:* **SiK telemetry pair (433/868/915 MHz)** | 25–45 | L4 link — 5–20 km, no WiFi range limit | [search](https://www.aliexpress.com/w/wholesale-sik-telemetry-radio-mavlink.html) | **Buy your region's legal band** (EU: 433/868. 915 is illegal in most of Europe). 100 mW versions only |

**The €0 option:** if your radio (#14) has an **ELRS MAVLink backpack**, `infra/edge/elrs-backpack.md`
gives you telemetry with *zero extra hardware* — ArduPilot ≥4.5. Try this before buying #6.

---

## 4. T3 — The vehicle brain · ~€150–250

Where "ESP" ends and a real autopilot begins. Everything here speaks the MAVLink the
`drone-link/mavlink` adapter already decodes (17 message types, ArduPilot copter/plane/**rover**
mode tables all shipped).

| # | Item | ~€ | Turns on | Search | Trap |
|---|---|---|---|---|---|
| 10 | **H743 flight controller** *(recommended)* | 45–70 | the whole telemetry + command-TX surface, with headroom | [search](https://www.aliexpress.com/w/wholesale-h743-flight-controller-ardupilot.html) | **Verify the exact board has a current target on `firmware.ardupilot.org`** before ordering. Needs ≥3 free UARTs, a real barometer, and SD or flash logging |
| 10b | **F405 flight controller** *(budget)* | 25–40 | same, flash-constrained | [search](https://www.aliexpress.com/w/wholesale-f405-flight-controller-ardupilot.html) | F4 targets are increasingly **minimal-featureset** on ArduPilot 4.7 (features trimmed to fit flash). Fine for a rover; buy H743 if buying new |
| 11 | **GPS + compass module (M10 / M8N)** | 15–35 | map baseline, geofence, RTL, missions, P0 in the position stack | [search](https://www.aliexpress.com/w/wholesale-m10-gps-module-compass-qmc5883.html) | Get **M10 with an integrated compass** (QMC5883/IST8310). Check the connector matches your FC — or buy #7 |
| 12 | **Power module / current sensor (XT60)** | 6–12 | battery telemetry → **energy-aware return (C3)**, battery health (B2) | [search](https://www.aliexpress.com/w/wholesale-ardupilot-power-module-current-sensor-xt60.html) | Without current sensing there is no energy-aware anything. Match your cell count (3S–6S) |
| 13 | **ELRS receiver, 2.4 GHz** | 10–18 | RC control, and the free MAVLink backpack path | [search](https://www.aliexpress.com/w/wholesale-expresslrs-elrs-receiver-2-4ghz.html) | Band **must match your radio**. ELRS v3 firmware. A diversity RX is worth the extra €4 on a rover |
| 14 | **RadioMaster Pocket / Boxer (EdgeTX + ELRS)** | 60–120 | **RC-CONTROL Phase 1** — the joystick path already on `master` | [search](https://www.aliexpress.com/w/wholesale-radiomaster-pocket-elrs-edgetx.html) | Must run **EdgeTX** with **USB Joystick (HID)** mode. Remember: HID mode and the RF module are *mutually exclusive* (RC-CONTROL-PLAN §"The hardware fact") |
| 15 | **Buzzer + safety switch** | 3–6 | ArduPilot arming flow, audible failsafe | [search](https://www.aliexpress.com/w/wholesale-ardupilot-safety-switch-buzzer.html) | Cheap, and the thing that tells you *why* it won't arm without a laptop |

---

## 5. T4 — The airframe · ~€60–250

**Buy the rover first.** DRONE-COMPONENTS + TWO-TARGETS both land on this and it is the
single highest-value call in the plan: identical MAVLink, exercises gateway → discovery →
wizard → telemetry → geofence → failsafe → command TX → RC relay → passport, **and it
cannot fall out of the sky.**

| # | Item | ~€ | Note | Search |
|---|---|---|---|---|
| 16 | **RC car chassis, 1/10 or 1/16, brushed** | 60–110 | the recommended first vehicle | [search](https://www.aliexpress.com/w/wholesale-1-10-rc-car-chassis-brushed-kit.html) |
| 17 | *Later:* **5" FPV frame + motors + 4-in-1 ESC + props** | 120–200 | only after the rover has proven the stack | [search](https://www.aliexpress.com/w/wholesale-5-inch-fpv-drone-frame-kit.html) · [motors/ESC](https://www.aliexpress.com/w/wholesale-fpv-4in1-esc-motor-combo-2207.html) |
| 18 | *Optional:* **analog FPV camera + VTX** | 25–45 | L1 video downlink; poor quality but it is video | [search](https://www.aliexpress.com/w/wholesale-fpv-camera-vtx-5-8ghz-analog.html) |

### Batteries — do **not** buy these on AliExpress

LiPos ship badly (or not at all) to the EU and arrive at unknown state of charge. **Buy
locally**, and buy the charger and the safe bag with them:

| Item | ~€ | Note |
|---|---|---|
| LiPo 3S/4S 1500–5000 mAh, XT60 | 15–40 ea | 2S–3S is plenty for a rover |
| Balance charger (ISDT/HOTA class) | 35–60 | with a storage-charge mode — LiPos die stored full |
| LiPo safe bag + fire-safe surface | 10–15 | non-negotiable |

---

## 6. T5 — Companion + IP link · ~€60–120

The upgrade that changes *what class of aircraft you are*: `infra/edge/companion-rpi.md`
is the only recipe carrying **video and telemetry over one link** (Class C/D). `adapter-rtsp`
already ingests `rtsp`, **`srt`** and `udp` — SRT is the one for lossy cellular.

| # | Item | ~€ | Turns on | Search | Trap |
|---|---|---|---|---|---|
| 19 | **Raspberry Pi Zero 2 W** | 18–30 | mavlink-router + ffmpeg → one pipe | [search](https://www.aliexpress.com/w/wholesale-raspberry-pi-zero-2-w.html) | On Pi OS, `/dev/ttyAMA0` is shared with Bluetooth — the recipe's `dtoverlay=disable-bt` is mandatory |
| 19b | *Alt:* **Orange Pi Zero 2W** | 20–35 | same role, more RAM/CPU | [search](https://www.aliexpress.com/w/wholesale-orange-pi-zero-2w.html) | Weaker OS support; you will do more of your own debugging |
| 20 | **CSI camera module (IMX219) or reuse #1** | 10–20 | the video half of the recipe | [search](https://www.aliexpress.com/w/wholesale-raspberry-pi-camera-module-imx219.html) | Zero 2 W uses the **mini CSI** connector — buy the right ribbon |
| 21 | **4G LTE USB modem** | 20–40 | the uplink | [search](https://www.aliexpress.com/w/wholesale-4g-lte-usb-dongle-modem.html) | **Start with a phone hotspot instead** — €0, proves the whole path before you buy anything |
| 22 | **microSD 64 GB A2/U3** ×2 | 8–14 | OS + spare | [search](https://www.aliexpress.com/w/wholesale-microsd-card-64gb-a2-u3.html) | Buy **two**. Cheap cards are the #1 cause of "the Pi randomly died" |

---

## 7. T6 — Specialist sensors (one feature each — buy on demand)

Ladder ranks 5–12. Each unlocks a narrow capability; none is a prerequisite for anything above.

| Rank | Item | ~€ | The one thing it buys | Search | Verdict |
|---|---|---|---|---|---|
| 5 | **TFmini-S / TF-Luna rangefinder** | 20–45 | true AGL, precision landing, **TERCOM (P8)** | [search](https://www.aliexpress.com/w/wholesale-tfmini-s-lidar-rangefinder.html) | Multi-feature and cheap — the best of this tier |
| 6 | **PMW3901 optical flow** | 15–25 | GNSS-denied velocity hold, **low AGL only (P5)** | [search](https://www.aliexpress.com/w/wholesale-pmw3901-optical-flow-sensor.html) | Cheap, narrow |
| 7 | **Gimbal, 2/3-axis brushless** | 50–200 | click-to-follow, stable geolocation, **much better VPR (P7)** — known camera attitude | [search](https://www.aliexpress.com/w/wholesale-2-axis-brushless-gimbal-fpv.html) | First genuinely expensive step; big quality jump |
| 8 | Thermal module (Lepton class) | 200+ | night ops, thermal fusion | [search](https://www.aliexpress.com/w/wholesale-flir-lepton-thermal-module.html) | Single-domain, high cost |
| 9 | RTK GNSS pair | 150–400 | cm-accurate survey | [search](https://www.aliexpress.com/w/wholesale-um982-rtk-gnss-module.html) | **Single-feature, and still fully jammable** — solves accuracy, not resilience |
| 10 | CRPA anti-jam antenna | 300–3000 | GNSS under jamming | — | **Argue against it.** P6–P8 (visual odometry, VPR, TERCOM) do the same job for **€0 of drone-side hardware.** This is the platform's strongest cost argument |
| 12 | Onboard ADS-B receiver | 200 | traffic awareness | — | **Don't.** The base does it from a ground feed for €0 (B1) |

---

## 8. Bench kit — the stuff nobody lists and everybody needs · ~€60–100

| Item | ~€ | Search |
|---|---|---|
| **Smoke stopper (XT60)** | 5–10 | [search](https://www.aliexpress.com/w/wholesale-smoke-stopper-xt60.html) |
| Soldering iron kit (T12 class) + solder + flux | 25–45 | [search](https://www.aliexpress.com/w/wholesale-soldering-iron-kit-t12.html) |
| Digital multimeter | 10–20 | [search](https://www.aliexpress.com/w/wholesale-digital-multimeter.html) |
| XT60/XT30 connector pairs, silicone wire, heat shrink | 10–15 | [search](https://www.aliexpress.com/w/wholesale-xt60-connector-pairs.html) |
| Dupont / JST jumper wire assortment | 5–10 | [search](https://www.aliexpress.com/w/wholesale-dupont-jumper-wire-kit.html) |
| *Optional:* adjustable bench PSU (30 V/10 A) | 45–80 | [search](https://www.aliexpress.com/w/wholesale-adjustable-bench-power-supply.html) |

**Buy the smoke stopper.** It is €7 and it stands between one reversed polarity and a dead
€60 flight controller. It is the highest-value item in this entire document per euro.

---

## 9. Firmware compatibility — what the platform actually accepts

From `infra/edge/README.md`, verified:

| Firmware | Minimum | Notes |
|---|---|---|
| **ArduPilot** | ≥ 4.5 | Full telemetry + command passthrough. **This is the one to buy for** |
| INAV | ≥ 8 | **Monitor-only** — its MAVLink is transmit-only. No two-way config/mission |
| Betaflight | ≥ 2025.12.0-beta | MAVLink telemetry landed only in this release; earlier has none at all |

Known platform limit today: one device per `udp://host:port` locks onto the first sysid heard.
Two aircraft = two ports until DRONE-INFRA I-a lands.

---

## 10. What this list deliberately does not include

- **Starlink Mini** — 1.1 kg, 25–40 W, $249. A fixed-wing/heavy-multirotor decision, not an
  entry one. It is a *link*, not a position source (DRONE-COMPONENTS §1.2).
- **Mesh relay hardware** — L7 in the link table, marked *"punch feature, later"*. It solves
  multi-aircraft-beyond-cellular. Not the problem blocking the first flight.
- **Anything at H3/H4 before the rover flies.** Buying the aircraft first means debugging
  software and flying an aircraft at the same time — recorded in TWO-TARGETS §H4 as an
  ordering mistake already made once.
