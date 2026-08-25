// Asserts HBridgeMotorDriver's reversal behaviour against the real LEDC writes
// rather than the driver's own bookkeeping.
//
// The rover's L298N died on 2026-08-23 with its throttle leg open and its
// steering leg oscillating and hot. That is the signature of SHOOT-THROUGH: the
// old driveAxis() zeroed one input and energised the other in the same call,
// which is safe in source order but not in silicon, because a bipolar bridge
// holds stored charge for microseconds after its input goes low. The dead-time
// checks below cover that.
//
// The second half covers the OTHER way a reversal kills a bridge, which the
// dead time does nothing about: energising against an armature that is still
// spinning. A turning motor is a generator, and reversing the applied voltage
// puts its back-EMF in series with the supply rather than against it. Both
// halves of every leg can be behaving perfectly while that current flows. The
// remedy is a coast at zero, scaled by the speed being left behind, and these
// are the checks that it happens and that it is not a fixed token delay.
#include <stdarg.h>
#include <stdio.h>

#include <Arduino.h>  // the shim, for the captured LEDC state

#include "Config.h"
#include "HBridgeMotorDriver.h"
#include "ILogger.h"

uint32_t g_hostMillis = 0;

namespace {

class QuietLogger : public ILogger {
public:
  bool enabled(LogLevel level) const override { return level <= LogLevel::Warn; }
  void write(LogLevel, const char* tag, const char* format, va_list args) override {
    fprintf(stderr, "    [%s] ", tag); vfprintf(stderr, format, args); fputc('\n', stderr);
  }
};

int failures = 0;
void check(bool ok, const char* what) {
  printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
  if (!ok) failures++;
}

constexpr uint32_t kTickMs = 20;  // the firmware's 50 Hz control period

}  // namespace

int main() {
  const AppConfig& cfg = appConfig();
  QuietLogger logger;
  HBridgeMotorDriver motors(cfg.pins, cfg.pwm, cfg.drive, logger);

  const uint8_t fwd = cfg.pins.throttleForward;
  const uint8_t rev = cfg.pins.throttleReverse;
  const uint8_t left = cfg.pins.steerLeft;
  const uint8_t right = cfg.pins.steerRight;

  motors.begin();
  check(g_ledcAttached[fwd] && g_ledcAttached[rev] && g_ledcAttached[left] && g_ledcAttached[right],
        "begin() attaches all four bridge inputs");

  // --- settle at full forward ---------------------------------------------
  bool bothHotEver = false;
  for (int i = 0; i < 60; i++) {
    g_hostMillis += kTickMs;
    motors.apply(1.0f, 0.0f, kTickMs);
    if (g_ledcDuty[fwd] > 0 && g_ledcDuty[rev] > 0) bothHotEver = true;
  }
  check(g_ledcDuty[fwd] > 0 && g_ledcDuty[rev] == 0, "forward energises fwd only");

  // --- command full reverse, and watch the pins tick by tick ---------------
  uint32_t gapMs = 0;
  int      firstReverseTick = -1;
  for (int i = 0; i < 60; i++) {
    g_hostMillis += kTickMs;
    motors.apply(-1.0f, 0.0f, kTickMs);
    if (g_ledcDuty[fwd] > 0 && g_ledcDuty[rev] > 0) bothHotEver = true;
    if (g_ledcDuty[fwd] == 0 && g_ledcDuty[rev] == 0) gapMs += kTickMs;
    if (g_ledcDuty[rev] > 0 && firstReverseTick < 0) firstReverseTick = i;
  }

  check(!bothHotEver, "both inputs of a leg are never energised at once");
  check(gapMs >= cfg.drive.reversalDeadTimeMs,
        "a reversal holds both inputs low for at least reversalDeadTimeMs");
  check(firstReverseTick >= 0 && g_ledcDuty[rev] > 0 && g_ledcDuty[fwd] == 0,
        "reverse eventually energises rev only");
  printf("      (gap measured %ums, configured %ums)\n", gapMs, cfg.drive.reversalDeadTimeMs);

  // --- the steering leg gets the same treatment ----------------------------
  bothHotEver = false;
  for (int i = 0; i < 60; i++) { g_hostMillis += kTickMs; motors.apply(0.0f, 1.0f, kTickMs); }
  check(g_ledcDuty[right] > 0 && g_ledcDuty[left] == 0, "steer right energises right only");

  uint32_t steerGapMs = 0;
  for (int i = 0; i < 60; i++) {
    g_hostMillis += kTickMs;
    motors.apply(0.0f, -1.0f, kTickMs);
    if (g_ledcDuty[left] > 0 && g_ledcDuty[right] > 0) bothHotEver = true;
    if (g_ledcDuty[left] == 0 && g_ledcDuty[right] == 0) steerGapMs += kTickMs;
  }
  check(!bothHotEver, "steering reversal never energises both inputs at once");
  check(steerGapMs >= cfg.drive.reversalDeadTimeMs, "steering reversal holds a full gap too");

  // --- reversing out of rest must still open the gap -----------------------
  // energisedSign deliberately survives a spell at zero. Without that, coming
  // to a stop would clear the direction and the very next tick could energise
  // the opposite leg with no gap at all -- a stop-then-reverse, which is the
  // most ordinary thing an operator does with a car.
  for (int i = 0; i < 40; i++) { g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs); }
  check(g_ledcDuty[fwd] > 0, "settled forward again");

  // Slew means rest takes ~10 ticks to reach, not one.
  for (int i = 0; i < 15; i++) { g_hostMillis += kTickMs; motors.apply(0.0f, 0.0f, kTickMs); }
  check(g_ledcDuty[fwd] == 0 && g_ledcDuty[rev] == 0, "at rest, both inputs are low");

  g_hostMillis += kTickMs; motors.apply(-1.0f, 0.0f, kTickMs);
  check(g_ledcDuty[fwd] == 0 && g_ledcDuty[rev] == 0,
        "reversing out of rest opens the gap rather than energising immediately");

  // --- stop() cuts both legs unconditionally -------------------------------
  motors.stop();
  check(g_ledcDuty[fwd] == 0 && g_ledcDuty[rev] == 0 && g_ledcDuty[left] == 0 && g_ledcDuty[right] == 0,
        "stop() cuts all four inputs");

  // --- the coast scales with the speed being reversed away from ------------
  //
  // A single fixed delay would have to be sized for a full-speed reversal, and
  // would then make a crawl feel dead. These two runs measure the same reversal
  // from two speeds and require the slow one to be genuinely shorter.
  auto measureReversal = [&](float fromDemand, float toDemand) -> uint32_t {
    motors.stop();
    // Settle at `fromDemand` -- long enough for the accel ramp to finish.
    for (int i = 0; i < 80; i++) { g_hostMillis += kTickMs; motors.apply(fromDemand, 0.0f, kTickMs); }
    uint32_t zeroMs = 0;
    bool     bothHot = false;
    for (int i = 0; i < 80; i++) {
      g_hostMillis += kTickMs;
      motors.apply(toDemand, 0.0f, kTickMs);
      if (g_ledcDuty[fwd] > 0 && g_ledcDuty[rev] > 0) bothHot = true;
      // Count only ticks where the axis is at rest AND has not yet turned
      // around -- the ramp down still has the outgoing pin hot, so it does not
      // count as coast.
      if (g_ledcDuty[fwd] == 0 && g_ledcDuty[rev] == 0) zeroMs += kTickMs;
    }
    check(!bothHot, "  ...and never energises both inputs while doing so");
    return zeroMs;
  };

  const uint32_t fullReversalMs = measureReversal(1.0f, -1.0f);
  const uint32_t crawlReversalMs = measureReversal(0.15f, -0.15f);

  const uint32_t minCoast = cfg.drive.reversalCoastMs + cfg.drive.reversalDeadTimeMs;
  check(fullReversalMs >= minCoast,
        "a full-speed reversal coasts for at least reversalCoastMs + the dead time");
  check(crawlReversalMs < fullReversalMs,
        "a crawl reverses faster than full speed -- the coast is speed-scaled, not fixed");
  check(crawlReversalMs >= cfg.drive.reversalDeadTimeMs,
        "...but even a crawl still gets the full electrical dead time");
  printf("      (full-speed coast %ums vs crawl %ums; floor %ums)\n",
         fullReversalMs, crawlReversalMs, minCoast);

  // Removing the coast entirely must break these, or they prove nothing. With
  // reversalCoastMs/PerUnit at 0 the full-speed figure collapses to the dead
  // time alone and the scaling check below has nothing left to compare.
  check(cfg.drive.reversalCoastMs > 0 || cfg.drive.reversalCoastPerUnitMs > 0,
        "the configuration under test actually has a coast to measure");

  // --- acceleration is gentler than deceleration ---------------------------
  //
  // Shedding duty costs nothing; adding it pushes current into an armature that
  // is not yet moving with the field. One rate for both would have to be sized
  // for the safe direction and would make the failsafe sluggish.
  check(cfg.drive.accelPerSecond < cfg.drive.decelPerSecond,
        "accelPerSecond is slower than decelPerSecond");

  motors.stop();
  int ticksToFull = 0;
  for (int i = 0; i < 200; i++) {
    g_hostMillis += kTickMs;
    motors.apply(1.0f, 0.0f, kTickMs);
    ticksToFull++;
    if (motors.appliedThrottle() >= cfg.drive.maxThrottle - 1e-4f) break;
  }
  int ticksToRest = 0;
  for (int i = 0; i < 200; i++) {
    g_hostMillis += kTickMs;
    motors.apply(0.0f, 0.0f, kTickMs);
    ticksToRest++;
    if (motors.appliedThrottle() == 0.0f) break;
  }
  check(ticksToFull > 1, "reaching full throttle is a ramp, not a step");
  check(ticksToRest < ticksToFull, "coming to rest is quicker than getting up to speed");
  printf("      (%d ticks up, %d ticks down, at %ums each)\n", ticksToFull, ticksToRest, kTickMs);

  // --- changing your mind mid-reversal must not impose the wait ------------
  //
  // Latching the reversal is what makes the coast survive across ticks; the
  // cost of getting this wrong is a rover that ignores the stick for a third of
  // a second after a twitch, which reads as a dropped link.
  motors.stop();
  for (int i = 0; i < 80; i++) { g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs); }
  g_hostMillis += kTickMs; motors.apply(-1.0f, 0.0f, kTickMs);   // ask to reverse
  g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs);    // ...and immediately take it back
  for (int i = 0; i < 5; i++) { g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs); }
  check(g_ledcDuty[fwd] > 0,
        "abandoning a reversal resumes at once rather than serving out the coast");

  // --- stop() abandons an in-flight reversal -------------------------------
  //
  // A failsafe that left the latch set would make the FIRST command after
  // recovery wait out a coast for a reversal that never completed.
  motors.stop();
  for (int i = 0; i < 80; i++) { g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs); }
  g_hostMillis += kTickMs; motors.apply(-1.0f, 0.0f, kTickMs);   // reversal now latched
  motors.stop();
  check(motors.appliedThrottle() == 0.0f, "stop() during a reversal zeroes the output");
  for (int i = 0; i < 40; i++) { g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs); }
  check(g_ledcDuty[fwd] > 0,
        "driving the ORIGINAL direction after a stop is not gated by the abandoned reversal");

  printf(failures ? "\nmotor_test: %d FAILED\n" : "\nmotor_test: all checks passed\n", failures);
  return failures ? 1 : 0;
}
