#!/usr/bin/env bash
# Runs the cv-service pytest suite.
#
# Usage: scripts/test.sh [pytest args...]
#
# Requires: generated stubs (scripts/gen_proto.sh) + dev deps
# (pip install -e '.[dev]'). tests/test_inference.py and tests/test_server.py
# are mock-based and need only opencv-python-headless/numpy (part of the
# `cv` extra, not ultralytics/torch) to import cv_service.inference.
# tests/test_real_model.py is skipped automatically unless `ultralytics` is
# importable and its default weights can be loaded (installed via
# pip install -e '.[cv]').
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
CV_SERVICE_DIR="$(cd -- "${SCRIPT_DIR}/.." &>/dev/null && pwd)"

cd "${CV_SERVICE_DIR}"

if [ ! -d cv_service/gen ]; then
  echo "error: cv_service/gen not found. Run scripts/gen_proto.sh first." >&2
  exit 1
fi

python -m pytest "$@"
