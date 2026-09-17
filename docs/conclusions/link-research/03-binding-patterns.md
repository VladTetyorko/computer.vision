# Binding, identity, and roaming — how mature ecosystems pair a controller to a vehicle

**Research only. No code.** External web research comparing pairing/binding/identity models across RC
links, telemetry radios, mesh and IoT provisioning, and smart-home commissioning standards, done to inform
a pairing model for DIY vehicles (ESP32 rovers, ArduPilot copters, IP cameras) that must roam across
carriers (Wi-Fi/UDP, nRF24-behind-Arduino, serial, later LoRa/ELRS) without re-configuring everything every
time a device is lost or the network changes. Every non-obvious factual claim below carries a source URL;
inferences and secondary-sourced (not primary-doc-verified) claims are labelled inline.

---

## 1. Comparison table

| System | Identity owner | Ceremony (what the human does, how long) | Out-of-band secret? | Multi-TX → one RX? | Multi-RX per TX? | Roams across carriers? | Loss-of-link behavior | What the station stores |
|---|---|---|---|---|---|---|---|---|
| ExpressLRS (RC link) | Shared UID = MD5(bind phrase)[0:6], baked into both firmwares | Set one phrase before flashing (zero-touch), or power-cycle RX 3× + press Bind on TX (~10 s) | Yes — the phrase, chosen by the human, never transmitted whole | Yes, unscoped by default (danger) | Yes, unscoped | No — RF link only | Configurable per-channel failsafe (hold/no-pulse) | Just the phrase text; Model Match ID is a second, narrower gate |
| ExpressLRS Backpack | Same phrase/UID mechanism, over ESP-NOW | Same phrase set on both backpacks, or 3× power-cycle + Bind | Yes, same phrase (can reportedly be set independently of the main link phrase) | Yes | Yes | No | N/A (side channel, not RC) | Nothing beyond the phrase |
| TBS Crossfire (plain bind) | Per-pair RF sync, not a persistent ID (inferred — protocol internals closed) | Hold RX bind button ~3 s within ~1 min power-on window, select Bind on TX | No — physical-access window only | No (last bind wins) | Yes, sequential | No | Not verified in this pass | RX bind state only |
| TBS Crossfire (Multibind) | TX-family User ID fetched from TBS's cloud, stamped into RX | As above, plus a one-time cloud ID fetch over Wi-Fi per module | Yes — cloud account/User ID | Yes, via shared cloud ID | Yes | No | Not verified in this pass | Cloud-fetched User ID per module |
| FrSky ACCESS | RX addressed by a Receiver Number slot (0–63); ACCESS encryption is proprietary | Two steps: Register (hold RX button while powering, confirm on TX), then Bind (assign slot, power-cycle, confirm) — tens of seconds | No — physical-access window | No (rebind overwrites — UNVERIFIED against a primary doc) | Yes, distinct slots per RX | No | Not verified in this pass | Receiver-number mapping in TX model memory |
| Spektrum DSMX | TX model-memory GUID stamped into RX ("RX learns the TX") | Bind plug in RX, power RX, hold TX bind button while powering TX, LED solid — ~20–30 s | No — physical-access window | No (ModelMatch enforces one TX model) | Yes, natively — independent sequential binds | No | Not verified in this pass | GUID lives in TX model memory + RX |
| SiK / RFD900 (point-to-point) | Shared NETID integer (default 25) — a network concept, not a device ID | Type same NETID + channel params into both radios (AT command or GCS GUI push) | No — often left at default; cross-talk observed even across *different* NETIDs | N/A — transparent bridge, but same-NETID-and-band radios *will* cross-talk | N/A (P2P) | No | RADIO_STATUS injected into MAVLink stream (rssi/remrssi/noise/txbuf/rxerrors); no radio-level failsafe | Nothing — NETID lives in radio EEPROM on both ends |
| RFD900 Multipoint firmware | Numeric Node ID per radio, one master, 255 = broadcast | Manual AT-command Node ID assignment per radio — no GUI tool at time of the manual | No | Master addresses many nodes individually | Yes — that is the point | No | Same RADIO_STATUS injection | Node-ID↔vehicle mapping (operator's own bookkeeping) |
| Meshtastic | Channel PSK (0/16/32-byte AES key) + name; node ID derived from device MAC | Configure the same PSK/name/region, or scan a QR/deep-link carrying the whole channel set | Weak — the PSK is a key, not an address, but the QR/link carries it in the clear once shared | N/A (mesh, any PSK-holder is a full peer) | N/A | No (LoRa mesh only) | No per-node failsafe — node just stops appearing until heard again | Nothing centrally; each node keeps its own local peer table |
| ESP-NOW (raw + DIY auto-pair) | Raw peer MAC address; no handshake in the base API | Community "auto-pair" pattern: broadcast a PAIR_REQUEST, peer replies with its MAC — automatic, no human ceremony beyond powering both on | No — pure MAC discovery; optional AES key if configured manually | Up to 20 peers (≤16 encrypted) | Same | No (Wi-Fi radio layer only) | None built in — application-defined | Table of peer MAC addresses |
| Improv Wi-Fi + ESPHome adopt | None persistent — a one-shot Wi-Fi credential handoff, not a device bind | Device advertises (BLE/serial); browser (Web Bluetooth/Web Serial, no app) sends SSID+password; device confirms — under a minute | No — open exchange, not a claimed security layer | N/A | N/A | Yes — this *is* the "moved to new Wi-Fi" ceremony | N/A (setup-time only) | ESPHome dashboard stores a generated API-encryption key once adopted |
| Espressif unified provisioning (POP) | Session key from X25519 exchange, gated by a Proof-of-Possession string baked into firmware | Phone app scans/connects to device's SoftAP/BLE advert, user enters/scans POP, credentials delivered encrypted — under a minute | Yes — the POP is the out-of-band secret | N/A | N/A | Yes, same POP-gated flow re-provisions | N/A | Nothing centrally (phone-app driven) |
| Matter | Discriminator (12-bit, picks device) + passcode (27-bit PAKE secret) → per-fabric certificate after commissioning | Open a commissioning window, scan QR/enter code, PASE (SPAKE2+) session, device attestation, cert issued — under a minute, window ~minutes | Yes — passcode/QR, never sent in the clear (SPAKE2+) | Yes by design — "fabrics," many independent controllers | Yes, standard | Partially — reopening a window re-admits without factory reset | Transport-specific, not part of Matter itself | Per-fabric certificate material (NOC), not the setup passcode long-term |
| Zigbee install codes | Per-device 16-byte random code, factory-printed, hashed into a unique Trust Center link key | Scan the barcode/QR during Trust Center join — a few seconds | Yes — the printed code | N/A | N/A | No (bound to that network's Trust Center) | Install code / derived link key per device until network key delivered |
| Wi-Fi Easy Connect / DPP | Device's own public key, encoded in its QR/NFC tag | Scan QR with phone/AP acting as Configurator — a few seconds | Yes — the QR-encoded public key is the trust anchor | N/A | N/A | Credential ("Connector") is bound to device identity, portable across compatible networks | N/A | Normal Wi-Fi membership records |
| BLE bonding | Negotiated Long-Term Key, stored by both sides after bonding | Varies: Just Works (none), Passkey (type/compare a 6-digit code), OOB (tap NFC) | Only in the OOB model; Passkey uses a human-mediated code as a weaker analog | N/A | N/A | No — bonding is per-device-pair | Simply drops; no faked liveness | Bonded LTK on both ends |
| DJI / Autel consumer link | RC↔aircraft radio pairing, factory pre-linked or manually relinked | Hold a button on the aircraft (~4 s) until an LED sequence signals ready, trigger link on RC/app, confirm within a countdown — under a minute | No — physical possession + timing window is the gate | No — exclusive, relink invalidates the old link | One RC can remember several aircraft, but one active link at a time | No | Return-to-home / hover failsafe (industry-standard, not deeply sourced here) | N/A — the RC *is* the station |
| Skydio (USB-C pairing) | Physical USB-C link between drone and Controller/Beacon | Plug in the cable once; LED confirms; auto-reconnects thereafter when both are powered | No | No — a Beacon pairs to a single aircraft at a time | N/A | No | Not sourced in this pass | N/A |
| Tello / Parrot (Wi-Fi-direct, contrast case) | None — whoever knows the drone's current Wi-Fi SSID/password | Phone joins the drone's own Wi-Fi AP like any hotspot | No | N/A (anyone on the Wi-Fi controls it) | N/A | No, and no roaming beyond Wi-Fi range | Drone just drops the association | Nothing — no persisted identity at all |

---

## 2. RC links: bind phrase / UID as a shared secret

**ExpressLRS.** The human-chosen binding phrase is run through MD5 and the first 6 bytes of the digest
become the shared **UID**, baked into TX and RX firmware — confirmed independently by two security
write-ups, not just the user-facing docs: [Fox-IT technical advisory](https://www.fox-it.com/nl-en/technical-advisory-expresslrs-vulnerabilities-allow-for-hijack-of-control-link/),
[Simon Koeck — ELRS security](https://simonkoeck.com/blog/elrs-security-what-is-possible). The UID seeds
the FHSS hop-sequence generator deterministically on both ends — it is a **shared seed**, not a classical
RF sync word, and official docs are explicit that "the binding phrase is *not* used for security, it is
used to prevent collisions" ([expresslrs.org/faq](https://www.expresslrs.org/faq/),
[quick-start/binding](https://www.expresslrs.org/quick-start/binding/)). Because both firmwares can be
pre-flashed with the same phrase, **binding requires no runtime ceremony at all** when devices are built
together — a button-press bind is only needed when pairing after the fact. **Model Match** is a second,
independent gate on top: the RX stores a small Model Match ID (0–63) and the TX's model memory carries a
matching Receiver number; if they don't match, the RF link still connects (UID matched) but goes into a
"half connection" that passes no channel data — UID and Model Match ID are separate fields
([expresslrs.org/software/model-config-match](https://www.expresslrs.org/software/model-config-match/)).
Default behavior is genuinely dangerous for multi-vehicle use: **any TX broadcasting a given UID will be
obeyed by any RX bound with the same phrase**, and maintainers document this as a real hazard, recommending
unique phrases per TX plus "Volatile Bind" (RX forgets its binding every power-cycle) as mitigation
([Discussion #2652](https://github.com/ExpressLRS/ExpressLRS/discussions/2652),
[#2747](https://github.com/ExpressLRS/ExpressLRS/discussions/2747)). Failsafe is configurable per channel
("No Pulses"/"Last Position" for SBUS, an explicit µs value per PWM channel;
[pwm-receivers](https://www.expresslrs.org/hardware/pwm-receivers/),
[issue #1165](https://github.com/ExpressLRS/ExpressLRS/issues/1165)). The **Backpack** (an ESP8285/ESP32 on
the TX module and the goggles/VRX) talks over ESP-NOW for VTX-channel sync, DVR triggering, and telemetry
mirroring, and pairs using the same bind-phrase mechanism, reportedly settable independently of the main
link phrase (lightly sourced;
[esp-backpack](https://www.expresslrs.org/hardware/backpack/esp-backpack/),
[Backpack wiki](https://github.com/ExpressLRS/Backpack/wiki)). Native **MAVLink mode** landed in **ELRS
3.5.0**: the RX's serial provider becomes MAVLink instead of CRSF, tunneling MAVLink frames through the
telemetry channel as real serial passthrough, and ELRS injects its own RSSI/LQ into a RADIO_STATUS-style
report that INAV/Betaflight key off directly
([mavlink](https://www.expresslrs.org/software/mavlink/),
[Betaflight MAVLink+ELRS](https://betaflight.com/docs/wiki/guides/current/MAVLinkELRS)). The predecessor
**AirPort** mode is a generic transparent tunnel but replaces RC control entirely, needing a second TX/RX
pair alongside it ([airport](https://www.expresslrs.org/software/airport/)). For the core link, identity
really is "a key, not an address": no discovery step lets TX and RX learn each other's MAC or IP, only a
pre-shared seed operating at the raw PHY layer.

**TBS Crossfire.** Standard bind: hold the RX bind button (~3 s) within roughly a one-minute post-power-up
window, then select Bind on the TX
([NoirFPV FAQ](https://noirfpv.com/tbs-crossfire-faq/), [Oscar Liang](https://oscarliang.com/crossfire-betaflight/)).
Crossfire's RF/bind internals are closed, so what's exchanged isn't publicly documented; each new bind
overwrites the prior one, suggesting a fresh per-pair RF sync rather than a persistent identity (inference).
The one documented persistent identity is **Multibind** (≥V3.71): each TX module fetches a **User ID from
TBS's cloud** once over Wi-Fi, stored in the RX at bind time so any module sharing that cloud ID can control
it without a fresh bind ([Enable Multibind](https://oscarliang.com/enable-tbs-crossfire-multibind/)) —
whichever module powers on first wins if two are live, and TBS advises powering only one at a time.
Crossfire is closed hardware+firmware, so no independent "receiver clones" were found; BetaFPV/iFlight Nano
RX are TBS-licensed OEM hardware ([expresslrs.org vs. Crossfire](https://oscarliang.com/expresslrs/)).

**FrSky ACCESS.** A two-step **Register-then-Bind** flow: Register while holding the RX's F/S button during
power-up, then Bind against a chosen **Receiver Number** slot (0–63) with a plain power-cycle
([Bind guide](https://oscarliang.com/bind-frsky-access-receiver/),
[R9 ACCESS manual](https://www.frsky-rc.com/wp-content/uploads/Downloads/Manual/R9/R9%20ACCESS-Manual.pdf)).
The Receiver Number is a routing slot, not a cryptographic identity; ACCESS is internally encrypted, which
is why open-source multiprotocol modules emulate the older D16/D8 but not ACCESS
([multi-module.org](https://www.multi-module.org/using-the-module/protocol-details/frsky-rx)). Community
consensus (not a primary FrSky statement): one RX binds to one TX at a time and rebinding is destructive,
while one TX can bind several RX via distinct slots.

**Spektrum DSMX.** Binding transfers the **TX model memory's GUID** into the RX — "the receivers are
learning the transmitter," not the reverse ([FlyingRC.net](https://flyingrc.net/dualrx.html)). Because the
GUID is tied to a *model memory*, not just the physical TX, **ModelMatch** falls out for free: switching
model memory makes a bound RX refuse to respond. Multi-RX from one TX is fully supported for FPV+backup
redundancy — binding a second RX doesn't disturb the first, and both blindly obey the same TX signal with
no mutual awareness (same source). Third-party "Lemon RX" receivers are marketed as DSM2/DSMX-*compatible*,
not literal clones, with documented protocol-mode caveats
([Lemon RX manual](https://www.flyingtech.co.uk/wp-content/uploads/2021/02/Lemon_RX_manual_V2.pdf)).

## 3. SiK / RFD900: identity as a shared network number, not a device pairing

SiK's **NETID** (param S3, default **25**) is a plain shared integer that seeds the frequency-hopping
pattern; it is set with AT commands (`ATS3=`, `RTS3=`) or through Mission Planner/QGroundControl's radio
config screen, and "for two radios to communicate, NETID must be the same for both"
([ArduPilot — SiK advanced configuration](https://ardupilot.org/copter/docs/common-3dr-radio-advanced-configuration-and-technical-information.html)).
There is no per-device ID anywhere in the firmware's parameter table
([SiK `parameters.h`](https://github.com/ArduPilot/SiK/blob/master/Firmware/radio/parameters.h)) — NETID is
a *network* concept, and it is not even a hard boundary: a documented GitHub issue shows real cross-talk
between radios on *different* NETIDs sharing a frequency range
([SiK issue #40](https://github.com/ArduPilot/SiK/issues/40)). Radios are a **transparent serial bridge**:
whatever bytes arrive on one UART come out the other, with no protocol awareness
([ArduPilot — SiK telemetry radio](https://ardupilot.org/copter/docs/common-sik-telemetry-radio.html)).
When `MAVLINK=1`, the radio watches for MAVLink HEARTBEAT traffic and then injects its own **RADIO_STATUS**
packets (rssi, remrssi, noise, remnoise, txbuf, rxerrors, fixed) into the serial stream, matching
`mavlink.io`'s RADIO_STATUS field list
([ArduPilot docs](https://ardupilot.org/copter/docs/common-3dr-radio-advanced-configuration-and-technical-information.html),
[MAVLink RADIO_STATUS](https://mavlink.io/en/messages/common.html#RADIO_STATUS)). A separate
**RFD900x Multipoint** firmware (distinct product from stock SiK) adds real node addressing: each radio
gets a numeric **Node ID** (max 16 valid IDs, 255 = broadcast/listen-all), one radio is master, and unicast
uses a persistent "Node Destination" parameter or a per-command `RT,[x]` target (RFD900x Multipoint manual,
lightly verified — PDF text could not be cleanly extracted, corroborated only via secondary mirrors). This
is distinct from software-level fan-out tools like **mavlink-router**, which replicate one MAVLink stream to
many GCS endpoints by routing on `target_system`/`target_component` in software, not in the radio
([mavlink.io routing guide](https://mavlink.io/en/guide/routing.html),
[mavlink-router](https://github.com/mavlink-router/mavlink-router)). There is no pairing ceremony anywhere
in this ecosystem — "same NETID = same network," full stop; ArduPilot's own multi-vehicle guidance is simply
to manually assign distinct NETIDs per vehicle to avoid collisions, an operational convention, not an
access-control mechanism ([ArduPilot — multi-vehicle flying](https://ardupilot.org/copter/docs/common-multi-vehicle-flying.html)).

## 4. Meshtastic, ESP-NOW, ESP32 provisioning

**Meshtastic.** A channel's identity is its **name + PSK** (0/16/32-byte AES key); node identity is a
separate node number derived from the device's MAC address, distinct from the user-editable long/short name
([Channel Configuration](https://meshtastic.org/docs/configuration/radio/channels/),
[Meshtastic Encryption](https://meshtastic.org/docs/overview/encryption/),
[User Configuration](https://meshtastic.org/docs/configuration/radio/user/)). There is no join/bind
ceremony beyond configuring the same PSK, name, and region — possession of the key is the only gate, and a
node hearing an unknown peer simply exchanges NodeInfo automatically
([Known Limitations](https://meshtastic.org/docs/about/overview/encryption/limitations/)). Channel sharing
via QR code encodes a base64url `ChannelSet` protobuf (PSK, name, modem preset, region) inside a
`meshtastic.org/e/#…` deep link — the URL *is* the key exchange, carrying the PSK in the clear once shared
([D-Central — Meshtastic channel URL reference](https://d-central.tech/meshtastic-channel-url-reference-what-a-shared-channel-link-actually-contains/)).

**ESP-NOW.** The base API is pure MAC-address peering: `esp_now_add_peer()` must register a peer's MAC
before unicast traffic, with no discovery or handshake built in, and encryption (an AES Local Master Key)
is opt-in and manual
([ESP-IDF ESP-NOW API](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/network/esp_now.html)).
The common DIY pattern layered on top avoids hardcoding MAC addresses: broadcast a `PAIR_REQUEST` to
`FF:FF:FF:FF:FF:FF`, have the counterpart capture the sender's MAC and reply with its own, then switch to
unicast — a state machine with channel-hopping retries
([RandomNerdTutorials — ESP-NOW auto-pairing](https://randomnerdtutorials.com/esp-now-auto-pairing-esp32-esp8266/),
[tomorrow56/ESPNowAutoPairing](https://github.com/tomorrow56/ESPNowAutoPairing)). This is a community
convention, not an Espressif standard.

**Improv Wi-Fi and ESPHome adoption.** Improv is an open standard for handing Wi-Fi credentials to a device
over BLE or Serial, drivable straight from a browser via Web Bluetooth/Web Serial with no native app — no
captive-portal round-trip is needed ([improv-wifi.com](https://www.improv-wifi.com/),
[improv-wifi GitHub](https://github.com/improv-wifi)). ESPHome layers two mechanisms on top: fresh hardware
runs `esp32_improv`/`improv_serial` (with an explicit authorization step before credentials are accepted),
falling back to its own AP via `captive_portal` if association still fails
([esp32_improv](https://esphome.io/components/esp32_improv/),
[captive_portal](https://esphome.io/components/captive_portal/)); separately, devices carrying
`dashboard_import` project metadata announce over mDNS, and the ESPHome Dashboard offers a one-click
**Adopt** that pulls the public config, generates a fresh API-encryption key, and reflashes
([dashboard_import docs](https://api-docs.esphome.io/dashboard__import_8h),
[PR #4393](https://github.com/esphome/esphome/pull/4393)). Both are the same shape: **the device announces,
the station adopts** — never the reverse.

**Espressif unified provisioning.** `wifi_provisioning` runs a `protocomm` session over SoftAP or BLE using
an **X25519 key exchange plus a Proof-of-Possession (POP)** string baked into firmware; once the session is
authenticated with the POP, all further messages (including the actual Wi-Fi SSID/password) are encrypted
under the negotiated session key — even though the underlying SoftAP/BLE transport itself has no security
of its own. The companion phone apps require the operator to enter or scan the POP before the device treats
the session as authorized; an insecure "Security 0" mode exists for testing only
([ESP-IDF Wi-Fi Provisioning](https://docs.espressif.com/projects/esp-idf/en/v5.1/esp32/api-reference/provisioning/wifi_provisioning.html)).

## 5. Commissioning standards: the ceremony shape

**Matter.** The setup payload has a 12-bit **discriminator** (picks the right device among several
simultaneously advertising) and a 27-bit **passcode**, packaged as an 11-digit manual code (with a Verhoeff
check digit) or a richer QR
([Silicon Labs — Matter Commissioning](https://docs.silabs.com/matter/latest/matter-overview-guides/matter-commissioning),
[Matter Alpha — QR codes explained](https://www.matteralpha.com/explainer/how-does-matter-qr-code-work)).
Devices only accept commissioning inside a bounded **commissioning window**, opened at factory reset or on
demand by an administrator, not permanently
([Matter Handbook](https://handbook.buildwithmatter.com/how-it-works/commisioning/),
[Google Home Developers](https://developers.home.google.com/reference/com/google/android/gms/home/matter/commissioning/CommissioningWindow)).
The passcode is never sent in the clear — **PASE** runs **SPAKE2+**, a password-authenticated key exchange,
so possession of the printed/QR code is the out-of-band secret but it is only ever proven, not transmitted
([Silicon Labs, same source](https://docs.silabs.com/matter/latest/matter-overview-guides/matter-commissioning);
[CSA security whitepaper](https://csa-iot.org/wp-content/uploads/2022/03/Matter_Security_and_Privacy_WP_March-2022.pdf)).
Critically, **one device can be commissioned into multiple independent controller fabrics simultaneously**
— Apple Home, Google Home, and Alexa each get their own fabric and certificate on the same device, a
deliberate multi-admin design, not an accident
([Google Home Developers — The Fabric](https://developers.home.google.com/matter/primer/fabric),
[Silicon Labs — Multicontroller Ecosystem](https://docs.silabs.com/matter/2.7.0/matter-ecosystems/multicontroller-ecosystem)).
Commissioning happens over BLE (mandatory for Thread) or Wi-Fi Soft-AP/IP before the device has full network
credentials, which are then delivered over the now-secured PASE session.

**Zigbee install codes.** A per-device 16-byte random code, factory-printed as a barcode/QR, is hashed
(AES-MMO) into a unique per-device Trust Center link key used only to protect the initial network-key
delivery — replacing reliance on the well-known global default key `"ZigBeeAlliance09"` that pre-3.0 devices
left enabled, a documented sniffing risk during forced rejoin
([Silicon Labs — Zigbee Installation Codes](https://docs.silabs.com/zigbee/latest/using-installation-codes-with-zigbee-devices/02-security-use),
[Kaspersky Securelist — Zigbee assessment](https://securelist.com/zigbee-protocol-security-assessment/118373/)).

**Wi-Fi Easy Connect / DPP.** A device's QR code or NFC tag encodes its own **public key**; the Configurator
(phone/AP) scans it to bootstrap trust, then runs elliptic-curve authentication and key exchange to deliver
Wi-Fi credentials — explicitly built to avoid the legacy pattern of "security-sensitive credentials passed
in the clear"
([Wi-Fi Alliance — Easy Connect](https://www.wi-fi.org/beacon/dan-harkins/wi-fi-easy-connect-simple-and-secure-onboarding-for-iot)).

**BLE bonding.** Just Works offers no MITM protection; Numeric Comparison and Passkey Entry do, via a
displayed/compared or typed code; Out-of-Band exchanges the pairing secret over a separate physical channel
like NFC ([Bluetooth.com — Pairing Part 1](https://www.bluetooth.com/blog/bluetooth-pairing-part-1-pairing-feature-exchange/),
[Part 4 — Numeric Comparison](https://www.bluetooth.com/blog/bluetooth-pairing-part-4/),
[Part 5 — OOB](https://www.bluetooth.com/blog/bluetooth-pairing-part-5-legacy-pairing-out-of-band/)).
Bonding is explicitly the storage of the resulting long-term key so the ceremony is not repeated on
reconnection (same source).

Across Matter, Zigbee, DPP, and BLE, the recurring shape is: **discovery is broadcast/in-band (anyone
nearby can see a device advertising), but authorization requires a possession-based secret exchanged or
proven out-of-band** — a printed code, a QR, a physical tap. This framing is this document's synthesis
across the sourced specifics above, not a single quoted claim.

## 6. Consumer drones (brief)

**DJI / Autel.** RC and aircraft typically ship pre-linked from the factory; re-linking is a manual
ceremony — hold the aircraft's power button until an LED sequence signals ready, then trigger linking on
the RC/app within a short window
([DJI Aircraft Linking Guide](https://support.dji.com/help/content?customId=en-us03400006734&spaceId=34&re=US&lang=en),
[Autel EVO pairing](https://www.autelpilot.com/blogs/tips-tutorials/how-to-pairing-remote-controller-to-evo-drone)).
DJI separately distinguishes this radio-level "linking" from account-level "binding" (tying serial numbers
to a DJI cloud account for warranty/flyaway-report purposes)
([DJI Account and Device Binding](https://support.dji.com/help/content?customId=en-us03400006568&spaceId=34&re=FI&lang=en)).
Linking is exclusive — screen RCs can remember several previously-linked aircraft, but only one link is
active at a time, and relinking to a new RC supersedes the old one (secondary-sourced for the exact
invalidation behavior). **Skydio** pairs its Controller and Beacon to the aircraft over a **wired USB-C
cable** rather than an RF ceremony, auto-reconnecting once both are powered
([Skydio Controller Guide](https://support.skydio.com/hc/en-us/articles/360040037893-Skydio-Controller-User-Guide),
[Skydio Beacon pairing](https://support.skydio.com/hc/en-us/articles/4476132039579-How-to-pair-the-Skydio-Beacon-to-your-devices)),
with a Beacon paired to only one aircraft at a time. **Tello and Parrot ANAFI** are the contrast case: the
drone broadcasts its own Wi-Fi AP, and the phone joins it like any hotspot — there is no persisted bound
identity beyond momentary knowledge of the current SSID/password, and no roaming beyond Wi-Fi range
([Tello User Manual](https://dl-cdn.ryzerobotics.com/downloads/Tello/Tello%20User%20Manual%20v1.4.pdf),
[Parrot ANAFI pairing](https://www.parrot.com/en/support/anafi/how-to-pair-my-device-with-my-anafi-series-drone)).

---

## 7. Design principles that recur across the winners

1. **Identity is a key the device holds, never an address.** ELRS's UID, Meshtastic's PSK, Matter's
   passcode/NOC certificate, DPP's public key — none of them are an IP address, a MAC, or a serial port.
   The systems that *do* use an address as identity (SiK's NETID-as-network, raw ESP-NOW MAC pairing,
   Tello's Wi-Fi password) are exactly the ones that break when hardware is swapped or the network changes.
2. **Discover in-band, authorize out-of-band.** A device broadcasts its presence openly (mDNS, BLE advert,
   Improv, a Meshtastic node hello), but joining requires a possession secret moved through a separate,
   human-mediated channel — a printed code, a QR, a phrase, a button held during a timing window. Only the
   weak options (Just Works, Zigbee's pre-3.0 default key, Tello's ambient Wi-Fi password) skip this.
3. **The station adopts; the device announces.** ESPHome's dashboard, Matter's commissioner, a Zigbee Trust
   Center: the smart, stateful end initiates ownership of a dumb, broadcasting device. None of the winning
   models expect the device to register itself against a central authority it has to already know about.
4. **Bind once, any holder of the key may act — by design, not by accident.** ELRS ("any TX with the
   phrase"), Meshtastic ("any node with the PSK"), Crossfire Multibind: shared-secret identity is inherently
   copyable, which is the whole point for multi-operator use, and ecosystems that need isolation add a
   second, narrower gate on top (Model Match, Matter fabrics, a receiver-number slot) rather than making the
   base identity exclusive.
5. **Rebinding is destructive by default; multi-owner is an explicit, separately-toggled mode.** Plain
   DSMX/ACCESS/Crossfire binds overwrite the previous pairing; ELRS's Volatile Bind, Crossfire's Multibind,
   and Matter's fabrics are all *opt-in* multi-owner layers bolted on top of a single-owner default.
6. **The secret is short and human-portable, not a managed credential store.** A bind phrase, a PSK, an
   11-digit passcode, a 16-byte install code — all of them are things a person can say, write on tape, or
   scan, with no PKI infrastructure required to mint or read one.
7. **Loss-of-link means the device holds known-safe state, not that the link pretends nothing happened.**
   ELRS's per-channel failsafe values, SiK's RADIO_STATUS injection instead of silent link death, BLE simply
   dropping a bond rather than faking liveness — the honest options degrade visibly.
8. **A short, supervised, physical-access window substitutes for cryptography when the ceremony is rare.**
   RC binds, DJI/Autel linking, and Zigbee's factory-scanned install code all lean on "you're standing next
   to it, right now, for a few seconds" as the trust anchor. Ecosystems expecting the ceremony to happen
   in less-controlled conditions add real key exchange instead (Matter's SPAKE2+, DPP's public-key exchange,
   Espressif's POP+X25519).
9. **Portable identity survives losing the hardware; addressable identity does not.** Meshtastic, Matter,
   and Improv-provisioned devices can move to new networks or even new physical units without breaking the
   human's mental model of "the same thing I paired before." NETID, raw ESP-NOW MAC pairing, and
   Wi-Fi-password-as-identity all force reconfiguration everywhere the old address was hardcoded — exactly
   the pain point driving this research.

## 8. Recommended pairing model for our case

**What each vehicle stores.** A random bind secret (comparable to an ELRS UID or a Zigbee install code — a
short key, not a certificate) generated **by the device itself at first boot**, not baked in at flash time
and not defaulted to anything guessable. The secret is the vehicle's identity; it is independent of Wi-Fi
IP, nRF24 pipe address, or serial port, exactly per principle 1 — whatever carrier the vehicle is reached
over in a given session is transport information, rediscovered each time, never persisted as identity.

**What the station stores.** A registry entry — `VehicleId` (matching the existing kernel `X.random()` id
style) → bind secret → friendly name/category (the existing Asset model) → last-known carrier + address
(ephemeral, refreshed on every discovery, never authoritative for "which vehicle is this"). The station is
also the **registrar** for transmitters: it is the one place a bind secret is minted, and the one place a
second TX is handed a copy of an existing vehicle's secret — never a phrase a human has to type identically
into three separate firmware images by hand (the ELRS failure mode that "Volatile Bind"/Multibind exist to
paper over).

**Ceremony — first pairing (brand-new ESP32 rover).**
A vehicle with no bind secret generates one and enters an announce mode for a bounded window (minutes, or
until power-cycle) over whatever carrier it currently has — Wi-Fi SoftAP, USB serial, or an nRF24 broadcast
address — mirroring Matter's commissioning window and ESPHome's fallback-AP/Improv pattern. The station's
"add device" wizard listens for that announcement, asks the operator one question ("New rover found — what
should we call it?"), and the two sides establish trust the same way Zigbee's install code or Espressif's
POP do: physical presence during the open window stands in for a printed code, since a DIY 3D-printed rover
has nowhere to print one. The station stores the resulting `VehicleId` + secret + name; the vehicle stores
the secret in NVS and drops out of announce mode into normal operation, from then on requiring the secret
(e.g., as an ELRS-style derived hop-seed or an HMAC over each packet) to accept any command. No running link
exists yet at this step, so nothing is disrupted.

**Ceremony — re-pairing after a lost ESP32.** Because identity is a key, not an address, there is nothing
to "re-pair" on the network side — a lost vehicle's secret simply goes unreachable. The operator runs the
*first pairing* ceremony again on the replacement hardware, gets a brand-new `VehicleId`, and then
explicitly re-associates it with the old Asset record if continuity of history (usage records, telemetry)
matters — a deliberate merge action, not automatic, so a rogue or misconfigured board can't silently inherit
another vehicle's history. The station should let the operator explicitly retire the old secret (matching
Matter's model of an admin-controlled fabric membership) rather than leaving it live indefinitely.

**Ceremony — adding a second TX.** Because "any transmitter holding the key may talk" is the explicit goal
here, this never touches the vehicle. The station, already holding the secret, provisions the new TX over a
short local/wired step (serial flash or an OOB tap), copying the existing `VehicleId` + secret across —
exactly ELRS's "any TX with the same phrase" or Crossfire's Multibind, but done through the station as
registrar instead of a manually retyped phrase. The vehicle itself is unaffected and cannot tell how many TX
hold its key. This is precisely the shared-secret model's known hazard (ELRS Discussion #2652): the link
layer has no built-in mutex between two live holders of the same key, so the **station's application layer,
not the link**, must arbitrate a single active controller per vehicle at a time — worth calling out
explicitly in any implementation, not left implicit.

**Ceremony — moving to a new Wi-Fi (or a new carrier).** Because identity is not the network address,
moving networks needs no re-pairing at the identity layer, only re-discovery of the address on the new
network — exactly **Improv Wi-Fi's** job. Improv fits here specifically: the station's pairing/setup UI can
speak Improv-over-serial (or BLE) to hand a rover fresh SSID+password the first time it's plugged in over
USB and again whenever the Wi-Fi changes, without the project inventing its own bespoke Wi-Fi-config
protocol. Improv provisions *network* credentials only — it is not a substitute for the bind-secret
ceremony above, exactly as ESPHome layers its own API-encryption-key adoption on top of, not instead of,
Improv-provisioned Wi-Fi. Moving to a different carrier altogether (Wi-Fi to nRF24) reuses the same
announce-and-discover flow as first pairing, but skips secret generation — the vehicle already has one, it
just needs the station to observe it "here" on the new carrier. In every case, the *old* link on the old
network simply times out through the vehicle's ordinary loss-of-link failsafe; there is no special-cased
teardown, because there is no address-is-identity state to invalidate.

## 9. Do not copy

- **Plain SiK's bare NETID (default 25, no per-device secret)** — fine for a single cooperative telemetry
  hop, wrong for a fleet: documented cross-talk even across *different* NETIDs means a popular default
  value is not a boundary at all, and there is no notion of "this telemetry belongs to vehicle X."
- **Raw ESP-NOW hardcoded peer MAC addresses** — ties identity to a specific piece of hardware, which is
  exactly the "lost my ESP32, now what" pain point driving this research. Use MAC only as ephemeral
  transport info discovered per-session, never as the persisted identity.
- **Unscoped multi-TX-wins-by-whoever's-loudest** (plain ELRS/Crossfire with no Model-Match-equivalent) for
  anything that can move a vehicle. Fine for a single hobbyist's own buddy-box; dangerous once "any
  transmitter with the key" could include a second operator's still-powered-on TX. Always add an explicit,
  application-layer single-active-controller check.
- **DJI/Autel-style exclusive single-bond-per-vehicle with a destructive relink** — wrong whenever more than
  one legitimate holder (a second crew station, an always-on logger) needs standing access at once. Matter's
  multi-fabric model is the right shape; the consumer-drone one is not.
- **Password-in-the-clear-over-open-Wi-Fi as the whole trust model** (Tello/Parrot's "whoever knows today's
  SSID"). No persisted identity, no ownership, no revocation, no roaming — acceptable for a disposable toy,
  not for a fleet asset.
- **Zigbee's pre-3.0 fallback to a well-known global Trust Center link key.** If a zero-config demo path
  ever ships a default bind phrase, treat it exactly like `"ZigBeeAlliance09"` — force rotation before the
  device leaves pairing mode, never let a shared default become the shipped, permanent secret.
- **Conflating the identity ceremony with the network ceremony** (plain Crossfire binding conflates "which
  RF parameters" with "which operator owns this," which is why Multibind had to be retrofitted). Keep the
  bind-secret ceremony and the Improv-style network ceremony strictly separate concerns from the start, the
  way Matter and ESPHome do, rather than layering one onto the other as an afterthought.
