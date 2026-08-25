// Host harness for the COMMAND surface: everything the vision application can
// ask this rover to do, driven through the real MavlinkUdpLink and the real
// ParameterStore, with pymavlink on both ends.
//
// wire_test covers the telemetry the rover pushes unsolicited. This covers the
// other direction -- the parameter protocol, MAV_CMD_REQUEST_MESSAGE,
// MAV_CMD_SET_MESSAGE_INTERVAL, MAV_CMD_DO_AUX_FUNCTION and the mode table --
// and the rule that binds them: every command gets an answer. An unacked
// command is retried and then reported as an unresponsive aircraft, which is a
// worse diagnosis for the operator than an honest MAV_RESULT_UNSUPPORTED.
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

class StderrLogger : public ILogger {
public:
  bool enabled(LogLevel level) const override { return level <= LogLevel::Info; }
  void write(LogLevel, const char* tag, const char* format, va_list args) override {
    fprintf(stderr, "    [%s] ", tag);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
  }
};

class FakeNetwork : public INetworkLink {
public:
  void   begin() override {}
  void   poll(uint32_t) override {}
  bool   connected() const override { return true; }
  int8_t rssiDbm() const override { return -60; }
};

std::vector<uint8_t> readFile(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                              std::istreambuf_iterator<char>());
}

int failures = 0;
void check(bool ok, const char* what) {
  printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
  if (!ok) failures++;
}

/// PARAM_REQUEST_LIST answers a couple per poll and STATUSTEXT drains one per
/// poll, so a single poll would capture a fraction of either.
void pump(MavlinkUdpLink& link, int polls) {
  for (int i = 0; i < polls; i++) {
    g_hostMillis += 20;
    link.poll(g_hostMillis);
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: %s <build-dir>\n", argv[0]); return 2; }
  const std::string dir = argv[1];

  ParameterStore parameters;
  StderrLogger   logger;
  FakeNetwork    network;
  MavlinkUdpLink link(parameters, network, logger);

  g_hostMillis = 100000;
  if (!link.begin()) { fprintf(stderr, "begin() failed\n"); return 1; }

  printf("-- parameter table --\n");
  check(parameters.count() > 0, "the store exposes a non-empty parameter table");
  const ParameterEntry* accel = parameters.byName("RVR_ACCEL");
  check(accel != nullptr, "RVR_ACCEL is addressable by name");

  // --- phase A: a cold link, with no telemetry snapshot yet ----------------
  g_lastSocket->feed(readFile(dir + "/cmd_a.bin"));
  pump(link, 12);

  printf("\n-- writes reached the live configuration --\n");
  // The point of the ParameterStore: a write is not stored beside the config,
  // it IS the config, so the modules holding const references see it.
  check(parameters.config().drive.maxThrottle == 0.5f,
        "PARAM_SET RVR_MAX_THR=0.5 reached AppConfig.drive.maxThrottle");
  check(parameters.config().drive.reversalCoastMs == 300,
        "PARAM_SET RVR_REV_COAST=300 reached the motor driver's coast");
  check(parameters.config().drive.maxSteering == 1.0f,
        "PARAM_SET RVR_MAX_STR=5.0 clamped to the entry's maximum of 1.0");
  check(parameters.config().link.systemId == 1,
        "PARAM_SET on the read-only MAV_SYSID left it alone");

  printf("\n-- stream rates --\n");
  check(parameters.streamPeriod(30) == 200,
        "SET_MESSAGE_INTERVAL(ATTITUDE, 200000us) set the period to 200ms");
  check(parameters.streamPeriod(999) == 0, "an unknown message id has no period");

  printf("\n-- mode table --\n");
  check(link.customMode() == 4,
        "DO_SET_MODE(HOLD) was honoured and RTL did not overwrite it");

  // --- phase B: the same requests once telemetry exists --------------------
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

  g_lastSocket->feed(readFile(dir + "/cmd_b.bin"));
  // Enough polls to drain the whole parameter table at PARAMS_PER_POLL.
  pump(link, static_cast<int>(parameters.count()) + 8);

  std::ofstream out(dir + "/cmd_frames.bin", std::ios::binary);
  size_t total = 0;
  for (const std::vector<uint8_t>& datagram : g_lastSocket->sent) {
    out.write(reinterpret_cast<const char*>(datagram.data()), datagram.size());
    total += datagram.size();
  }
  out.close();
  printf("\nTX_DATAGRAMS count=%zu bytes=%zu params=%u\n",
         g_lastSocket->sent.size(), total, parameters.count());

  if (failures) printf("\ncommand_test: %d FAILED\n", failures);
  return failures ? 1 : 0;
}
