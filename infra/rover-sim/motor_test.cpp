// Asserts Tb6612MotorDriver's behaviour against the real GPIO/LEDC writes
// rather than the driver's own bookkeeping.
//
// Both axes are now bare TB6612FNG channels running the SAME reversal state
// machine -- Idle -> Settling -> Driving -> Braking -> Coasting -> DeadTime ->
// Idle -- because there is no longer an ESC hiding the reversal problem on
// the throttle side. There is no shortcut from Driving one way straight to
// Driving the other: every reversal pays the electrical dead time (both
// direction pins low) and the speed-scaled mechanical coast (armature
// spin-down) in full, even when the stick sweeps through centre in a single
// control tick. "Zero" on a channel is IN1=IN2=LOW (coast), never a
// direction pin held HIGH with PWM 0 -- the difference between an idle
// bridge and a short brake waiting to happen the instant PWM comes back.
//
// This is the one module here whose bug burns hardware rather than failing a
// request: the rover's L298N died on 2026-08-23 with its steering leg
// oscillating and hot, the signature of shoot-through (one input still
// energised while the other comes up). The checks below cover the two
// distinct ways a reversal kills a leg: the dead time protects the bridge
// from itself, the coast protects it from the motor's own back-EMF.
#include <stdarg.h>
#include <stdio.h>

#include <Arduino.h>  // the shim, for the captured GPIO/LEDC state

#include "Config.h"
#include "ILogger.h"
#include "Tb6612MotorDriver.h"

uint32_t g_hostMillis = 0;

namespace {

class QuietLogger : public ILogger {
public:
  bool enabled(LogLevel level) const override { return level <= LogLevel::Info; }
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

const AppConfig& cfg = appConfig();

/// One TB6612FNG channel's pins, so both axes can share every check below
/// instead of duplicating them.
struct AxisPins {
  const char* name;
  uint8_t     in1, in2, pwm;
};

bool atRest(const AxisPins& axis) {
  return g_digitalLevel[axis.in1] == LOW && g_digitalLevel[axis.in2] == LOW
         && g_ledcDuty[axis.pwm] == 0;
}

}  // namespace

int main() {
  QuietLogger logger;
  Tb6612MotorDriver motors(cfg.pins, cfg.pwm, cfg.drive, logger);

  const AxisPins throttle{"throttle", cfg.pins.throttleIn1, cfg.pins.throttleIn2, cfg.pins.throttlePwm};
  const AxisPins steering{"steering", cfg.pins.steerIn1,    cfg.pins.steerIn2,    cfg.pins.steerPwm};

  printf("-- begin(): asleep, both channels at rest --\n");
  motors.begin();
  check(!motors.enabled(), "begin() leaves the bridge asleep");
  check(g_digitalLevel[cfg.pins.standby] == LOW, "...STBY is driven low before anything else");
  check(g_ledcAttached[throttle.pwm] && g_ledcAttached[steering.pwm],
        "begin() attaches LEDC on both PWM pins");
  check(atRest(throttle) && atRest(steering),
        "both channels start at rest: IN1=IN2=LOW, PWM 0");

  printf("\n-- disarmed: apply() drives nothing, whatever the demand --\n");
  motors.apply(1.0f, 1.0f, kTickMs);
  check(atRest(throttle) && atRest(steering),
        "a demand while the bridge is asleep never reaches the pins");

  printf("\n-- enable(): wakes the bridge, still at rest until the first apply() --\n");
  motors.enable();
  check(motors.enabled(), "enable() wakes the bridge");
  check(g_digitalLevel[cfg.pins.standby] == HIGH, "...STBY is driven high");
  check(atRest(throttle) && atRest(steering),
        "waking the bridge does not itself move anything -- only apply() does");

  auto settle = [&](float throttleDemand, float steeringDemand, int ticks) {
    for (int i = 0; i < ticks; i++) {
      g_hostMillis += kTickMs;
      motors.apply(throttleDemand, steeringDemand, kTickMs);
    }
  };

  printf("\n-- driving: exactly one direction pin per channel, never both --\n");
  settle(1.0f, 1.0f, 60);
  check(g_digitalLevel[throttle.in1] == HIGH && g_digitalLevel[throttle.in2] == LOW
            && g_ledcDuty[throttle.pwm] > 0,
        "forward throttle energises IN1 only");
  check(g_digitalLevel[steering.in1] == HIGH && g_digitalLevel[steering.in2] == LOW
            && g_ledcDuty[steering.pwm] > 0,
        "right steering energises IN1 only");
  check(motors.appliedThrottle() > 0.0f && motors.appliedSteering() > 0.0f,
        "the driver reports what it applied");

  motors.stop();
  check(atRest(throttle) && atRest(steering),
        "stop() cuts both channels to IN1=IN2=LOW, PWM 0 -- coast, not brake");

  // maxThrottle/maxSteering are a ceiling on the DEMAND, so full stick must
  // not necessarily reach the channel's own full-scale duty for throttle
  // (ceiling 0.60), while steering (ceiling 1.00) may.
  settle(1.0f, 1.0f, 200);
  const uint16_t dutyMax = cfg.pwm.dutyMax();
  check(g_ledcDuty[throttle.pwm] < dutyMax,
        "maxThrottle keeps full stick short of the PWM ceiling");
  check(g_ledcDuty[steering.pwm] > 0,
        "full steering lock reaches its own configured ceiling");
  motors.stop();

  printf("\n-- ramps: acceleration and braking are never a step --\n");
  check(cfg.drive.accelPerSecond < cfg.drive.decelPerSecond,
        "throttle: accelPerSecond is slower than decelPerSecond -- current into a "
        "stalled armature is the expensive direction");
  check(cfg.drive.steerAccelPerSecond < cfg.drive.steerDecelPerSecond,
        "steering: same asymmetry");
  {
    int ticksToFull = 0;
    for (int i = 0; i < 200; i++) {
      g_hostMillis += kTickMs; motors.apply(1.0f, 0.0f, kTickMs); ticksToFull++;
      if (motors.appliedThrottle() >= cfg.drive.maxThrottle - 1e-4f) break;
    }
    int ticksToRest = 0;
    for (int i = 0; i < 200; i++) {
      g_hostMillis += kTickMs; motors.apply(0.0f, 0.0f, kTickMs); ticksToRest++;
      if (motors.appliedThrottle() == 0.0f) break;
    }
    check(ticksToFull > 1, "reaching full throttle is a ramp, not a step");
    check(ticksToRest < ticksToFull, "coming to rest is quicker than getting up to speed");
    printf("      (throttle: %d ticks up, %d ticks down, at %ums each)\n",
           ticksToFull, ticksToRest, kTickMs);
  }
  motors.stop();

  printf("\n-- disable(): stops first, then sleeps the bridge --\n");
  settle(1.0f, 1.0f, 60);
  check(g_ledcDuty[throttle.pwm] > 0 && g_ledcDuty[steering.pwm] > 0, "driving before disable()");
  motors.disable();
  check(!motors.enabled(), "disable() puts the bridge to sleep");
  check(g_digitalLevel[cfg.pins.standby] == LOW, "...STBY is driven low");
  check(atRest(throttle) && atRest(steering),
        "disable() cuts outputs to rest before sleeping, not after");

  // Race between arm and first demand: the demand the operator was holding
  // when the bridge went to sleep must not resurrect the instant it wakes.
  motors.apply(1.0f, 1.0f, kTickMs);
  check(atRest(throttle) && atRest(steering), "apply() while disabled still drives nothing");
  motors.enable();
  check(atRest(throttle) && atRest(steering),
        "re-enabling resumes at rest -- no demand was latent behind STBY");

  printf("\n-- the reversal state machine, per axis --\n");
  // Runs one axis through the state machine and checks every invariant that
  // applies uniformly to both channels. `drive(v)` applies `v` to THIS axis
  // and 0 to the other, advancing one control tick.
  auto testAxisReversal = [&](const AxisPins& axis, auto drive) {
    printf("  -- %s --\n", axis.name);

    // Checked on every tick below, not just at the end: a direction pin held
    // HIGH with PWM 0 is legitimate for exactly one tick -- the Settling
    // preamble commits the new direction startSettleMs before the PWM comes
    // up, on purpose, so a stray current the instant PWM starts never lands
    // through inputs that are still moving. What must never happen is that
    // state OUTLIVING the settle: a direction left "held" at zero duty as a
    // way of representing REST is exactly the hazard "zero" is meant to rule
    // out -- rest is IN1=IN2=LOW, never a direction pin sitting HIGH with
    // nothing driving it.
    uint32_t heldZeroStreakMs = 0, maxHeldZeroStreakMs = 0;
    auto tick = [&](float value) {
      drive(value);
      const bool zeroDuty       = g_ledcDuty[axis.pwm] == 0;
      const bool directionHeld  = g_digitalLevel[axis.in1] == HIGH || g_digitalLevel[axis.in2] == HIGH;
      if (zeroDuty && directionHeld) {
        heldZeroStreakMs += kTickMs;
        if (heldZeroStreakMs > maxHeldZeroStreakMs) maxHeldZeroStreakMs = heldZeroStreakMs;
      } else {
        heldZeroStreakMs = 0;
      }
    };

    motors.stop();

    // -- no shoot-through, and the dead-time gap --------------------------
    bool bothHotEver = false;
    for (int i = 0; i < 60; i++) {
      tick(1.0f);
      if (g_digitalLevel[axis.in1] == HIGH && g_digitalLevel[axis.in2] == HIGH) bothHotEver = true;
    }
    // A full-forward hold, then straight to full-reverse with no explicit
    // zero tick in between: sweeping the stick through centre in one frame
    // must get the same gap as holding it there.
    uint32_t gapMs = 0;
    for (int i = 0; i < 60; i++) {
      tick(-1.0f);
      if (g_digitalLevel[axis.in1] == HIGH && g_digitalLevel[axis.in2] == HIGH) bothHotEver = true;
      if (g_digitalLevel[axis.in1] == LOW && g_digitalLevel[axis.in2] == LOW) gapMs += kTickMs;
    }
    check(!bothHotEver, "both direction pins are never HIGH at once (no shoot-through)");
    check(gapMs >= cfg.drive.reversalDeadTimeMs,
          "a one-frame reversal still holds both direction pins low for at least reversalDeadTimeMs");
    check(g_digitalLevel[axis.in2] == HIGH && g_digitalLevel[axis.in1] == LOW
              && g_ledcDuty[axis.pwm] > 0,
          "...and then energises IN2 only -- the opposite direction, not a partial one");
    printf("      (gap measured %ums, configured dead time %ums)\n",
           gapMs, cfg.drive.reversalDeadTimeMs);

    // -- coast scales with the speed being reversed away from -------------
    // One fixed delay would have to be sized for a full-speed reversal and
    // would make a crawl feel dead.
    auto measureReversal = [&](float from, float to) -> uint32_t {
      motors.stop();
      for (int i = 0; i < 80; i++) tick(from);
      uint32_t zeroMs = 0;
      bool bothHot = false;
      for (int i = 0; i < 80; i++) {
        tick(to);
        if (g_digitalLevel[axis.in1] == HIGH && g_digitalLevel[axis.in2] == HIGH) bothHot = true;
        if (g_digitalLevel[axis.in1] == LOW && g_digitalLevel[axis.in2] == LOW) zeroMs += kTickMs;
      }
      check(!bothHot, "  ...and never energises both inputs while doing so");
      return zeroMs;
    };

    const uint32_t fullMs  = measureReversal(1.0f, -1.0f);
    const uint32_t crawlMs = measureReversal(0.15f, -0.15f);
    const uint32_t minCoast = cfg.drive.reversalCoastMs + cfg.drive.reversalDeadTimeMs;
    check(fullMs >= minCoast, "a full-speed reversal coasts for at least reversalCoastMs + dead time");
    check(crawlMs < fullMs, "a crawl reverses faster -- the coast is speed-scaled, not fixed");
    check(crawlMs >= cfg.drive.reversalDeadTimeMs, "...but even a crawl gets the full dead time");
    printf("      (full-speed coast %ums vs crawl %ums; floor %ums)\n", fullMs, crawlMs, minCoast);

    // Zeroing the coast must break the two checks above, or they prove nothing.
    check(cfg.drive.reversalCoastMs > 0 || cfg.drive.reversalCoastPerUnitMs > 0,
          "the configuration under test actually has a coast to measure");

    // -- abandoning a reversal must not impose the wait --------------------
    // The cost of getting this wrong is a rover that ignores the stick for a
    // third of a second after a twitch, which on the ground reads as a
    // dropped link.
    motors.stop();
    for (int i = 0; i < 80; i++) tick(1.0f);
    tick(-1.0f);   // start a reversal (still Braking: one decel step in)
    tick(1.0f);    // ...and change their mind before it left Braking
    for (int i = 0; i < 5; i++) tick(1.0f);
    check(g_digitalLevel[axis.in1] == HIGH && g_ledcDuty[axis.pwm] > 0,
          "abandoning a reversal resumes at once rather than serving the coast");

    // -- stop() landing inside an already-running coast --------------------
    // A failsafe that trips mid-reversal must not extend the wind-down: only
    // the Driving/Braking -> Coasting edge may arm the timer, and every call
    // once coasting has begun has to be a no-op, or a failsafe that keeps
    // firing every tick would mean a reversal that started before it tripped
    // never finishes.
    auto reversalZeroMs = [&](bool interruptWithStop) -> uint32_t {
      motors.stop();
      for (int i = 0; i < 80; i++) tick(1.0f);
      uint32_t zeroMs = 0;
      for (int i = 0; i < 80; i++) {
        tick(-1.0f);
        if (g_digitalLevel[axis.in1] == LOW && g_digitalLevel[axis.in2] == LOW
                && g_ledcDuty[axis.pwm] == 0) {
          zeroMs += kTickMs;
          if (interruptWithStop) motors.stop();
        }
      }
      return zeroMs;
    };
    const uint32_t undisturbed  = reversalZeroMs(false);
    const uint32_t interrupted  = reversalZeroMs(true);
    check(interrupted == undisturbed,
          "stop() landing inside a running coast does not extend it");
    printf("      (undisturbed coast %ums, interrupted coast %ums)\n", undisturbed, interrupted);

    // Settling's own exit check flips the state to Driving on the tick where
    // nowMs first reaches untilMs, but that tick's switch-case ends there --
    // the ramp/setPwm that would put real duty on the pins only runs on the
    // FOLLOWING tickAxis() call. So the pins observably hold direction/PWM 0
    // for one control tick longer than startSettleMs itself: at most a second
    // tick, never an indefinite one, which is the actual hazard this guards.
    const uint32_t maxHeldZero = cfg.drive.startSettleMs + kTickMs;
    check(maxHeldZeroStreakMs <= maxHeldZero,
          "a direction pin is only ever HIGH with PWM 0 for the settle preamble "
          "(plus the one tick its exit is observed on), never as a resting state");
    printf("      (longest direction-held-at-zero streak %ums, allowed up to %ums)\n",
           maxHeldZeroStreakMs, maxHeldZero);
  };

  testAxisReversal(throttle, [&](float v) {
    g_hostMillis += kTickMs;
    motors.apply(v, 0.0f, kTickMs);
  });
  testAxisReversal(steering, [&](float v) {
    g_hostMillis += kTickMs;
    motors.apply(0.0f, v, kTickMs);
  });

  printf(failures ? "\nmotor_test: %d FAILED\n" : "\nmotor_test: all checks passed\n", failures);
  return failures ? 1 : 0;
}
