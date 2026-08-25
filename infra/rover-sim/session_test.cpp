// Replays the failure the real rover logged on 2026-08-23: drive, stop, re-arm.
//
// The stream watchdog must measure against a control session that is OPEN. Once
// a session ends -- deliberately (3x RELEASE) or by stalling -- a later ARM has
// no stream to be late against, so it must hold. Before the session-lifecycle
// fix the link kept comparing to the ended stream and the vehicle oscillated
// "failsafe cleared" / "FAILSAFE" every loop tick, exactly as the device log
// shows at t=254519.
#include <stdarg.h>
#include <stdio.h>

#include <vector>

#include "Config.h"
#include "ILogger.h"
#include "IImu.h"
#include "IMotorDriver.h"
#include "INetworkLink.h"
#include "MavlinkUdpLink.h"
#include "ParameterStore.h"
#include "VehicleController.h"

uint32_t g_hostMillis = 0;
WiFiUDP* g_lastSocket = nullptr;

namespace {

class QuietLogger : public ILogger {
public:
  bool enabled(LogLevel level) const override { return level <= LogLevel::Warn; }
  void write(LogLevel, const char* tag, const char* format, va_list args) override {
    fprintf(stderr, "    [%s] ", tag); vfprintf(stderr, format, args); fputc('\n', stderr);
  }
};
class FakeNetwork : public INetworkLink {
public:
  void begin() override {} void poll(uint32_t) override {}
  bool connected() const override { return true; }
  int8_t rssiDbm() const override { return -60; }
};

class FakeMotors : public IMotorDriver {
public:
  void begin() override {}
  void apply(float t, float s, uint32_t) override { throttle_ = t; steering_ = s; }
  void stop() override { throttle_ = 0; steering_ = 0; }
  float appliedThrottle() const override { return throttle_; }
  float appliedSteering() const override { return steering_; }
  float estimatedSpeedMps() const override { return 0; }
private:
  float throttle_ = 0, steering_ = 0;
};
class FakeImu : public IImu {
public:
  bool begin() override { return false; }
  void update(uint32_t) override {}
  bool present() const override { return false; }
  ImuSample read() const override { return ImuSample::invalid(); }
  void calibrate() override {}
};

std::vector<uint8_t> g_rc, g_arm;
int  g_failures = 0;

void check(const char* what, bool ok) {
  printf("%-58s %s\n", what, ok ? "ok" : "FAIL");
  if (!ok) ++g_failures;
}

}  // namespace

std::vector<uint8_t> g_ch5hi, g_ch5lo, g_ch5mid;

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: %s <fixture-dir>\n", argv[0]); return 2; }
  auto load = [&](const char* name) {
    char path[512]; snprintf(path, sizeof(path), "%s/%s.bin", argv[1], name);
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "missing fixture %s\n", path); exit(2); }
    std::vector<uint8_t> v; int c;
    while ((c = fgetc(f)) != EOF) v.push_back(static_cast<uint8_t>(c));
    fclose(f); return v;
  };
  g_rc = load("rc"); g_arm = load("arm");
  g_ch5hi = load("ch5hi"); g_ch5lo = load("ch5lo"); g_ch5mid = load("ch5mid");

  // The store owns the live config; the link binds to it, not to appConfig().
  ParameterStore   parameters;
  const AppConfig& cfg = parameters.config();
  QuietLogger logger; FakeNetwork network; FakeMotors motors; FakeImu imu;
  MavlinkUdpLink link(parameters, network, logger);
  VehicleController vehicle(link, motors, imu, cfg.timing, logger);
  vehicle.begin();

  g_hostMillis = 100000;
  link.begin();
  WiFiUDP* sock = g_lastSocket;

  auto tick = [&](uint32_t ms) { g_hostMillis += ms; link.poll(g_hostMillis); vehicle.loop(g_hostMillis); };
  auto feed = [&](const std::vector<uint8_t>& f) { sock->feed(f); };

  // --- 1. arm from cold, with no stream ever seen (the fix already shipped)
  feed(g_arm); tick(10);
  for (int i = 0; i < 300; ++i) tick(20);          // 6 s of silence
  check("cold ARM survives 6s with no RC stream", vehicle.armed());

  // --- 2. a real drive session: 3 s of RC frames at 50 Hz
  for (int i = 0; i < 150; ++i) { feed(g_rc); tick(20); }
  check("driving: armed and out of failsafe", vehicle.armed());

  // --- 3. the stream stops dead (no RELEASE burst) -- the watchdog must cut it
  for (int i = 0; i < 50; ++i) tick(20);           // 1 s past the 500 ms timeout
  check("stalled stream cuts the arm", !vehicle.armed());

  // --- 4. the regression: ARM again, long after that session ended
  feed(g_arm); tick(10);
  bool held = true;
  for (int i = 0; i < 300; ++i) { tick(20); if (!vehicle.armed()) held = false; }
  check("re-ARM after a finished session holds for 6s", held);

  // --- 5. and the stream still re-arms the watchdog when it comes back
  for (int i = 0; i < 50; ++i) { feed(g_rc); tick(20); }
  for (int i = 0; i < 50; ++i) tick(20);
  check("a resumed stream re-arms the watchdog", !vehicle.armed());

  // ================= ch5 arm switch =================
  printf("\n-- ch5 arm switch --\n");
  MavlinkUdpLink link2(parameters, network, logger);
  VehicleController vehicle2(link2, motors, imu, cfg.timing, logger);
  g_hostMillis = 100000;
  link2.begin();
  WiFiUDP* sock2 = g_lastSocket;
  auto tick2 = [&](uint32_t ms) { g_hostMillis += ms; link2.poll(g_hostMillis); vehicle2.loop(g_hostMillis); };
  auto feed2 = [&](const std::vector<uint8_t>& f) { sock2->feed(f); };
  vehicle2.begin();

  // Powered up with the switch ALREADY high: must not arm until it is cycled low.
  for (int i = 0; i < 10; ++i) { feed2(g_ch5hi); tick2(20); }
  check("ch5 high at power-on does NOT arm (interlock)", !vehicle2.armed());

  for (int i = 0; i < 3; ++i) { feed2(g_ch5lo); tick2(20); }
  for (int i = 0; i < 3; ++i) { feed2(g_ch5hi); tick2(20); }
  check("ch5 low then high arms", vehicle2.armed());

  // Mid-band is dead space: neither edge, so nothing changes.
  for (int i = 0; i < 5; ++i) { feed2(g_ch5mid); tick2(20); }
  check("ch5 mid-band leaves the arm state alone", vehicle2.armed());

  // Drive, then flip the switch down.
  for (int i = 0; i < 25; ++i) { feed2(g_rc); tick2(20); }
  const bool drove = motors.appliedThrottle() > 0.1f;
  feed2(g_ch5lo); tick2(20);
  check("driving before the disarm", drove);
  check("ch5 low disarms", !vehicle2.armed());
  check("disarm cuts the outputs", motors.appliedThrottle() == 0.0f);

  // The safety property: re-arming with NO fresh stick data must not resurrect
  // the demand the vehicle was carrying when it was disarmed.
  feed2(g_ch5hi); tick2(20); tick2(20);
  check("re-arm without fresh sticks does not resurrect demand",
        vehicle2.armed() && motors.appliedThrottle() == 0.0f);

  printf("\n%s\n", g_failures ? "SESSION CHECKS FAILED" : "ALL SESSION CHECKS PASSED");
  return g_failures ? 1 : 0;
}
