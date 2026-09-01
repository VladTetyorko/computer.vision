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
  }

  printf("\n%s\n", failures == 0 ? "ALL LINK CHECKS PASSED"
                                 : (std::to_string(failures) + " FAILED").c_str());
  return failures == 0 ? 0 : 1;
}
