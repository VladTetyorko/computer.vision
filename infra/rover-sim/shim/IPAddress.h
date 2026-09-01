// Host shim for the Arduino IPAddress/String pair that WiFiUdp.h drags in on
// the real core. Only the operations the firmware actually performs on a peer
// address are here: compare it, store it, print it.
#pragma once
#include <stdint.h>
#include <stdio.h>
#include <string>

class String {
public:
  String() = default;
  String(const char* s) : s_(s ? s : "") {}
  const char* c_str() const { return s_.c_str(); }

private:
  std::string s_;
};

class IPAddress {
public:
  IPAddress() = default;
  /// @param networkOrder the address as it comes off a sockaddr_in, i.e. big-endian
  explicit IPAddress(uint32_t networkOrder) : addr_(networkOrder) {}

  bool operator==(const IPAddress& other) const { return addr_ == other.addr_; }
  bool operator!=(const IPAddress& other) const { return !(*this == other); }

  String toString() const {
    const uint8_t* b = reinterpret_cast<const uint8_t*>(&addr_);
    char buffer[16];
    snprintf(buffer, sizeof(buffer), "%u.%u.%u.%u", b[0], b[1], b[2], b[3]);
    return String(buffer);
  }

  uint32_t networkOrder() const { return addr_; }

private:
  uint32_t addr_ = 0;
};
