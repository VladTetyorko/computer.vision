"""Build the session-lifecycle frames with the reference implementation."""
import sys
from pymavlink.dialects.v20 import ardupilotmega as mav
from pymavlink import mavutil


class Sink:
    def __init__(self): self.buf = bytearray()
    def write(self, b): self.buf.extend(b)


def build(fn):
    sink = Sink()
    m = mav.MAVLink(sink, srcSystem=255, srcComponent=190)  # the app transmits as 255/190
    m.robust_parsing = True
    fn(m)
    return bytes(sink.buf)


IGN = 0xFFFF


def rc(ch1=IGN, ch3=IGN, ch5=IGN):
    return build(lambda m: m.rc_channels_override_send(1, 1, ch1, IGN, ch3, IGN, ch5, IGN, IGN, IGN))


FRAMES = {
    # sticks only -- ch5 IGNORE, which is what an app binding just steering/throttle sends
    'rc':     rc(ch1=1750, ch3=1750),
    'arm':    build(lambda m: m.command_long_send(
                  1, 1, mavutil.mavlink.MAV_CMD_COMPONENT_ARM_DISARM, 0, 1.0, 0, 0, 0, 0, 0, 0)),
    # the arm switch alone, sticks left unchanged, so a re-arm carries no fresh demand
    'ch5hi':  rc(ch5=1900),
    'ch5lo':  rc(ch5=1100),
    'ch5mid': rc(ch5=1500),   # inside the hysteresis band -- must change nothing
}

out = sys.argv[1]
for name, payload in FRAMES.items():
    open(f'{out}/{name}.bin', 'wb').write(payload)
print('session fixture: ' + ', '.join(f'{n}={len(p)}B' for n, p in FRAMES.items()))
