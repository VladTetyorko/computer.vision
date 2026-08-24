# Builds the ESP32 rover firmware for this machine. Everything below the motor
# pins is the unmodified sketch; only Arduino.h and WiFiUDP are shimmed.
#
#   make            build both binaries
#   make wire       run the wire-format check against pymavlink
#   make session    replay the arm/drive/stop/re-arm session lifecycle
#   make motors     check the H-bridge reversal dead-time against the real pins
#   make run        drive a live vision instance (HOST=... PORT=...)
#
# FIRMWARE points at the Arduino sketch; override it if yours lives elsewhere.

FIRMWARE ?= $(HOME)/Arduino/ardupoilot-start
# pymavlink is the reference implementation the wire check measures against; the
# repo's cv-service venv already carries it, so prefer that over bare python3.
VENV_PYTHON := $(abspath ../../cv/cv-service/.venv/bin/python)
PYTHON   ?= $(if $(wildcard $(VENV_PYTHON)),$(VENV_PYTHON),python3)
HOST     ?= 127.0.0.1
PORT     ?= 14551
BUILD    := build

SRC := $(FIRMWARE)/MavlinkUdpLink.cpp $(FIRMWARE)/MavlinkV2Codec.cpp $(FIRMWARE)/Config.cpp
CXXFLAGS := -std=gnu++2b -Wall -Wextra -O1 -I$(FIRMWARE)

.PHONY: all wire session motors check run clean
all: $(BUILD)/rover_host $(BUILD)/wire_test $(BUILD)/session_test $(BUILD)/motor_test

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/rover_host: rover_host.cpp $(SRC) $(FIRMWARE)/VehicleController.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim $^ -o $@

$(BUILD)/wire_test: wire_test.cpp $(SRC) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim-capture -Ishim $^ -o $@

$(BUILD)/session_test: session_test.cpp $(SRC) $(FIRMWARE)/VehicleController.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim-capture -Ishim $^ -o $@

$(BUILD)/motor_test: motor_test.cpp $(FIRMWARE)/HBridgeMotorDriver.cpp $(FIRMWARE)/Config.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim $^ -o $@

wire: $(BUILD)/wire_test
	$(PYTHON) wire_fixture.py $(BUILD)/rx_fixture.bin
	$(BUILD)/wire_test $(BUILD)/rx_fixture.bin $(BUILD)/tx_frames.bin
	$(PYTHON) wire_verify.py $(BUILD)/tx_frames.bin

# The watchdog only means anything across a whole session, so this replays one:
# arm cold, drive, stall, re-arm -- and the ch5 arm switch with its interlock.
# Fixtures come from pymavlink, same as `wire`.
session: $(BUILD)/session_test
	$(PYTHON) session_fixture.py $(BUILD)
	$(BUILD)/session_test $(BUILD)

# The bridge driver is the one part whose bug burns hardware rather than
# failing a request, so it is checked against the captured LEDC writes.
motors: $(BUILD)/motor_test
	$(BUILD)/motor_test

check: wire session motors

run: $(BUILD)/rover_host
	$(BUILD)/rover_host $(HOST) $(PORT)

clean:
	rm -rf $(BUILD)
