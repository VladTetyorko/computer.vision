// Host shim for WiFiUDP backed by a real POSIX UDP socket, so the unmodified
// firmware link talks to the actual vision application.
#pragma once
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <vector>

#include "IPAddress.h"

class WiFiUDP {
public:
  uint8_t begin(uint16_t port) {
    fd_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd_ < 0) return 0;
    int reuse = 1;
    setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (bind(fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) return 0;
    fcntl(fd_, F_SETFL, O_NONBLOCK);
    return 1;
  }

  void stop() { if (fd_ >= 0) { close(fd_); fd_ = -1; } }

  int parsePacket() {
    if (pos_ < rx_.size()) return static_cast<int>(rx_.size() - pos_);
    uint8_t buffer[2048];
    sockaddr_in from{};
    socklen_t fromLen = sizeof(from);
    const ssize_t n = recvfrom(fd_, buffer, sizeof(buffer), 0,
                               reinterpret_cast<sockaddr*>(&from), &fromLen);
    if (n <= 0) return 0;
    remote_ = IPAddress(from.sin_addr.s_addr);
    remotePort_ = ntohs(from.sin_port);
    rx_.assign(buffer, buffer + n);
    pos_ = 0;
    return static_cast<int>(n);
  }

  IPAddress remoteIP() const { return remote_; }
  uint16_t  remotePort() const { return remotePort_; }
  int available() { return static_cast<int>(rx_.size() - pos_); }
  int read() { return pos_ < rx_.size() ? rx_[pos_++] : -1; }

  int beginPacket(const char* host, uint16_t port) {
    memset(&peer_, 0, sizeof(peer_));
    peer_.sin_family = AF_INET;
    peer_.sin_port = htons(port);
    if (inet_pton(AF_INET, host, &peer_.sin_addr) != 1) return 0;
    tx_.clear();
    return 1;
  }

  int beginPacket(IPAddress ip, uint16_t port) {
    memset(&peer_, 0, sizeof(peer_));
    peer_.sin_family = AF_INET;
    peer_.sin_port = htons(port);
    peer_.sin_addr.s_addr = ip.networkOrder();
    tx_.clear();
    return 1;
  }
  size_t write(const uint8_t* b, size_t n) { tx_.insert(tx_.end(), b, b + n); return n; }
  int endPacket() {
    const ssize_t n = sendto(fd_, tx_.data(), tx_.size(), 0,
                             reinterpret_cast<sockaddr*>(&peer_), sizeof(peer_));
    tx_.clear();
    return n > 0 ? 1 : 0;
  }

private:
  int fd_ = -1;
  std::vector<uint8_t> rx_, tx_;
  size_t pos_ = 0;
  IPAddress remote_;
  uint16_t  remotePort_ = 0;
  sockaddr_in peer_{};
};
