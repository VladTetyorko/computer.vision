// Host shim: the firmware uses millis(), digitalWrite()/pinMode() for the
// TB6612FNG's direction and STBY pins, and the ESP32 core's LEDC PWM for the
// two speed pins.
#pragma once
#include <stdint.h>
#include <math.h>
#include <stddef.h>

extern uint32_t g_hostMillis;
inline uint32_t millis() { return g_hostMillis; }

inline constexpr uint8_t kHostPins = 64;

/// LEDC, host side. Duty is recorded per pin rather than discarded, because the
/// only thing worth asserting about a bridge driver is what actually reaches
/// its inputs -- in particular that no reversal ever leaves both inputs of one
/// leg energised at the same instant.
inline uint16_t g_ledcDuty[kHostPins];
inline bool     g_ledcAttached[kHostPins];

inline bool ledcAttach(uint8_t pin, uint32_t /*freqHz*/, uint8_t /*bits*/) {
  if (pin >= kHostPins) return false;
  g_ledcAttached[pin] = true;
  g_ledcDuty[pin]     = 0;
  return true;
}

inline void ledcWrite(uint8_t pin, uint32_t duty) {
  if (pin < kHostPins) g_ledcDuty[pin] = static_cast<uint16_t>(duty);
}

/// Plain GPIO, host side. The TB6612FNG's IN1/IN2 (direction) and STBY
/// (arm/disarm) pins are driven with digitalWrite(), never LEDC -- only the
/// two PWM pins go through ledcWrite() above. Recording the level per pin,
/// like the LEDC duty above, is what lets a test assert on what actually
/// reached the chip rather than on the driver's own bookkeeping.
constexpr uint8_t OUTPUT = 1;
constexpr uint8_t HIGH   = 1;
constexpr uint8_t LOW    = 0;

inline uint8_t g_digitalLevel[kHostPins];

inline void pinMode(uint8_t pin, uint8_t /*mode*/) {
  // Real hardware boot-safety here rests on the TB6612FNG's own 200k
  // pull-downs (Config.cpp), not on pinMode(); LOW is simply the host's
  // starting value for an unconfigured pin.
  if (pin < kHostPins) g_digitalLevel[pin] = LOW;
}

inline void digitalWrite(uint8_t pin, uint8_t level) {
  if (pin < kHostPins) g_digitalLevel[pin] = level;
}
