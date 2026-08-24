// The ESP32 rover firmware, running on this machine against a real vision
// instance. Everything below the motor pins is the unmodified firmware: the
// same MavlinkUdpLink, the same MavlinkV2Codec, the same VehicleController.
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

#include "Config.h"
#include "ILogger.h"
#include "IMotorDriver.h"
#include "INetworkLink.h"
#include "MavlinkUdpLink.h"
#include "NullImu.h"
#include "VehicleController.h"

uint32_t g_hostMillis = 0;

namespace {

volatile sig_atomic_t g_stop = 0;
void onSignal(int) { g_stop = 1; }

uint32_t nowMillis() {
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<uint32_t>(ts.tv_sec * 1000ULL + ts.tv_nsec / 1000000ULL);
}

class StderrLogger : public ILogger {
public:
  bool enabled(LogLevel level) const override { return level <= level_; }
  void write(LogLevel, const char* tag, const char* format, va_list args) override {
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
    fflush(stderr);
  }
  LogLevel level_ = LogLevel::Info;
};

/// Stands in for the H-bridge: records the demand, spins no wheels.
class BenchMotorDriver : public IMotorDriver {
public:
  explicit BenchMotorDriver(const DriveLimits& limits) : limits_(limits) {}
  void begin() override {}
  void apply(float throttle, float steering, uint32_t) override {
    throttle_ = throttle; steering_ = steering;
  }
  void stop() override { throttle_ = 0.0f; steering_ = 0.0f; }
  float appliedThrottle() const override { return throttle_; }
  float appliedSteering() const override { return steering_; }
  float estimatedSpeedMps() const override {
    return (throttle_ < 0 ? -throttle_ : throttle_) * limits_.topSpeedMps;
  }
private:
  const DriveLimits& limits_;
  float throttle_ = 0.0f, steering_ = 0.0f;
};

class WiredNetwork : public INetworkLink {
public:
  void   begin() override {}
  void   poll(uint32_t) override {}
  bool   connected() const override { return true; }
  int8_t rssiDbm() const override { return -55; }
};

}  // namespace

int main(int argc, char** argv) {
  const char* host = argc > 1 ? argv[1] : "127.0.0.1";
  const uint16_t localPort = argc > 2 ? static_cast<uint16_t>(atoi(argv[2])) : 14551;

  signal(SIGINT, onSignal);
  signal(SIGTERM, onSignal);

  const AppConfig& cfg = appConfig();
  LinkConfig link = cfg.link;      // must outlive the adapter; held by reference
  link.peerHost  = host;
  link.localPort = localPort;

  StderrLogger      logger;
  WiredNetwork      network;
  BenchMotorDriver  motors(cfg.drive);
  NullImu           imu;
  MavlinkUdpLink    commandLink(link, cfg.rc, cfg.telemetry, cfg.timing, network, logger);
  VehicleController controller(commandLink, motors, imu, cfg.timing, logger);

  g_hostMillis = nowMillis();
  controller.begin();

  uint32_t lastReport = 0;
  while (!g_stop) {
    g_hostMillis = nowMillis();
    controller.loop(g_hostMillis);

    if (g_hostMillis - lastReport >= 2000) {
      lastReport = g_hostMillis;
      fprintf(stdout, "state=%d throttle=%+.2f steering=%+.2f\n",
              static_cast<int>(commandLink.state()),
              motors.appliedThrottle(), motors.appliedSteering());
      fflush(stdout);
    }
    usleep(2000);   // ~500 Hz poll; the link owns the real stream rates
  }
  fprintf(stderr, "rover stopped\n");
  return 0;
}
