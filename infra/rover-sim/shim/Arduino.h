// Host shim: the firmware uses millis() and the ESP32 core's LEDC PWM.
#pragma once
#include <stdint.h>
#include <math.h>
#include <stddef.h>

extern uint32_t g_hostMillis;
inline uint32_t millis() { return g_hostMillis; }

/// LEDC, host side. Duty is recorded per pin rather than discarded, because the
/// only thing worth asserting about a bridge driver is what actually reaches
/// its inputs -- in particular that no reversal ever leaves both inputs of one
/// leg energised at the same instant.
inline constexpr uint8_t kHostPins = 64;
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
