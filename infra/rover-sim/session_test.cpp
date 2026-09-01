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
  // Defaults true so every existing call site is unaffected; only the F3
  // network-down case below ever flips it. This is the IP-layer carrier
  // itself going away (WiFi association lost), not merely a stalled RC
  // stream -- MavlinkUdpLink::poll() reads this before anything else.
  bool up = true;
  void begin() override {} void poll(uint32_t) override {}
  bool connected() const override { return up; }
  int8_t rssiDbm() const override { return up ? -60 : -100; }
};

class FakeMotors : public IMotorDriver {
public:
  // A timestamped demand, opt-in: only the F3 case below sets `recording`,
  // so every other section pays nothing and behaves exactly as before.
  struct Sample { uint32_t ms; float throttle; float steering; };
  std::vector<Sample>* recording = nullptr;

  void begin() override {}
  void apply(float t, float s, uint32_t) override {
    throttle_ = t; steering_ = s;
    if (recording) recording->push_back({g_hostMillis, t, s});
  }
  void stop() override {
    throttle_ = 0; steering_ = 0;
    if (recording) recording->push_back({g_hostMillis, 0.0f, 0.0f});
  }
  // Counted, not performed: this suite is about WHEN the controller wakes or
  // sleeps the bridge (the STBY edge), not about what enable()/disable()
  // write to the pin -- motor_test owns that. The two questions fail
  // independently, so they are asked separately.
  void enable() override { enabled_ = true; ++enableCalls; }
  void disable() override { enabled_ = false; ++disableCalls; }
  bool enabled() const override { return enabled_; }
  int enableCalls = 0;
  int disableCalls = 0;
  float appliedThrottle() const override { return throttle_; }
  float appliedSteering() const override { return steering_; }
  float estimatedSpeedMps() const override { return 0; }
private:
  float throttle_ = 0, steering_ = 0;
  bool  enabled_ = false;
};
class FakeImu : public IImu {
public:
  bool begin() override { return false; }
  void update(uint32_t) override {}
  bool present() const override { return false; }
  ImuSample read() const override { return ImuSample::invalid(); }
  void calibrate() override {}
};

std::vector<uint8_t> g_rc, g_arm, g_rel;
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
  g_rc = load("rc"); g_arm = load("arm"); g_rel = load("rel");
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
  // Waking the bridge belongs to the arm EDGE, not to every tick a command
  // happens to arrive while already armed.
  check("the arm edge enables the bridge exactly once", motors.enableCalls == 1);

  // --- 2. a real drive session: 3 s of RC frames at 50 Hz
  for (int i = 0; i < 150; ++i) { feed(g_rc); tick(20); }
  check("driving: armed and out of failsafe", vehicle.armed());

  // --- 3. the stream stops dead (no RELEASE burst). The watchdog must stop the
  //        vehicle -- but a lost stream is not an operator disarm, so the arm
  //        holds and the next frame to arrive drives immediately.
  for (int i = 0; i < 50; ++i) tick(20);           // 1 s past the 500 ms timeout
  check("stalled stream raises failsafe", vehicle.failsafeActive());
  check("stalled stream centres the outputs",
        motors.appliedThrottle() == 0.0f && motors.appliedSteering() == 0.0f);
  check("stalled stream HOLDS the arm (no surprise disarm)", vehicle.armed());

  // --- 4. the regression: ARM again, long after that session ended
  feed(g_arm); tick(10);
  bool held = true;
  for (int i = 0; i < 300; ++i) { tick(20); if (!vehicle.armed()) held = false; }
  check("re-ARM after a finished session holds for 6s", held);

  // --- 5. and the stream still re-arms the watchdog when it comes back: a
  //        second stall must latch failsafe again rather than be swallowed by
  //        the first, while still leaving the arm alone.
  for (int i = 0; i < 50; ++i) { feed(g_rc); tick(20); }
  check("a resumed stream clears failsafe", !vehicle.failsafeActive());
  for (int i = 0; i < 50; ++i) tick(20);
  check("a resumed stream re-arms the watchdog", vehicle.failsafeActive());
  check("the second stall also holds the arm", vehicle.armed());

  // --- 6. the operator lets go: the app's 3x RELEASE burst. Same rule as a
  //        stall -- centre, hold the arm. The app sends this burst when its own
  //        input watchdog trips too, so treating it as a disarm would have made
  //        the arm-holding above unreachable through the app.
  for (int i = 0; i < 60; ++i) { feed(g_rc); tick(20); }
  check("driving again before the release", vehicle.armed());
  for (int i = 0; i < 3; ++i) { feed(g_rel); tick(20); }
  tick(20);
  check("3x RELEASE centres the outputs",
        motors.appliedThrottle() == 0.0f && motors.appliedSteering() == 0.0f);
  check("3x RELEASE HOLDS the arm (no surprise disarm)", vehicle.armed());
  for (int i = 0; i < 150; ++i) tick(20);          // 3 s of the silence that follows
  check("the silence after a release does not disarm either", vehicle.armed());
  for (int i = 0; i < 30; ++i) { feed(g_rc); tick(20); }
  check("the next stream drives without a fresh arm", vehicle.armed());

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
  check("the disarm edge disables the bridge", motors.disableCalls > 0);
  check("disarm cuts the outputs", motors.appliedThrottle() == 0.0f);

  // The safety property: re-arming with NO fresh stick data must not resurrect
  // the demand the vehicle was carrying when it was disarmed.
  feed2(g_ch5hi); tick2(20); tick2(20);
  check("re-arm without fresh sticks does not resurrect demand",
        vehicle2.armed() && motors.appliedThrottle() == 0.0f);

  // ================= F3: network-down failsafe is a pinned invariant =================
  // R4 finding 5: two independently-maintained failsafe clocks currently happen to
  // agree only because they read the exact same cfg.timing.commandTimeoutMs field.
  // MavlinkUdpLink::poll() (MavlinkUdpLink.cpp:202-209) returns BEFORE reaching its
  // own "stream stalled -> centre demand" block whenever network_.connected() is
  // false -- so during a full network outage that clock never runs at all, and
  // pending_/hasPending_/streamLost_ are never refreshed. VehicleController::
  // evaluateFailsafe() (VehicleController.cpp:26-57) is the one that actually
  // protects the rover here: it reads link_.sinceLastCommandMs(), a plain getter
  // over lastCommandMs_ that needs no poll() to have run, and trips on its own.
  //
  // Every other section above starves the STREAM (silence while the link stays
  // healthy). This one kills the NETWORK itself, so the link's own stall clock
  // is provably absent from the picture and the bound below is proven to rest on
  // the controller alone.
  printf("\n-- network-down failsafe: the controller's own clock, not the link's, must trip --\n");
  {
    FakeNetwork network3;
    FakeMotors  motors3;
    std::vector<FakeMotors::Sample> history;
    motors3.recording = &history;
    MavlinkUdpLink    link3(parameters, network3, logger);
    VehicleController vehicle3(link3, motors3, imu, cfg.timing, logger);
    vehicle3.begin();

    g_hostMillis = 100000;
    link3.begin();
    WiFiUDP* sock3 = g_lastSocket;
    auto at    = [&](uint32_t absMs) { g_hostMillis = absMs; link3.poll(g_hostMillis); vehicle3.loop(g_hostMillis); };
    auto tick3 = [&](uint32_t ms)    { at(g_hostMillis + ms); };
    auto feed3 = [&](const std::vector<uint8_t>& f) { sock3->feed(f); };

    // -- drive: nonzero throttle AND steering, actually applied and verified ----
    feed3(g_arm); tick3(10);
    for (int i = 0; i < 60; ++i) { feed3(g_rc); tick3(20); }
    check("network-down setup: armed and out of failsafe", vehicle3.armed() && !vehicle3.failsafeActive());
    check("network-down setup: nonzero throttle and steering are applied",
          motors3.appliedThrottle() > 0.0f && motors3.appliedSteering() > 0.0f);

    // -- go silent: kill the network, not just the stream. From here MavlinkUdpLink::
    //    poll() early-returns every call: its own stall detection cannot run, and
    //    controlStreamLost() must stay false throughout, since nothing ever sets
    //    streamLost_ while the network is down.
    network3.up = false;
    const uint32_t bound       = cfg.timing.commandTimeoutMs;  // the ONE value both clocks read
    const uint32_t period      = cfg.timing.controlPeriodMs;
    const uint32_t lastFrameMs = g_hostMillis;

    at(lastFrameMs + bound);   // since == bound, NOT > bound: must not have tripped yet
    check("failsafe has NOT latched at exactly commandTimeoutMs of silence (the bound is exclusive)",
          !vehicle3.failsafeActive());
    check("demand has not been prematurely zeroed by exactly commandTimeoutMs (still driving)",
          motors3.appliedThrottle() != 0.0f);
    check("MavlinkUdpLink's own stall clock never ran during the outage (poll() returns early on network-down)",
          !link3.controlStreamLost());

    at(lastFrameMs + bound + 1);   // since == bound + 1, now > bound
    check("failsafe latches the instant silence exceeds commandTimeoutMs "
          "-- the controller's own clock, unaided by the link",
          vehicle3.failsafeActive());
    check("the arm holds through the trip (a dead network is not an operator disarm)",
          vehicle3.armed());

    // -- the flag can latch mid control-period; the actuator only obeys on the next
    //    period boundary (VehicleController.cpp:111), so the real end-to-end bound on
    //    PHYSICAL demand is commandTimeoutMs + one controlPeriodMs, not commandTimeoutMs
    //    alone. Pin that too, or a widened control period could quietly widen this.
    tick3(period);
    check("demand at the motor driver returns to zero within one control period of the trip "
          "(commandTimeoutMs + controlPeriodMs end to end)",
          motors3.appliedThrottle() == 0.0f && motors3.appliedSteering() == 0.0f);

    // -- coast, never an instant reversal: reuse the bench driver's own recording
    //    (the sample history FakeMotors kept above) and check the transition from
    //    the last positive demand to zero never crossed sign. The firmware always
    //    reaches failsafe through stop(), never through apply() with a flipped
    //    command -- a demand that jumped straight from + to - without landing on
    //    zero first would be exactly the "instant reversal" defect this pins against.
    bool sawInstantReversal = false;
    bool sawExactZero       = false;
    for (size_t i = 1; i < history.size(); ++i) {
      const FakeMotors::Sample& prev = history[i - 1];
      const FakeMotors::Sample& cur  = history[i];
      const bool throttleFlipped = (prev.throttle > 0.0f && cur.throttle < 0.0f)
                                 || (prev.throttle < 0.0f && cur.throttle > 0.0f);
      const bool steeringFlipped = (prev.steering > 0.0f && cur.steering < 0.0f)
                                 || (prev.steering < 0.0f && cur.steering > 0.0f);
      if (throttleFlipped || steeringFlipped) sawInstantReversal = true;
      if (cur.throttle == 0.0f && cur.steering == 0.0f) sawExactZero = true;
    }
    check("the recorded demand reached exact zero (a coast), not merely a small value", sawExactZero);
    check("no recorded step ever flipped sign directly -- a coast to zero, never a reversal",
          !sawInstantReversal);

    // -- resumption: the network returns, a fresh stream is accepted at once --------
    network3.up = true;
    for (int i = 0; i < 30; ++i) { feed3(g_rc); tick3(period); }
    check("a resumed stream clears the failsafe", !vehicle3.failsafeActive());
    check("fresh demand is accepted again once the stream resumes",
          motors3.appliedThrottle() > 0.0f && motors3.appliedSteering() > 0.0f);
  }

  printf("\n%s\n", g_failures ? "SESSION CHECKS FAILED" : "ALL SESSION CHECKS PASSED");
  return g_failures ? 1 : 0;
}
