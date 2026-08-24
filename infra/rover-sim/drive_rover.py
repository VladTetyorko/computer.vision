"""Engage manual control over the real WebSocket and push the sticks."""
import json, sys, time
import websocket

asset = sys.argv[1]
port = sys.argv[2] if len(sys.argv) > 2 else "8081"

ws = websocket.create_connection(f"ws://localhost:{port}/ws/manual-control", timeout=5)
ws.send(json.dumps({"type": "engage", "assetId": asset}))
engaged = ws.recv()
print("engage ->", engaged)

# The server picks the stick layout from the vehicle's own HEARTBEAT type and echoes it back, so a
# rover's map has only CH1/CH3 in it and its throttle centres at stop (docs/plans/active/
# VEHICLE-CONTROL-PROFILES-CONTEXT.md). Print it: the wrong profile here means the platform did not
# recognise the heartbeat, and the sticks below will not do what this script's comments claim.
try:
    frame = json.loads(engaged)
    print("profile ->", frame.get("vehicleKind"), frame.get("profileName"),
          [b.get("label") + "/CH" + str(b.get("rcChannel")) + "/" + b.get("travel")
           for b in frame.get("channelMap", [])])
except Exception as e:
    print("profile ->", e)

seq = 0
deadline = time.time() + 6.0
# axes[0] -> RC1 (steering, centred: 0 = straight), axes[2] -> RC3 (throttle, centred on a rover:
# 0 = stop, negative = reverse, positive = forward)
while time.time() < deadline:
    ws.send(json.dumps({"type": "channels", "axes": [0.6, 0.0, 0.4, 0.0],
                        "buttons": [], "seq": seq, "tSent": int(time.time() * 1000)}))
    seq += 1
    try:
        ws.settimeout(0.01)
        ws.recv()
    except Exception:
        pass
    time.sleep(1 / 30)

print(f"sent {seq} channel frames")
ws.settimeout(5)
ws.send(json.dumps({"type": "release"}))
try:
    print("release ->", ws.recv())
except Exception as e:
    print("release ->", e)
ws.close()
