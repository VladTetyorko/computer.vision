"""Decode the firmware's answers to the command surface, with pymavlink.

robust_parsing=False, so a wrong CRC_EXTRA seed, a wrong payload length or a
field at the wrong offset fails the run instead of being skipped -- which is
the whole point of measuring against the reference implementation rather than
against the firmware's own idea of what it sent.
"""
import sys
from pymavlink.dialects.v20 import ardupilotmega as mav
from pymavlink import mavutil

M = mavutil.mavlink

data = open(sys.argv[1] + "/cmd_frames.bin", "rb").read()
m = mav.MAVLink(None)
m.robust_parsing = False
msgs = m.parse_buffer(data) or []

failures = []
def check(cond, label):
    print(("  ok   " if cond else "  FAIL ") + label)
    if not cond: failures.append(label)

def of(kind):
    return [x for x in msgs if x.get_type() == kind]

acks   = of("COMMAND_ACK")
params = of("PARAM_VALUE")
texts  = of("STATUSTEXT")
print(f"decoded {len(msgs)} messages: {len(acks)} acks, {len(params)} param values, "
      f"{len(texts)} status texts\n")

# -- every command is answered ----------------------------------------------
print("-- every command gets an answer --")
def ack_for(command):
    return [a for a in acks if a.command == command]

def ack_results(command):
    return [a.result for a in ack_for(command)]

check(len(acks) == 10, f"all ten COMMAND_LONGs were acked (got {len(acks)})")

av = of("AUTOPILOT_VERSION")
print("\n-- MAV_CMD_REQUEST_MESSAGE --")
req = ack_results(M.MAV_CMD_REQUEST_MESSAGE)
check(req[:1] == [M.MAV_RESULT_ACCEPTED], f"AUTOPILOT_VERSION accepted (got {req[:1]})")
check(req[1:2] == [M.MAV_RESULT_UNSUPPORTED],
      f"VFR_HUD refused before any telemetry exists (got {req[1:2]})")
check(req[2:3] == [M.MAV_RESULT_ACCEPTED],
      f"VFR_HUD accepted once a snapshot exists (got {req[2:3]})")
# Position by identity: pymavlink messages compare by value, so list.index()
# would return the first EQUAL message rather than this one.
position = {id(x): i for i, x in enumerate(msgs)}

def index_of(msg):
    return position[id(msg)]

granted = [a for a in ack_for(M.MAV_CMD_REQUEST_MESSAGE) if a.result == M.MAV_RESULT_ACCEPTED]
vfr_after = [v for v in of("VFR_HUD") if granted and index_of(v) > index_of(granted[-1])]
check(len(vfr_after) == 1,
      "...and the VFR_HUD came AFTER its ack -- ArduPilot's ordering, so a ground "
      f"station cannot tell this rover apart by it (got {len(vfr_after)})")
if av:
    check(index_of(av[0]) > index_of(ack_for(M.MAV_CMD_REQUEST_MESSAGE)[0]),
          "AUTOPILOT_VERSION likewise follows its own ack")

print("\n-- AUTOPILOT_VERSION --")
check(len(av) == 1, f"exactly one AUTOPILOT_VERSION (got {len(av)})")
if av:
    v = av[0]
    packed = v.flight_sw_version
    major, minor, patch = (packed >> 24) & 0xFF, (packed >> 16) & 0xFF, (packed >> 8) & 0xFF
    check((major, minor, patch) == (1, 1, 0),
          f"flight_sw_version decodes as 1.1.0 (got {major}.{minor}.{patch})")
    check(packed & 0xFF == 128, f"maturity byte is BETA/128 (got {packed & 0xFF})")
    # The app decodes the low 32 bits into named flags and shows them on the
    # vehicle profile, so a bit set here that no handler backs is a lie.
    check(v.capabilities & M.MAV_PROTOCOL_CAPABILITY_PARAM_FLOAT != 0,
          "capabilities advertises PARAM_FLOAT, which the parameter handlers back")
    check(v.capabilities & M.MAV_PROTOCOL_CAPABILITY_MAVLINK2 != 0,
          "capabilities advertises MAVLINK2")
    missions = (M.MAV_PROTOCOL_CAPABILITY_MISSION_FLOAT
                | M.MAV_PROTOCOL_CAPABILITY_MISSION_INT
                | M.MAV_PROTOCOL_CAPABILITY_COMMAND_INT)
    check(v.capabilities & missions == 0,
          "capabilities claims NO mission or COMMAND_INT support, none of which is implemented")
    check(v.capabilities == (M.MAV_PROTOCOL_CAPABILITY_PARAM_FLOAT
                             | M.MAV_PROTOCOL_CAPABILITY_MAVLINK2),
          f"...and claims nothing else at all (got {v.capabilities})")

print("\n-- MAV_CMD_SET_MESSAGE_INTERVAL --")
interval = ack_results(M.MAV_CMD_SET_MESSAGE_INTERVAL)
check(interval == [M.MAV_RESULT_ACCEPTED, M.MAV_RESULT_UNSUPPORTED],
      f"a stream we push is accepted, one we do not is refused (got {interval})")

print("\n-- the mode table (RTL stays refused: no GPS) --")
modes = ack_results(M.MAV_CMD_DO_SET_MODE)
check(modes == [M.MAV_RESULT_UNSUPPORTED, M.MAV_RESULT_ACCEPTED],
      f"RTL refused, HOLD accepted (got {modes})")

print("\n-- MAV_CMD_DO_AUX_FUNCTION --")
auxes = ack_results(M.MAV_CMD_DO_AUX_FUNCTION)
check(auxes == [M.MAV_RESULT_UNSUPPORTED, M.MAV_RESULT_ACCEPTED, M.MAV_RESULT_UNSUPPORTED],
      f"RTL switch refused, arm switch accepted, unknown refused (got {auxes})")

print("\n-- the parameter protocol --")
by_name = {}
for p in params:
    by_name.setdefault(p.param_id, []).append(p)

check("RVR_ACCEL" in by_name, "a read by NAME was answered")
check(any(p.param_id == "MAV_SYSID" for p in params), "a read by INDEX 0 was answered")
check("FRAME_CLASS" not in by_name,
      "a read for a parameter we do not have is met with silence, per the protocol")

# A refused write still replies -- with the UNCHANGED value. That is the only
# mechanism MAVLink gives a vehicle for saying no, and staying silent would
# look like a lost packet and be retried forever.
sysid = by_name.get("MAV_SYSID", [])
check(any(p.param_value == 1 for p in sysid),
      "the read-only MAV_SYSID replied with its unchanged value, not silence")

thr = by_name.get("RVR_MAX_THR", [])
check(thr and thr[0].param_value == 0.5, f"RVR_MAX_THR came back as 0.5 (got {[p.param_value for p in thr]})")
steer = by_name.get("RVR_MAX_STR", [])
check(steer and steer[0].param_value == 1.0,
      f"RVR_MAX_STR was clamped to 1.0 and the CLAMPED value returned (got {[p.param_value for p in steer]})")
coast = by_name.get("RVR_REV_COAST", [])
check(coast and coast[0].param_value == 300, f"RVR_REV_COAST came back as 300 (got {[p.param_value for p in coast]})")
check(coast and coast[0].param_type == M.MAV_PARAM_TYPE_UINT16,
      "...declared as UINT16, not as a float")

print("\n-- PARAM_REQUEST_LIST --")
total = int(sys.argv[2])
listed = {p.param_id for p in params}
check(len(listed) == total,
      f"every one of the {total} parameters appeared (got {len(listed)})")
counts = {p.param_count for p in params}
check(counts == {total}, f"every PARAM_VALUE reports param_count={total} (got {counts})")
indices = sorted({p.param_index for p in params})
check(indices == list(range(total)),
      f"param_index covers 0..{total - 1} with no gaps or repeats")

print("\n-- STATUSTEXT --")
said = [t.text for t in texts]
check(len(texts) > 0, "the rover reported events to the operator")
check(any("RTL" in s for s in said),
      f"the refused RTL was explained in text, not just in a result code (said {said})")
check(all(t.severity <= M.MAV_SEVERITY_DEBUG for t in texts), "every severity is a valid MAV_SEVERITY")

# -- command idempotency: the same COMMAND_LONG, a rising confirmation ------
# D2a lets the platform retry an absolute-state command (COMPONENT_ARM_DISARM,
# DO_SET_MODE) up to twice. The firmware must answer every attempt -- an
# unacked retry is what makes the app give up and call the vehicle
# unresponsive -- but the STATE it produces must be the same as sending the
# command once: an "armed" notice for every one of three ARM attempts would
# mean the retry budget is quietly tripling every side effect, not just
# covering for a lost packet.
print("\n-- command idempotency: rising confirmation --")
idem_data = open(sys.argv[1] + "/idem_frames.bin", "rb").read()
idem_decoder = mav.MAVLink(None)
idem_decoder.robust_parsing = False
idem_msgs = idem_decoder.parse_buffer(idem_data) or []


def iof(kind):
    return [x for x in idem_msgs if x.get_type() == kind]


idem_acks = iof("COMMAND_ACK")
idem_texts = iof("STATUSTEXT")

arm_acks = [a for a in idem_acks if a.command == M.MAV_CMD_COMPONENT_ARM_DISARM]
mode_acks = [a for a in idem_acks if a.command == M.MAV_CMD_DO_SET_MODE]

check(len(arm_acks) == 3,
      f"COMPONENT_ARM_DISARM re-sent 3x (confirmation 0,1,2) got 3 acks (got {len(arm_acks)})")
check(all(a.result == M.MAV_RESULT_ACCEPTED for a in arm_acks),
      f"...every attempt ACCEPTED, not just the first (got {[a.result for a in arm_acks]})")

check(len(mode_acks) == 3,
      f"DO_SET_MODE re-sent 3x (confirmation 0,1,2) got 3 acks (got {len(mode_acks)})")
check(all(a.result == M.MAV_RESULT_ACCEPTED for a in mode_acks),
      f"...every attempt ACCEPTED, not just the first (got {[a.result for a in mode_acks]})")

# "armed" is exact-matched with startswith, not `in`, because "disarmed"
# contains "armed" as a substring -- a naive `in` check would count the wrong
# notices as evidence of idempotency.
armed_notices = [t for t in idem_texts if t.text.startswith("armed")]
check(len(armed_notices) == 1,
      "a repeated ARM changes state ONCE -- exactly one 'armed' notice for "
      f"3 attempts (got {len(armed_notices)}: {[t.text for t in armed_notices]})")

print("\n-- STATUSTEXT (idempotency block) --")
check(all(t.severity <= M.MAV_SEVERITY_DEBUG for t in idem_texts), "every severity is a valid MAV_SEVERITY")

print()
print("ALL COMMAND CHECKS PASSED" if not failures else f"{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
