# infra

Fleet infrastructure that lives outside the Maven/Angular build: `sitl/` is a dev/CI/demo
fleet-in-a-box (`./infra/sitl/up.sh N` launches N real ArduPilot SITL aircraft, distinct
MAVLink sysids, pushing telemetry into this platform over UDP); `edge/` is a docs-and-configs
kit of three real-world link recipes (ELRS backpack, ESP32 bridge, RPi companion computer) for
getting an actual aircraft's telemetry — and, for the companion-computer recipe, video — onto
this platform in the field. Neither directory touches the Java/Python/Angular build; both are
standalone (`sitl/` needs only docker, `edge/` is configuration to copy onto real hardware).

Full context, sequencing, and the phases these implement (I-c and I-d) live in
[`docs/DRONE-INFRA-PLAN.md`](../docs/DRONE-INFRA-PLAN.md) — read that first for how this fits
alongside the multi-vehicle gateway (I-a), plug-and-fly provisioning (I-b), and guarded command
TX (I-e). Each subdirectory has its own README with prerequisites, quick-start, and what was
verified vs. researched.
