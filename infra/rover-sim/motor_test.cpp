// Asserts the reversal dead-time in HBridgeMotorDriver, against the real
// LEDC writes rather than the driver's own bookkeeping.
//
// The rover's L298N died on 2026-08-23 with its throttle leg open and its
// steering leg oscillating and hot -- the signature of shoot-through. The old
// driveAxis() zeroed one input and energised the other in the same call, which
// is safe in source order but not in silicon: a bipolar bridge holds stored
// charge for microseconds after its input goes low, so both legs conduct
// briefly on every direction change. This checks that a reversal now passes
// through a real off period, and that both inputs of a leg are never hot at
// once.
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

  printf(failures ? "\nmotor_test: %d FAILED\n" : "\nmotor_test: all checks passed\n", failures);
  return failures ? 1 : 0;
}
