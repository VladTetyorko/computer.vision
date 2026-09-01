// Host shim for WiFiUDP: an in-memory socket. Inbound bytes are fed from a
// fixture file; every outbound datagram is captured for pymavlink to check.
#pragma once
#include <stdint.h>
#include <stddef.h>

#include <IPAddress.h>
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

  // Where the datagram currently being read came from. Port 0 is "unknown",
  // which is what an in-memory fixture is unless a test says otherwise -- and
  // the firmware treats port 0 as "do not learn a peer from this".
  IPAddress remote;
  uint16_t  remotePortValue = 0;

  // Set to N to make the next N endPacket() calls fail, the way a socket with
  // no route does. The firmware's escalation is only reachable through this.
  int failEndPacketFor = 0;
  int stopped = 0;

  uint8_t begin(uint16_t) { return 1; }
  void    stop() { stopped++; }

  /// A datagram from nobody in particular: the sender is cleared, so the
  /// firmware sees remotePort()==0 and declines to learn a peer from it.
  void feed(const std::vector<uint8_t>& bytes) {
    inbound = bytes; inboundPos = 0; inboundPending = true;
    remote = IPAddress(); remotePortValue = 0;
  }

  /// Same as feed(), but the datagram arrives from a named sender.
  void feedFrom(const std::vector<uint8_t>& bytes, uint32_t networkOrderIp, uint16_t port) {
    feed(bytes);
    remote = IPAddress(networkOrderIp);
    remotePortValue = port;
  }

  IPAddress remoteIP() { return remote; }
  uint16_t  remotePort() { return remotePortValue; }

  int parsePacket() {
    if (!inboundPending) return 0;
    inboundPending = false;
    return static_cast<int>(inbound.size() - inboundPos);
  }
  int available() { return static_cast<int>(inbound.size() - inboundPos); }
  int read() { return inboundPos < inbound.size() ? inbound[inboundPos++] : -1; }

  int beginPacket(const char* host, uint16_t port) {
    building.clear(); lastTarget = host ? host : ""; lastTargetPort = port; return 1;
  }
  int beginPacket(IPAddress ip, uint16_t port) {
    building.clear(); lastTarget = ip.toString().c_str(); lastTargetPort = port; return 1;
  }
  size_t write(const uint8_t* b, size_t n) { building.insert(building.end(), b, b + n); return n; }
  int endPacket() {
    if (failEndPacketFor > 0) { failEndPacketFor--; building.clear(); return 0; }
    sent.push_back(building);
    sentTo.push_back(lastTarget + ":" + std::to_string(lastTargetPort));
    building.clear();
    return 1;
  }

  std::string lastTarget;
  uint16_t    lastTargetPort = 0;
  std::vector<std::string> sentTo;   ///< one entry per datagram in `sent`
};
