#!/usr/bin/env python3
"""infra/sitl/autofly.py

Internal test-harness "GCS": connects to the SITL vehicle's own local
control port (never touches the udpclient link entrypoint.sh points at the
vision platform), arms it, takes off in GUIDED mode, then switches to
CIRCLE so it flies a small default circuit near its home location -- the
fleet-in-a-box requirement of "flying a small default circuit near a
configurable home location" (docs/plans/active/DRONE-INFRA-PLAN.md I-c).

Failure here is non-fatal by design (see entrypoint.sh's `|| echo ...`)
-- worst case the vehicle sits armed or disarmed on the ground, but SITL
keeps pushing telemetry to the platform either way.
"""
import argparse
import sys
import time

from pymavlink import mavutil

CONNECT_TIMEOUT_S = 60
HEARTBEAT_TIMEOUT_S = 30
MODE_CHANGE_TIMEOUT_S = 15
ARM_TIMEOUT_S = 45
ARM_RETRY_INTERVAL_S = 3
EKF_SETTLE_S = 15
ALT_REACHED_FRACTION = 0.95


def connect(port: int):
    deadline = time.monotonic() + CONNECT_TIMEOUT_S
    last_err = None
    while time.monotonic() < deadline:
        try:
            conn = mavutil.mavlink_connection(f"tcp:127.0.0.1:{port}", source_system=255)
            conn.wait_heartbeat(timeout=HEARTBEAT_TIMEOUT_S)
            return conn
        except (ConnectionRefusedError, OSError) as err:
            last_err = err
            time.sleep(1)
    raise RuntimeError(f"could not reach local SITL control port {port}: {last_err}")


def set_mode(conn, mode_name: str) -> bool:
    mode_id = conn.mode_mapping()[mode_name]
    conn.mav.set_mode_send(
        conn.target_system,
        mavutil.mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
        mode_id,
    )
    deadline = time.monotonic() + MODE_CHANGE_TIMEOUT_S
    while time.monotonic() < deadline:
        msg = conn.recv_match(type="HEARTBEAT", blocking=True, timeout=2)
        if msg is not None and msg.custom_mode == mode_id:
            return True
    return False


def arm(conn) -> bool:
    # A single arm request can be legitimately NACKed while the EKF/GPS is
    # still settling (a transient "PreArm: ..." STATUSTEXT, not a permanent
    # one) -- ArduPilot does not retry a rejected command on its own, so this
    # resends every ARM_RETRY_INTERVAL_S until ARM_TIMEOUT_S runs out.
    # PreArm/Arm STATUSTEXT is printed as it arrives so `docker logs` shows
    # *why* if arming keeps failing, without needing to exec into the box.
    deadline = time.monotonic() + ARM_TIMEOUT_S
    next_attempt = 0.0
    while time.monotonic() < deadline:
        now = time.monotonic()
        if now >= next_attempt:
            conn.mav.command_long_send(
                conn.target_system,
                conn.target_component,
                mavutil.mavlink.MAV_CMD_COMPONENT_ARM_DISARM,
                0,
                1, 0, 0, 0, 0, 0, 0,
            )
            next_attempt = now + ARM_RETRY_INTERVAL_S
        msg = conn.recv_match(blocking=True, timeout=1)
        if msg is None:
            continue
        t = msg.get_type()
        if t == "STATUSTEXT" and ("PreArm" in msg.text or "Arm" in msg.text):
            print(f"[autofly] {msg.text}", flush=True)
        elif t == "HEARTBEAT" and bool(msg.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED):
            return True
    return False


def takeoff(conn, alt_m: float) -> bool:
    conn.mav.command_long_send(
        conn.target_system,
        conn.target_component,
        mavutil.mavlink.MAV_CMD_NAV_TAKEOFF,
        0,
        0, 0, 0, 0, 0, 0, alt_m,
    )
    deadline = time.monotonic() + max(45.0, alt_m * 3)
    target = alt_m * ALT_REACHED_FRACTION
    while time.monotonic() < deadline:
        msg = conn.recv_match(type="GLOBAL_POSITION_INT", blocking=True, timeout=2)
        if msg is not None and (msg.relative_alt / 1000.0) >= target:
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", type=int, default=0)
    parser.add_argument("--takeoff-alt", type=float, default=20.0)
    parser.add_argument("--port", type=int, default=None, help="override the local SITL control port")
    args = parser.parse_args()

    # SITL's own default local MAVLink port for serial1 is 5762, offset by
    # +10 per --instance/-I (ArduPilot's documented port-offset convention:
    # "-I N adds 10*instance to all port numbers"); serial0 is repurposed by
    # entrypoint.sh for the udpclient push to the platform, so serial1 is
    # this script's own private, never-externally-exposed control link.
    port = args.port if args.port is not None else 5762 + 10 * args.instance

    print(f"[autofly] connecting to local SITL control port {port}", flush=True)
    conn = connect(port)
    print("[autofly] heartbeat received, waiting for EKF/GPS to settle", flush=True)
    time.sleep(EKF_SETTLE_S)

    if not set_mode(conn, "GUIDED"):
        print("[autofly] failed to enter GUIDED mode, giving up", file=sys.stderr)
        return 1
    if not arm(conn):
        print("[autofly] failed to arm, giving up", file=sys.stderr)
        return 1
    print("[autofly] armed, taking off", flush=True)
    if not takeoff(conn, args.takeoff_alt):
        print("[autofly] takeoff did not reach target altitude in time, continuing anyway", file=sys.stderr)
    if not set_mode(conn, "CIRCLE"):
        print("[autofly] failed to enter CIRCLE mode", file=sys.stderr)
        return 1
    print("[autofly] flying default circuit in CIRCLE mode", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
