"""Decode the firmware's real transmitted bytes with the reference implementation."""
import sys
from pymavlink.dialects.v20 import ardupilotmega as mav

data = open(sys.argv[1], 'rb').read()
m = mav.MAVLink(None)
m.robust_parsing = False   # any CRC/length error must raise, not be skipped

msgs = m.parse_buffer(data) or []
print(f"decoded {len(msgs)} messages from {len(data)} bytes\n")

seen, failures = {}, []
def check(cond, label):
    print(("  PASS  " if cond else "  FAIL  ") + label)
    if not cond: failures.append(label)

for msg in msgs:
    name = msg.get_type()
    seen[name] = msg
    print(f"{name}  (seq={msg.get_seq()} sys={msg.get_srcSystem()} comp={msg.get_srcComponent()})")

print()
expected = {'HEARTBEAT','SYS_STATUS','GPS_RAW_INT','ATTITUDE','GLOBAL_POSITION_INT',
            'RC_CHANNELS','VFR_HUD','COMMAND_ACK'}
check(expected <= set(seen), f"all 8 message types present (missing: {expected - set(seen)})")
check(all(m2.get_srcSystem() == 1 and m2.get_srcComponent() == 1
          for m2 in msgs), "every frame is from sysid 1 / compid 1")
check(sorted(m2.get_seq() for m2 in msgs) == list(range(len(msgs))),
      "sequence numbers are monotonic 0..n-1")

hb = seen.get('HEARTBEAT')
if hb:
    check(hb.autopilot == 3, f"HEARTBEAT.autopilot == 3 ARDUPILOTMEGA (got {hb.autopilot})")
    check(hb.type == 10, f"HEARTBEAT.type == 10 GROUND_ROVER (got {hb.type})")
    check(hb.base_mode & 0x80 != 0, f"HEARTBEAT armed bit set (base_mode={hb.base_mode})")
    check(hb.base_mode & 0x01 != 0, "HEARTBEAT custom-mode-enabled bit set")
    check(hb.system_status == 4, f"HEARTBEAT.system_status == 4 ACTIVE (got {hb.system_status})")
    check(hb.mavlink_version == 3, f"HEARTBEAT.mavlink_version == 3 (got {hb.mavlink_version})")

ss = seen.get('SYS_STATUS')
if ss:
    check(ss.battery_remaining == -1, f"SYS_STATUS.battery_remaining == -1 unknown (got {ss.battery_remaining})")
    check(ss.voltage_battery == 65535, f"SYS_STATUS.voltage_battery == 65535 unknown (got {ss.voltage_battery})")

gps = seen.get('GPS_RAW_INT')
if gps:
    check(gps.fix_type == 0, f"GPS_RAW_INT.fix_type == 0 no-gps (got {gps.fix_type})")
    check(gps.satellites_visible == 255, f"GPS_RAW_INT.satellites_visible == 255 (got {gps.satellites_visible})")
    check(gps.eph == 65535, f"GPS_RAW_INT.eph == 65535 (got {gps.eph})")
    check(gps.time_usec == 100000 * 1000, f"GPS_RAW_INT.time_usec == uptime us (got {gps.time_usec})")

att = seen.get('ATTITUDE')
if att:
    check(att.time_boot_ms == 100000, f"ATTITUDE.time_boot_ms == 100000 (got {att.time_boot_ms})")
    check(att.roll == 0.0 and att.pitch == 0.0 and att.yaw == 0.0, "ATTITUDE zeroed with no IMU fitted")

gp = seen.get('GLOBAL_POSITION_INT')
if gp:
    check(gp.hdg == 65535, f"GLOBAL_POSITION_INT.hdg == 65535 unknown (got {gp.hdg})")
    check(gp.time_boot_ms == 100000, f"GLOBAL_POSITION_INT.time_boot_ms == 100000 (got {gp.time_boot_ms})")
    check(gp.lat == 0 and gp.lon == 0, "GLOBAL_POSITION_INT position stays 0 with no GPS")

rc = seen.get('RC_CHANNELS')
if rc:
    # appliedSteering -0.25 -> 1500 - 0.25*500 = 1375; appliedThrottle 0.50 -> 1750
    check(rc.chan1_raw == 1375, f"RC_CHANNELS.chan1_raw echoes applied steering -0.25 as 1375 (got {rc.chan1_raw})")
    check(rc.chan3_raw == 1750, f"RC_CHANNELS.chan3_raw echoes applied throttle +0.50 as 1750 (got {rc.chan3_raw})")
    check(rc.chan2_raw == 65535, f"RC_CHANNELS undriven channels are 65535 (got {rc.chan2_raw})")
    check(rc.chancount == 8, f"RC_CHANNELS.chancount == 8 (got {rc.chancount})")
    # -60 dBm on the -100..-50 scale -> 40/50 * 254 = 203
    check(rc.rssi == 203, f"RC_CHANNELS.rssi maps -60 dBm to 203/254 (got {rc.rssi})")

vfr = seen.get('VFR_HUD')
if vfr:
    check(abs(vfr.groundspeed - 0.75) < 1e-6, f"VFR_HUD.groundspeed == 0.75 m/s (got {vfr.groundspeed})")
    check(vfr.throttle == 50, f"VFR_HUD.throttle == 50 percent (got {vfr.throttle})")

ack = seen.get('COMMAND_ACK')
if ack:
    check(ack.command == 400, f"COMMAND_ACK.command == 400 ARM_DISARM (got {ack.command})")
    check(ack.result == 0, f"COMMAND_ACK.result == 0 ACCEPTED (got {ack.result})")

print()
print("ALL CHECKS PASSED" if not failures else f"{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
