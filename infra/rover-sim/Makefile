# Builds the ESP32 rover firmware for this machine. Everything below the motor
# pins is the unmodified sketch; only Arduino.h and WiFiUDP are shimmed.
#
#   make            build all six binaries
#   make check      run all five regression suites: wire+session+motors+commands+link
#   make wire       run the wire-format check against pymavlink
#   make session    replay the arm/drive/stop/re-arm session lifecycle
#   make motors     check the TB6612FNG reversal dead-time + coast against the real pins
#   make commands   answer every command the app can send, checked by pymavlink
#   make link       reply addressing and transmit-failure recovery
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

SRC := $(FIRMWARE)/MavlinkUdpLink.cpp $(FIRMWARE)/MavlinkV2Codec.cpp \
       $(FIRMWARE)/ParameterStore.cpp $(FIRMWARE)/Config.cpp
CXXFLAGS := -std=gnu++2b -Wall -Wextra -O1 -I$(FIRMWARE)

# Every binary depends on every firmware header. Without this, editing a header
# leaves the binaries stale and the tests keep passing against the OLD code --
# which is worse than failing, because a green run then means nothing.
# MavlinkProtocol.h in particular is almost entirely constants, so a header-only
# edit is the normal way a wire bug gets introduced and fixed.
HEADERS := $(wildcard $(FIRMWARE)/*.h) $(wildcard shim/*.h) $(wildcard shim-capture/*.h)

.PHONY: all wire session motors commands link check run clean
all: $(BUILD)/rover_host $(BUILD)/wire_test $(BUILD)/session_test $(BUILD)/motor_test \
     $(BUILD)/command_test $(BUILD)/link_test

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/rover_host: rover_host.cpp $(SRC) $(FIRMWARE)/VehicleController.cpp $(HEADERS) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim $(filter %.cpp,$^) -o $@

$(BUILD)/wire_test: wire_test.cpp $(SRC) $(HEADERS) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim-capture -Ishim $(filter %.cpp,$^) -o $@

$(BUILD)/session_test: session_test.cpp $(SRC) $(FIRMWARE)/VehicleController.cpp $(HEADERS) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim-capture -Ishim $(filter %.cpp,$^) -o $@

$(BUILD)/motor_test: motor_test.cpp $(FIRMWARE)/Tb6612MotorDriver.cpp $(FIRMWARE)/Config.cpp $(HEADERS) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim $(filter %.cpp,$^) -o $@

$(BUILD)/command_test: command_test.cpp $(SRC) $(HEADERS) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim-capture -Ishim $(filter %.cpp,$^) -o $@

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

# Everything the app can ASK for, as opposed to everything the rover pushes:
# the parameter protocol, REQUEST_MESSAGE, SET_MESSAGE_INTERVAL, aux functions
# and the mode table. The parameter count is passed to the verifier so a table
# that grows cannot quietly stop being checked end to end.
$(BUILD)/link_test: link_test.cpp $(SRC) $(HEADERS) | $(BUILD)
	$(CXX) $(CXXFLAGS) -Ishim-capture -Ishim $(filter %.cpp,$^) -o $@

# Where replies are addressed, and what happens when none of them can be
# delivered. Reuses the command fixture rather than minting its own: any valid
# inbound frame provokes a reply, and a reply is all this suite needs.
link: $(BUILD)/link_test
	$(PYTHON) command_fixture.py $(BUILD)
	$(BUILD)/link_test $(BUILD)

commands: $(BUILD)/command_test
	$(PYTHON) command_fixture.py $(BUILD)
	$(BUILD)/command_test $(BUILD) | tee $(BUILD)/command_test.log
	$(PYTHON) command_verify.py $(BUILD) \
	  $$(sed -n 's/.*params=\([0-9]*\).*/\1/p' $(BUILD)/command_test.log)

check: wire session motors commands link

run: $(BUILD)/rover_host
	$(BUILD)/rover_host $(HOST) $(PORT)

clean:
	rm -rf $(BUILD)
