# infra

Fleet infrastructure that lives outside the Maven/Angular build: `sitl/` is a dev/CI/demo
fleet-in-a-box (`./infra/sitl/up.sh N` launches N real ArduPilot SITL aircraft, distinct
MAVLink sysids, pushing telemetry into this platform over UDP); `edge/` is a docs-and-configs
kit of three real-world link recipes (ELRS backpack, ESP32 bridge, RPi companion computer) for
getting an actual aircraft's telemetry — and, for the companion-computer recipe, video — onto
this platform in the field. `rover-sim/` runs the ESP32 rover firmware (`~/Arduino/ardupoilot-start`)
as a host process, so the add-an-asset-and-drive-it flow can be exercised — and the firmware's wire
format regression-tested against pymavlink — with no board plugged in. None of these directories
touches the Java/Python/Angular build; all are standalone (`sitl/` needs only docker, `edge/` is
configuration to copy onto real hardware, `rover-sim/` needs a C++ compiler).

Full context, sequencing, and the phases these implement (I-c and I-d) live in
[`docs/plans/active/DRONE-INFRA-PLAN.md`](../docs/plans/active/DRONE-INFRA-PLAN.md) — read that first for how this fits
alongside the multi-vehicle gateway (I-a), plug-and-fly provisioning (I-b), and guarded command
TX (I-e). Each subdirectory has its own README with prerequisites, quick-start, and what was
verified vs. researched.
