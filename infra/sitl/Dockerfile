# infra/sitl/Dockerfile
#
# Runs the OFFICIAL, prebuilt ArduCopter SITL binary published by the
# ArduPilot project — nothing here is built from ArduPilot source.
#
# Provenance (verified 2026-07-28, `curl -I` 200 OK):
#   https://firmware.ardupilot.org/Copter/stable-4.7.0/SITL_x86_64_linux_gnu/arducopter
# That directory's git-version.txt attributes the build to ArduPilot/ardupilot
# commit 1511f27 ("Plane: version to 4.7.0" release-tag rollup) — the
# project's own release server, not a third-party rehost or mirror. Pinned to
# the numbered "stable-4.7.0" path (not the floating "stable" alias) so this
# image never silently changes under a future release. Bumping the version
# requires re-running the `curl -I` check and updating both the ARG default
# below and this comment's date.
FROM python:3.12-slim

ARG ARDUPILOT_VERSION=stable-4.7.0
ARG ARDUPILOT_FIRMWARE_URL=https://firmware.ardupilot.org/Copter/${ARDUPILOT_VERSION}/SITL_x86_64_linux_gnu/arducopter

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

RUN curl -fsSL -o arducopter "$ARDUPILOT_FIRMWARE_URL" \
    && chmod +x arducopter

COPY entrypoint.sh autofly.py /sitl/
RUN chmod +x /sitl/entrypoint.sh

ENTRYPOINT ["/sitl/entrypoint.sh"]
