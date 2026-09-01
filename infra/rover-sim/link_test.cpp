// Host harness for the LINK layer itself: where replies are addressed, and what
// happens when they cannot be delivered.
//
// The other suites all assume a healthy socket and check what goes onto it.
// This one checks the two things that decide whether anything reaches the
// ground station at all:
//
//   1. the reply address. A vehicle that only ever transmits to a compile-time
//      IP goes permanently mute the moment the ground station's DHCP lease
//      moves -- and nothing notices, because inbound traffic keeps arriving.
//   2. the response to a transmit path that has stopped working. An ESP32 can
//      be associated, report a healthy RSSI, and still not put one datagram on
//      the wire; logging that forever is not a recovery.
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include <fstream>
#include <string>
#include <vector>

#include "Config.h"
#include "ILogger.h"
#include "INetworkLink.h"
#include "MavlinkUdpLink.h"
#include "ParameterStore.h"

uint32_t g_hostMillis = 0;
WiFiUDP* g_lastSocket = nullptr;

namespace {

/// Quiet by default: this suite deliberately provokes a flood of transmit
/// errors, and the point is the recovery, not the noise.
class QuietLogger : public ILogger {
public:
  bool verbose = false;
  bool enabled(LogLevel level) const override { return verbose && level <= LogLevel::Info; }
  void write(LogLevel, const char* tag, const char* format, va_list args) override {
    fprintf(stderr, "    [%s] ", tag);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
  }
};

class CountingNetwork : public INetworkLink {
public:
  int reconnects = 0;
  void   begin() override {}
  void   poll(uint32_t) override {}
  bool   connected() const override { return true; }
  int8_t rssiDbm() const override { return -60; }
  void   reconnect() override { reconnects++; }
  const char* localAddress() const override { return "192.168.0.101"; }
};

std::vector<uint8_t> readFile(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                              std::istreambuf_iterator<char>());
}

int failures = 0;
void check(bool ok, const std::string& what) {
  printf("  %s %s\n", ok ? "ok  " : "FAIL", what.c_str());
  if (!ok) failures++;
}

/// One turn of the sketch's own loop: poll the link, then offer it a telemetry
/// snapshot. Both halves matter here -- poll() drains replies, publishTelemetry()
/// is what puts unsolicited frames on the wire, and this suite is about where
/// those frames are addressed.
void pump(MavlinkUdpLink& link, int polls) {
  for (int i = 0; i < polls; i++) {
    g_hostMillis += 20;
    link.poll(g_hostMillis);

    VehicleTelemetry t;
    t.armed           = false;
    t.failsafeActive  = false;
    t.appliedThrottle = 0.0f;
    t.appliedSteering = 0.0f;
    t.groundSpeedMps  = 0.0f;
    t.imu             = ImuSample::invalid();
    t.linkRssiDbm     = -60;
    t.uptimeMs        = g_hostMillis;
    link.publishTelemetry(t, g_hostMillis);
  }
}

/// 192.168.0.N as a sockaddr-order word, matching the shim's IPAddress.
uint32_t lanAddress(uint8_t host) {
  return static_cast<uint32_t>(192) | (168u << 8) | (0u << 16) |
         (static_cast<uint32_t>(host) << 24);
}

/// True when `datagram` is a STATUSTEXT frame; writes its severity if given.
/// Message id lives at byte 7 of a v2 frame (one byte suffices -- every id
/// this firmware sends fits below 255, see MavlinkV2Codec.cpp's OFF_*
/// offsets); the payload, and so the severity, starts right after the
/// 10-byte header (mavlink::HEADER_LEN_V2).
bool isStatusText(const std::vector<uint8_t>& datagram, uint8_t* severityOut = nullptr) {
  if (datagram.size() < 11 || datagram[7] != static_cast<uint8_t>(mavlink::msg::STATUSTEXT)) {
    return false;
  }
  if (severityOut != nullptr) *severityOut = datagram[10];
  return true;
}

/// Every distinct "ip:port" this socket has transmitted to.
std::string targets(const WiFiUDP& socket) {
  std::string out;
  for (const std::string& t : socket.sentTo) {
    if (out.find(t) == std::string::npos) {
      if (!out.empty()) out += ", ";
      out += t;
    }
  }
  return out.empty() ? "(nothing sent)" : out;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: %s <build-dir>\n", argv[0]); return 2; }
  const std::string dir = argv[1];
  const std::vector<uint8_t> commands = readFile(dir + "/cmd_a.bin");
  if (commands.empty()) { fprintf(stderr, "cmd_a.bin missing -- run command_fixture.py\n"); return 2; }

  printf("-- before the app has answered, the configured host is all we have --\n");
  {
    ParameterStore  parameters;
    QuietLogger     logger;
    CountingNetwork network;
    MavlinkUdpLink  link(parameters, network, logger);
    WiFiUDP* const  socket = g_lastSocket;

    g_hostMillis = 100000;
    link.begin();
    pump(link, 60);   // unsolicited telemetry only; nobody has spoken to us

    check(!socket->sent.empty(), "the rover transmits first, unprompted");
    check(targets(*socket) == "192.168.0.104:14550",
          "...to the configured peerHost (got " + targets(*socket) + ")");
  }

  printf("\n-- once it answers, replies follow the path the command arrived on --\n");
  {
    ParameterStore  parameters;
    QuietLogger     logger;
    CountingNetwork network;
    MavlinkUdpLink  link(parameters, network, logger);
    WiFiUDP* const  socket = g_lastSocket;

    g_hostMillis = 100000;
    link.begin();
    pump(link, 20);
    socket->sentTo.clear();
    socket->sent.clear();

    // The app is NOT at the configured address -- this is the lease-moved case.
    socket->feedFrom(commands, lanAddress(77), 14550);
    pump(link, 40);

    check(targets(*socket) == "192.168.0.77:14550",
          "every reply went to the sender, not to the compile-time constant (got " +
              targets(*socket) + ")");

    // ...and it moves again, as a lease renewal on the app's side would.
    socket->sentTo.clear();
    socket->feedFrom(commands, lanAddress(88), 14555);
    pump(link, 40);
    check(targets(*socket) == "192.168.0.88:14555",
          "a peer that moves is followed (got " + targets(*socket) + ")");

    // A datagram whose sender we cannot see must not reset what we learned.
    socket->sentTo.clear();
    socket->feed(commands);
    pump(link, 40);
    check(targets(*socket) == "192.168.0.88:14555",
          "an unattributable datagram does not unlearn the peer (got " +
              targets(*socket) + ")");
  }

  printf("\n-- a second transmitter cannot steal command authority --\n");
  {
    // R4-firmware-audit finding 4's cheap half: once a GCS peer is learned,
    // a COMMAND frame from a DIFFERENT source is refused, closing "any
    // second device on the LAN claiming sysid 255 can drive the rover"
    // without touching the protocol. Reply/telemetry re-learning (above)
    // is deliberately untouched -- that half of the vulnerability is out of
    // scope for this wave (D4 row 5).
    ParameterStore  parameters;
    QuietLogger     logger;
    CountingNetwork network;
    MavlinkUdpLink  link(parameters, network, logger);
    WiFiUDP* const  socket = g_lastSocket;

    const std::vector<uint8_t> arm  = readFile(dir + "/cmd_idem_arm0.bin");   // COMPONENT_ARM_DISARM
    const std::vector<uint8_t> hold = readFile(dir + "/cmd_idem_mode0.bin");  // DO_SET_MODE -> HOLD (disarms)
    if (arm.empty() || hold.empty()) {
      fprintf(stderr, "cmd_idem_arm0.bin/cmd_idem_mode0.bin missing -- run command_fixture.py\n");
      return 2;
    }

    g_hostMillis = 100000;
    link.begin();

    // Source A (.10) is the first ever command sender: nothing to compare
    // it against yet, so it is accepted outright and becomes the command
    // source of record.
    socket->feedFrom(arm, lanAddress(10), 14550);
    pump(link, 1);
    ControlCommand cmd;
    check(link.takeCommand(cmd) && cmd.armed,
          "the first command source is accepted and arms the rover");

    // Source B (.20), a different address, tries to switch to HOLD --
    // moments later, well inside the re-learn window. It must be silently
    // ignored: no state change, no ACK, just a throttled log line.
    socket->sentTo.clear();
    socket->feedFrom(hold, lanAddress(20), 14550);
    pump(link, 1);
    check(!link.takeCommand(cmd),
          "a second transmitter's command is ignored while the first is still live");
    check(link.customMode() == mavlink::rover_mode::MANUAL,
          "...and never reaches the mode table");

    // The reply/telemetry address still follows whoever spoke last, exactly
    // as before this wave -- learnPeer() is unconditional and unchanged.
    // Nothing was due to transmit on the single tick above (every telemetry
    // stream is rate-limited well past 20ms), so pump a few more ticks --
    // still well inside the re-learn window -- until ATTITUDE (the fastest
    // stream, 100ms) has something to send, then look at where it went.
    pump(link, 5);
    check(targets(*socket) == "192.168.0.20:14550",
          "telemetry keeps following the last sender even though its command "
          "was refused (got " + targets(*socket) + ")");

    // Now let source A go silent for the same window the stall failsafe
    // already uses (timing_.commandTimeoutMs, 500ms by default) -- long
    // enough that a real DHCP move would have reassociated by now too.
    // (20ms already elapsed before the rejected attempt above, plus the 100ms
    // just spent settling telemetry, so 20 more ticks clears the 500ms mark.)
    pump(link, 20);

    // Source B tries again: the incumbent has been quiet long enough, so
    // this time the takeover is allowed.
    socket->sentTo.clear();
    socket->feedFrom(hold, lanAddress(20), 14550);
    pump(link, 1);
    check(link.takeCommand(cmd) && !cmd.armed,
          "once the incumbent has been silent past the re-learn window, a "
          "new source is accepted");
    check(link.customMode() == mavlink::rover_mode::HOLD,
          "...and its command actually lands");
  }

  printf("\n-- a transmit path that stops working is recovered, not narrated --\n");
  {
    ParameterStore  parameters;
    QuietLogger     logger;
    CountingNetwork network;
    MavlinkUdpLink  link(parameters, network, logger);
    WiFiUDP* const  socket = g_lastSocket;

    g_hostMillis = 100000;
    link.begin();
    socket->stopped = 0;

    // A handful of failures is a lossy link, not a broken one: nothing yet.
    socket->failEndPacketFor = 5;
    pump(link, 40);
    check(socket->stopped == 0 && network.reconnects == 0,
          "a short run of failures is tolerated without tearing anything down");

    // A sustained run is the wedged case. First the socket, then the radio.
    socket->failEndPacketFor = 100000;
    pump(link, 400);
    check(socket->stopped > 0, "the socket was rebound after a sustained failure run");
    check(network.reconnects == 1,
          "the radio was re-associated exactly once, not on every failure "
          "(got " + std::to_string(network.reconnects) + ")");

    // And the escalation is not a loop: recovery has to actually recover.
    socket->failEndPacketFor = 0;
    const int reconnectsBefore = network.reconnects;
    pump(link, 200);
    check(network.reconnects == reconnectsBefore,
          "once transmits succeed again the escalation stops");
    check(!socket->sent.empty(), "and traffic resumes");

    // R4-firmware-audit §5's observability gap: both escalations above used
    // to be Serial-only, which tells an untethered operator nothing. Both
    // are now queued as STATUSTEXT too (drained here, now that transmits
    // succeed again) so the ground station sees "the link looks fine but
    // nothing is getting through" without a USB cable attached.
    bool sawRebindWarning = false;
    bool sawReconnectError = false;
    for (const std::vector<uint8_t>& datagram : socket->sent) {
      uint8_t severity = 0;
      if (!isStatusText(datagram, &severity)) continue;
      if (severity == mavlink::severity::WARNING) sawRebindWarning = true;
      if (severity == mavlink::severity::ERROR)   sawReconnectError = true;
    }
    check(sawRebindWarning,
          "the socket-rebind escalation reached the operator as a STATUSTEXT, not just Serial");
    check(sawReconnectError,
          "the re-associate escalation reached the operator as a STATUSTEXT, not just Serial");
  }

  printf("\n%s\n", failures == 0 ? "ALL LINK CHECKS PASSED"
                                 : (std::to_string(failures) + " FAILED").c_str());
  return failures == 0 ? 0 : 1;
}
