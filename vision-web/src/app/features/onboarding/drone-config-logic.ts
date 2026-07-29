import type { DiscoveredDevice } from '../../core/api/models';
import { vehicleSysid } from './drone-scan-logic';

/**
 * Pure logic behind the onboarding wizard's "Add a real drone" Connect method
 * (docs/DRONE-INFRA-PLAN.md I-g, wave B) — the guided firmware×link picker, the parameterized
 * copy-paste config snippets, and the device shape the eventual create call embeds. Split out per
 * this app's own convention (`onboarding-logic.ts`'s doc comment): Angular-free, unit-tested
 * without HTTP, the router, or a component.
 *
 * Content here is deliberately adapted, never contradicted, from `infra/edge/elrs-backpack.md`,
 * `infra/edge/esp32-bridge.md`, `infra/edge/companion-rpi.md`, and
 * `infra/edge/mavlink-router/main.conf` — every version floor/limitation/setting quoted below
 * traces back to one of those four files.
 *
 * `OnboardingStore` (the wizard's own "component store") is the only caller — it owns the HTTP call
 * to `GET /api/system/network` and the picker/config signals, everything else (which combos are
 * viable, what the copy-paste content says, what the created device looks like) lives here.
 */

/** The three firmwares the wizard's picker offers — mirrors `infra/edge/*.md`'s own scope. */
export type Firmware = 'ardupilot' | 'inav' | 'betaflight';

/** The three link recipes `infra/edge/` documents (docs/DRONE-INFRA-PLAN.md I-d). */
export type LinkType = 'elrs' | 'esp32' | 'companion';

export const FIRMWARES: readonly Firmware[] = ['ardupilot', 'inav', 'betaflight'];
export const LINKS: readonly LinkType[] = ['elrs', 'esp32', 'companion'];

export const FIRMWARE_LABELS: Record<Firmware, string> = {
  ardupilot: 'ArduPilot',
  inav: 'INAV',
  betaflight: 'Betaflight',
};

export const LINK_LABELS: Record<LinkType, string> = {
  elrs: 'ELRS backpack',
  esp32: 'ESP32/WiFi bridge',
  companion: 'Companion computer',
};

/** One line under each link button in the picker — `infra/edge/*.md`'s own opening sentence, condensed. */
export const LINK_HINTS: Record<LinkType, string> = {
  elrs: "Zero extra hardware — the TX module's own backpack WiFi radio. Telemetry only.",
  esp32: '~$5 ESP32/ESP8266 UART bridge (DroneBridge/mavesp8266). Telemetry only.',
  companion: 'RPi-class computer running mavlink-router — the only recipe that also carries video.',
};

/**
 * The picker's poka-yoke verdict for one firmware×link combination (docs/DRONE-INFRA-PLAN.md I-g:
 * "impossible/degraded combos are labeled honestly"). `supported` is `level !== 'no-go'` — kept as
 * its own field (rather than derived at every call site) since it's what the UI's disabled-state
 * check reads directly. `note` always explains *why*, never just *that*.
 */
export interface LinkCompatibility {
  readonly supported: boolean;
  readonly level: 'full' | 'monitor-only' | 'no-go';
  readonly note: string;
}

// --- The honest matrix (docs/DRONE-INFRA-PLAN.md I-g's own wording, sourced from infra/edge/*.md) -
// ArduPilot: full two-way MAVLink on every link (its own MAVLink implementation is complete).
// INAV: monitor-only on every link — `elrs-backpack.md`'s own words, "INAV's MAVLink implementation
// is transmit-only", is a firmware-wide limitation, not specific to the backpack link it happens to
// be documented against; the same UART-level MAVLink-out setting over ESP32/companion carries the
// identical one-way limitation.
// Betaflight: hard no-go below 2025.12.0-beta on every link, for the identical reason in reverse —
// `elrs-backpack.md`: "Earlier Betaflight has no MAVLink telemetry output at all" is a firmware
// capability gate, not a backpack-specific one.

const ARDUPILOT_NOTES: Record<LinkType, string> = {
  elrs:
    'Full two-way MAVLink over the ELRS backpack. Requires ExpressLRS ≥ 3.5.0 (TX + RX), TX ' +
    'Backpack firmware ≥ 1.5.0, and ArduPilot ≥ 4.5. ≈ 2.4 kB/s telemetry-only link — no video.',
  esp32:
    'Full two-way MAVLink over a ~$5 ESP32/ESP8266 UART bridge (DroneBridge for ESP32 or ' +
    'mavesp8266). Telemetry only — no video; AP-mode WiFi range is short (tens of meters), switch ' +
    'to STA mode for reach.',
  companion:
    'Full two-way MAVLink via mavlink-router. The only recipe that also carries video (camera → ' +
    'FFmpeg → RTSP → mediamtx) over the same LTE/WiFi link.',
};

const INAV_NOTES: Record<LinkType, string> = {
  elrs:
    "Monitor-only — INAV's MAVLink implementation is transmit-only, so no command/config/mission " +
    'traffic gets through this link. Requires INAV ≥ 8, ExpressLRS ≥ 3.5.0, and TX Backpack ' +
    'firmware ≥ 1.5.0.',
  esp32:
    "Monitor-only — INAV's MAVLink implementation is transmit-only, so no command/config/mission " +
    'traffic gets through, even over a wired bridge. Telemetry only — no video.',
  companion:
    "Monitor-only — INAV's MAVLink implementation is transmit-only, so no command/config/mission " +
    'traffic gets through mavlink-router. Video can still be wired up independently over the same ' +
    "companion computer's own RTSP path.",
};

const BETAFLIGHT_NOTES: Record<LinkType, string> = {
  elrs:
    'Hard no-go below Betaflight 2025.12.0-beta — earlier releases have no MAVLink telemetry ' +
    'output at all. Even once available, also requires ExpressLRS ≥ 3.5.0 and TX Backpack ' +
    'firmware ≥ 1.5.0.',
  esp32:
    'Hard no-go below Betaflight 2025.12.0-beta — earlier releases have no MAVLink telemetry ' +
    'output at all, regardless of which link carries it.',
  companion:
    'Hard no-go below Betaflight 2025.12.0-beta — earlier releases have no MAVLink telemetry ' +
    'output at all, so mavlink-router has nothing to forward.',
};

/**
 * The picker's per-cell verdict (docs/DRONE-INFRA-PLAN.md I-g's poka-yoke: "impossible combos are
 * labeled, never silently offered as if they'll work"). Firmware alone decides the `level` — every
 * link degrades identically for a given firmware, per the matrix note above — but the `note` text
 * is still per-link, since each recipe's own version floors/limitations differ.
 */
export function linkCompatibility(firmware: Firmware, link: LinkType): LinkCompatibility {
  if (firmware === 'betaflight') {
    return { supported: false, level: 'no-go', note: BETAFLIGHT_NOTES[link] };
  }
  if (firmware === 'inav') {
    return { supported: true, level: 'monitor-only', note: INAV_NOTES[link] };
  }
  return { supported: true, level: 'full', note: ARDUPILOT_NOTES[link] };
}

/**
 * One copy-paste block the "configure your drone" sub-step renders (docs/DRONE-INFRA-PLAN.md I-g).
 * `filename` present is what tells the UI to also offer a download button (client-side
 * `Blob` → `<a download>`) — only the companion path's `main.conf` carries one today.
 */
export interface ConfigBlock {
  readonly title: string;
  readonly language?: string;
  readonly body: string;
  readonly filename?: string;
}

const UART_LABEL: Record<LinkType, string> = {
  elrs: "the UART wired to the ELRS receiver's telemetry pin",
  esp32: 'the UART wired to the ESP32/ESP8266 bridge',
  companion: 'the UART wired to the companion computer',
};

/** ArduPilot's own `SERIALx_BAUD` parameter value (a lookup code, not the literal baud rate) per link. */
const ARDUPILOT_BAUD: Record<LinkType, { readonly code: string; readonly human: string }> = {
  elrs: { code: '460', human: '460800' },
  // mavesp8266/DroneBridge both default to 115200 (esp32-bridge.md).
  esp32: { code: '115', human: '115200' },
  // Matches infra/edge/mavlink-router/main.conf's own `[UartEndpoint fc] Baud = 57600` default.
  companion: { code: '57', human: '57600' },
};

function ardupilotFcBlock(link: LinkType): ConfigBlock {
  const baud = ARDUPILOT_BAUD[link];
  return {
    title: 'ArduPilot — flight controller serial settings',
    language: 'text',
    body:
      `SERIALx_PROTOCOL = 2      (MAVLink2, on ${UART_LABEL[link]})\n` +
      `SERIALx_BAUD     = ${baud.code}        (${baud.human} baud)`,
  };
}

function inavFcBlock(link: LinkType): ConfigBlock {
  return {
    title: 'INAV — flight controller serial settings',
    language: 'text',
    body:
      `Configurator → Ports tab: assign the MAVLink telemetry function to ${UART_LABEL[link]}\n` +
      '(CLI equivalent: serial <port> <mask> with FUNCTION_TELEMETRY_MAVLINK set)\n' +
      'feature TELEMETRY\n\n' +
      "Monitor-only — INAV's MAVLink implementation is transmit-only: telemetry flows out, but no " +
      'command/config/mission traffic will come back through it.',
  };
}

function betaflightFcBlock(link: LinkType): ConfigBlock {
  return {
    title: 'Betaflight — flight controller serial settings',
    language: 'text',
    body:
      `Ports tab: set ${UART_LABEL[link]}'s Telemetry Output to MAVLink\n` +
      'feature TELEMETRY\n\n' +
      'Requires Betaflight ≥ 2025.12.0-beta — earlier releases have no MAVLink telemetry output ' +
      'at all (hard no-go below that version).',
  };
}

function fcSettingsBlock(firmware: Firmware, link: LinkType): ConfigBlock {
  switch (firmware) {
    case 'ardupilot':
      return ardupilotFcBlock(link);
    case 'inav':
      return inavFcBlock(link);
    case 'betaflight':
      return betaflightFcBlock(link);
  }
}

function elrsTargetBlock(serverAddress: string, mavlinkPort: number): ConfigBlock {
  return {
    title: 'ELRS TX Backpack — Telemetry → WiFi target (STA mode)',
    language: 'text',
    body:
      `${serverAddress}:${mavlinkPort}\n\n` +
      'Backpack Lua script: Backpack → Telemetry → WiFi (not the general-purpose WiFi toggle). ' +
      'AP mode needs no target address — join the backpack’s own SSID and this platform listens ' +
      'on 0.0.0.0, port above, on its own.',
  };
}

function esp32TargetBlock(serverAddress: string, mavlinkPort: number): ConfigBlock {
  return {
    title: 'ESP32/ESP8266 bridge — UDP target (STA mode)',
    language: 'text',
    body:
      `udp://${serverAddress}:${mavlinkPort}\n\n` +
      "Bridge's own web UI (its AP gateway address, typically 192.168.4.1) or the DroneBridge " +
      'companion app. AP mode needs no target — join the bridge’s own SSID instead.',
  };
}

/**
 * The companion path's `main.conf` (docs/DRONE-INFRA-PLAN.md I-g: "a downloadable, pre-filled
 * mavlink-router main.conf"), adapted from `infra/edge/mavlink-router/main.conf` verbatim except
 * the `[UdpEndpoint platform]` `Address`/`Port` pair, now pre-filled with this platform's own real
 * values instead of the source template's `platform.example.internal`/`14550` placeholders. `Device`/
 * `Baud` stay `CHANGE-ME`-flagged — those depend on the companion's own wiring, which this wizard
 * has no way to know.
 */
function companionMainConfBlock(serverAddress: string, mavlinkPort: number): ConfigBlock {
  const body =
    '[General]\n' +
    'MavlinkDialect = ardupilotmega\n' +
    '\n' +
    '[UartEndpoint fc]\n' +
    "# CHANGE ME: the UART wired to the flight controller's telemetry port.\n" +
    '# /dev/ttyAMA0 is the RPi’s primary UART (GPIO14/15) when Bluetooth is\n' +
    '# disabled or moved off it -- /dev/ttyUSB0 if bridging over a USB-serial\n' +
    '# adapter instead (see infra/edge/companion-rpi.md "Wiring").\n' +
    'Device = /dev/ttyAMA0\n' +
    'Baud = 57600\n' +
    '\n' +
    '[UdpEndpoint platform]\n' +
    "# Pre-filled with this platform's own reachable address -- verify it's\n" +
    "# actually reachable from the companion computer's network. Mode=Normal\n" +
    '# means mavlink-router actively pushes here (a UDP *client*, matching this\n' +
    "# platform's listen-not-connect ingest model).\n" +
    'Mode = Normal\n' +
    `Address = ${serverAddress}\n` +
    `Port = ${mavlinkPort}\n`;
  return {
    title: 'mavlink-router — /etc/mavlink-router/main.conf',
    language: 'ini',
    filename: 'main.conf',
    body,
  };
}

function targetBlock(link: LinkType, serverAddress: string, mavlinkPort: number): ConfigBlock {
  switch (link) {
    case 'elrs':
      return elrsTargetBlock(serverAddress, mavlinkPort);
    case 'esp32':
      return esp32TargetBlock(serverAddress, mavlinkPort);
    case 'companion':
      return companionMainConfBlock(serverAddress, mavlinkPort);
  }
}

/**
 * The "configure your drone" sub-step's copy-paste blocks (docs/DRONE-INFRA-PLAN.md I-g) —
 * always exactly two: the flight controller's own serial/protocol settings (firmware-specific, no
 * address/port — nothing in this block depends on where this platform is reachable), then the
 * link's own target settings (address/port-parameterized for `elrs`/`esp32`; the full `main.conf`,
 * `Address`/`Port` pre-filled, for `companion`). `serverAddress`/`mavlinkPort` come from
 * `GET /api/system/network` (`OnboardingStore`) — this function never guesses or falls back to a
 * placeholder itself.
 */
export function configSnippets(
  firmware: Firmware,
  link: LinkType,
  serverAddress: string,
  mavlinkPort: number,
): readonly ConfigBlock[] {
  return [fcSettingsBlock(firmware, link), targetBlock(link, serverAddress, mavlinkPort)];
}

/** The device shape `buildDroneDeviceSpec` returns — a subset of `CreateAssetDeviceSpec`'s own fields. */
export interface DroneDeviceSpec {
  readonly protocol: 'mavlink';
  readonly uri: string;
  readonly options?: Record<string, string>;
}

/**
 * The device this wizard's eventual create call embeds for a heard vehicle (docs/DRONE-INFRA-PLAN.md
 * I-g step 3: "create-step prefills ... mavlink device udp://0.0.0.0:<port> + sysid option pinned to
 * the heard vehicle") — the pin that makes multi-drone-on-one-port ingest work (I-a): the URI is
 * always `udp://0.0.0.0:<mavlinkPort>`, built from this platform's own authoritative
 * `GET /api/system/network` port, never trusted from whatever the scan candidate happened to echo
 * back on its own `uri`/`address` fields (`vehicleSysid`, `drone-scan-logic.ts`, is still the one
 * place that reads the heard sysid out of a candidate's `details` map — reused here, not
 * reimplemented). `options` is omitted entirely when the scanner reported no sysid for this vehicle
 * (an unpinned device would otherwise silently steal "first unclaimed vehicle" on the socket —
 * see `adapters/adapter-mavlink/MODULE.md`'s own claim-precedence contract — so a caller that got
 * here without a sysid has bigger problems than this function can silently paper over).
 */
export function buildDroneDeviceSpec(vehicle: DiscoveredDevice, mavlinkPort: number): DroneDeviceSpec {
  const sysid = vehicleSysid(vehicle);
  return {
    protocol: 'mavlink',
    uri: `udp://0.0.0.0:${mavlinkPort}`,
    ...(sysid ? { options: { sysid } } : {}),
  };
}
