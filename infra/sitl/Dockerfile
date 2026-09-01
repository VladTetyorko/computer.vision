# infra/sitl/Dockerfile
#
# Runs the OFFICIAL, prebuilt ArduPilot SITL binaries published by the
# ArduPilot project — nothing here is built from ArduPilot source.
#
# Provenance (arducopter verified 2026-07-28, ardurover verified 2026-08-27,
# both `curl -I` 200 OK):
#   https://firmware.ardupilot.org/Copter/stable-4.7.0/SITL_x86_64_linux_gnu/arducopter
#   https://firmware.ardupilot.org/Rover/stable-4.7.0/SITL_x86_64_linux_gnu/ardurover
# That directory's git-version.txt attributes the build to ArduPilot/ardupilot
# commit 1511f27 ("Plane: version to 4.7.0" release-tag rollup) — the
# project's own release server, not a third-party rehost or mirror. Pinned to
# the numbered "stable-4.7.0" path (not the floating "stable" alias) so this
# image never silently changes under a future release. Bumping the version
# requires re-running the `curl -I` check and updating both the ARG default
# below and this comment's date.
#
# BOTH vehicles ship in ONE image, selected at run time by the VEHICLE env var
# (docs/plans/active/FLEET-RADIO-PLAN.md R7), rather than one image per vehicle
# behind a build ARG. The rover binary is ~7 MB against a base image two orders
# of magnitude larger, and up.sh fans a fleet out of a single build — a second
# image would mean a second build, a second tag to keep in step, and no way to
# run a mixed copter+rover fleet without both. The platform's whole reason for
# wanting a rover is that a real fleet is not all one kind of vehicle.
FROM python:3.12-slim

ARG ARDUPILOT_VERSION=stable-4.7.0
ARG ARDUPILOT_COPTER_URL=https://firmware.ardupilot.org/Copter/${ARDUPILOT_VERSION}/SITL_x86_64_linux_gnu/arducopter
ARG ARDUPILOT_ROVER_URL=https://firmware.ardupilot.org/Rover/${ARDUPILOT_VERSION}/SITL_x86_64_linux_gnu/ardurover

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# pymavlink: the ArduPilot-project-maintained Python MAVLink library (PyPI,
# ships manylinux wheels for this platform -- no compiler, no ArduPilot
# source checkout). Used only by autofly.py, an internal test-harness "GCS"
# that arms/takes off/circles the SITL vehicle over its own local control
# port -- entirely separate from the udpclient link entrypoint.sh points at
# the vision platform (see below); the platform never sees this traffic.
RUN pip install --no-cache-dir pymavlink==2.4.49

WORKDIR /sitl

RUN curl -fsSL -o arducopter "$ARDUPILOT_COPTER_URL" \
    && curl -fsSL -o ardurover "$ARDUPILOT_ROVER_URL" \
    && chmod +x arducopter ardurover

COPY entrypoint.sh autofly.py /sitl/
RUN chmod +x /sitl/entrypoint.sh

ENTRYPOINT ["/sitl/entrypoint.sh"]
