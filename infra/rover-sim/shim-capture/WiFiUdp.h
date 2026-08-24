// Host shim for WiFiUDP: an in-memory socket. Inbound bytes are fed from a
// fixture file; every outbound datagram is captured for pymavlink to check.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string>
#include <vector>

class WiFiUDP;
extern WiFiUDP* g_lastSocket;

class WiFiUDP {
public:
  WiFiUDP() { g_lastSocket = this; }
  std::vector<uint8_t> inbound;      // remaining bytes of the current datagram
  size_t inboundPos = 0;
  bool   inboundPending = false;
  std::vector<std::vector<uint8_t>> sent;
  std::vector<uint8_t> building;

  uint8_t begin(uint16_t) { return 1; }

  void feed(const std::vector<uint8_t>& bytes) {
    inbound = bytes; inboundPos = 0; inboundPending = true;
  }

  int parsePacket() {
    if (!inboundPending) return 0;
    inboundPending = false;
    return static_cast<int>(inbound.size() - inboundPos);
  }
  int available() { return static_cast<int>(inbound.size() - inboundPos); }
  int read() { return inboundPos < inbound.size() ? inbound[inboundPos++] : -1; }

  int beginPacket(const char*, uint16_t) { building.clear(); return 1; }
  size_t write(const uint8_t* b, size_t n) { building.insert(building.end(), b, b + n); return n; }
  int endPacket() { sent.push_back(building); building.clear(); return 1; }
};
