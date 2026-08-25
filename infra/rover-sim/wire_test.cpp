// Host harness: runs the REAL firmware link + codec against pymavlink.
//
// Compiling proves nothing about the wire. This drives MavlinkUdpLink through
// a fake socket so the exact bytes it would transmit can be handed to the
// reference implementation, and the bytes the reference produces can be handed
// back to the parser.
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include <fstream>
#include <vector>

#include "Config.h"
#include "ILogger.h"
#include "INetworkLink.h"
#include "MavlinkUdpLink.h"
#include "ParameterStore.h"

uint32_t g_hostMillis = 0;
WiFiUDP* g_lastSocket = nullptr;

namespace {

class StderrLogger : public ILogger {
public:
  bool enabled(LogLevel level) const override { return level <= LogLevel::Info; }
  void write(LogLevel, const char* tag, const char* format, va_list args) override {
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
  }
};

class FakeNetwork : public INetworkLink {
public:
  void   begin() override {}
  void   poll(uint32_t) override {}
  bool   connected() const override { return true; }
  int8_t rssiDbm() const override { return -60; }   // mid-scale -> rssi 203
};

std::vector<uint8_t> readFile(const char* path) {
  std::ifstream in(path, std::ios::binary);
  return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                              std::istreambuf_iterator<char>());
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) { fprintf(stderr, "usage: %s <rx_fixture.bin> <tx_frames.bin>\n", argv[0]); return 2; }

  // The store owns the live config; the link binds to it, not to appConfig().
  ParameterStore parameters;
  StderrLogger logger;
  FakeNetwork  network;
  MavlinkUdpLink link(parameters, network, logger);

  g_hostMillis = 100000;
  if (!link.begin()) { fprintf(stderr, "begin() failed\n"); return 1; }

  // --- RX: feed frames pymavlink built, and see what the firmware makes of them
  g_lastSocket->feed(readFile(argv[1]));
  link.poll(g_hostMillis);

  ControlCommand command;
  const bool got = link.takeCommand(command);
  printf("RX_COMMAND got=%d throttle=%.4f steering=%.4f armed=%d\n",
         got ? 1 : 0, command.throttle, command.steering, command.armed ? 1 : 0);
  printf("RX_STATE state=%d customMode=%lu\n",
         static_cast<int>(link.state()), static_cast<unsigned long>(link.customMode()));

  // --- TX: one publish tick, with every stream due
  VehicleTelemetry t;
  t.armed           = true;
  t.failsafeActive  = false;
  t.appliedThrottle = 0.50f;
  t.appliedSteering = -0.25f;
  t.groundSpeedMps  = 0.75f;
  t.imu             = ImuSample::invalid();
  t.linkRssiDbm     = 0;
  t.uptimeMs        = g_hostMillis;
  link.publishTelemetry(t, g_hostMillis);

  std::ofstream out(argv[2], std::ios::binary);
  size_t total = 0;
  for (const std::vector<uint8_t>& datagram : g_lastSocket->sent) {
    out.write(reinterpret_cast<const char*>(datagram.data()), datagram.size());
    total += datagram.size();
  }
  printf("TX_DATAGRAMS count=%zu bytes=%zu\n", g_lastSocket->sent.size(), total);
  return 0;
}
