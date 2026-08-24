"""Build the RX fixture with the reference implementation."""
import sys
from pymavlink.dialects.v20 import ardupilotmega as mav
from pymavlink import mavutil

class Sink:
    def __init__(self): self.buf = bytearray()
    def write(self, b): self.buf.extend(b)

sink = Sink()
# The app transmits as 255/190.
m = mav.MAVLink(sink, srcSystem=255, srcComponent=190)
m.robust_parsing = True

# Half-right steering on RC1, half-forward throttle on RC3, addressed to 1/1.
IGN = 0xFFFF
m.rc_channels_override_send(1, 1, 1750, IGN, 1750, IGN, IGN, IGN, IGN, IGN)
# ARM
m.command_long_send(1, 1, mavutil.mavlink.MAV_CMD_COMPONENT_ARM_DISARM, 0,
                    1.0, 0, 0, 0, 0, 0, 0)
open(sys.argv[1], 'wb').write(bytes(sink.buf))
print(f"fixture: {len(sink.buf)} bytes, {sink.buf[:1].hex()} magic")
