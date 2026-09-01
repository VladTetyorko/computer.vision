"""Build the command-surface RX fixtures with the reference implementation.

Two files, because one of the things under test is what the firmware does
BEFORE it has any telemetry to report: `a` runs against a cold link, `b` after
the controller's first tick.
"""
import sys
from pymavlink.dialects.v20 import ardupilotmega as mav
from pymavlink import mavutil

M = mavutil.mavlink


class Sink:
    def __init__(self): self.buf = bytearray()
    def write(self, b): self.buf.extend(b)


def link():
    sink = Sink()
    # The app transmits as 255/190, addressed to the vehicle's 1/1.
    m = mav.MAVLink(sink, srcSystem=255, srcComponent=190)
    m.robust_parsing = True
    return sink, m


def cmd(m, command, p1=0.0, p2=0.0):
    m.command_long_send(1, 1, command, 0, p1, p2, 0, 0, 0, 0, 0)


def cmd_confirm(m, command, confirmation, p1=0.0, p2=0.0):
    """Same as cmd(), but with an explicit confirmation -- for the retry case
    below, where the app resends the SAME command with a rising confirmation
    rather than confirmation=0 every time."""
    m.command_long_send(1, 1, command, confirmation, p1, p2, 0, 0, 0, 0, 0)


def name(text):
    """param_id is a fixed 16-byte field; pymavlink pads it for us."""
    return text.encode()


sink, m = link()

# -- parameter reads --------------------------------------------------------
# -1 in param_index means "the name is authoritative".
m.param_request_read_send(1, 1, name("RVR_ACCEL"), -1)
m.param_request_read_send(1, 1, name(""), 0)          # by index: the first entry
m.param_request_read_send(1, 1, name("FRAME_CLASS"), -1)   # we have no such parameter

# -- parameter writes -------------------------------------------------------
m.param_set_send(1, 1, name("RVR_MAX_THR"), 0.5, M.MAV_PARAM_TYPE_REAL32)
m.param_set_send(1, 1, name("RVR_REV_COAST"), 300.0, M.MAV_PARAM_TYPE_UINT16)
m.param_set_send(1, 1, name("RVR_MAX_STR"), 5.0, M.MAV_PARAM_TYPE_REAL32)   # out of range
m.param_set_send(1, 1, name("MAV_SYSID"), 99.0, M.MAV_PARAM_TYPE_UINT8)     # read-only

# -- commands ---------------------------------------------------------------
cmd(m, M.MAV_CMD_REQUEST_MESSAGE, 148)      # AUTOPILOT_VERSION -- needs no telemetry
cmd(m, M.MAV_CMD_REQUEST_MESSAGE, 74)       # VFR_HUD -- nothing to report yet
cmd(m, M.MAV_CMD_SET_MESSAGE_INTERVAL, 30, 200000)   # ATTITUDE at 5 Hz
cmd(m, M.MAV_CMD_SET_MESSAGE_INTERVAL, 999, 200000)  # not a stream we push
cmd(m, M.MAV_CMD_DO_SET_MODE, 1, 11)        # RTL -- must be refused
cmd(m, M.MAV_CMD_DO_SET_MODE, 1, 4)         # HOLD -- honoured
cmd(m, M.MAV_CMD_DO_AUX_FUNCTION, 4, 2)     # RTL switch -- refused, same as the mode
cmd(m, M.MAV_CMD_DO_AUX_FUNCTION, 153, 2)   # arm/disarm switch, high
cmd(m, M.MAV_CMD_DO_AUX_FUNCTION, 999, 2)   # not a function we implement

open(sys.argv[1] + "/cmd_a.bin", "wb").write(bytes(sink.buf))

# -- after the first telemetry tick -----------------------------------------
sink, m = link()
cmd(m, M.MAV_CMD_REQUEST_MESSAGE, 74)       # VFR_HUD -- now answerable
m.param_request_list_send(1, 1)
open(sys.argv[1] + "/cmd_b.bin", "wb").write(bytes(sink.buf))

# -- idempotency: the same COMMAND_LONG re-sent with a rising confirmation --
# The platform may now retry up to twice (D2a), which means the firmware sees
# the SAME absolute-state command up to three times with confirmation 0, 1, 2.
# It must be handled idempotently: the state change lands once, but every
# attempt still gets its own COMMAND_ACK. One file per attempt, so the host
# harness can feed and poll them one at a time and observe the state after
# each -- a single fixture with all six frames concatenated would let the
# firmware answer all of them within one poll() and hide exactly the ordering
# this case exists to check.
for i, confirmation in enumerate((0, 1, 2)):
    sink, m = link()
    cmd_confirm(m, M.MAV_CMD_COMPONENT_ARM_DISARM, confirmation, 1, 0)   # arm
    open(sys.argv[1] + f"/cmd_idem_arm{i}.bin", "wb").write(bytes(sink.buf))

for i, confirmation in enumerate((0, 1, 2)):
    sink, m = link()
    cmd_confirm(m, M.MAV_CMD_DO_SET_MODE, confirmation, 1, 4)           # HOLD
    open(sys.argv[1] + f"/cmd_idem_mode{i}.bin", "wb").write(bytes(sink.buf))

print(f"command fixtures written to {sys.argv[1]}")
