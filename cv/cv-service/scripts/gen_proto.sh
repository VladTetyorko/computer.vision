#!/usr/bin/env bash
# Regenerates the Python protobuf/gRPC stubs for cv-service from the shared
# proto contract at proto/vision/v1/cv.proto (single source of truth, also
# consumed by vision-proto on the Java side - see docs/plans/done/PHASE0-PLAN.md §4).
#
# Generated code is NOT committed (see cv/cv-service/.gitignore) - run this
# script after cloning and whenever proto/vision/v1/cv.proto changes.
#
# Usage: scripts/gen_proto.sh   (run from anywhere; paths are resolved
# relative to this script's location)
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
CV_SERVICE_DIR="$(cd -- "${SCRIPT_DIR}/.." &>/dev/null && pwd)"

# The proto/ directory is found by walking UP from this checkout rather than by a
# fixed number of "..", because cv-service sits at a different depth in each place
# it runs: cv/cv-service/ in this repo, /app/cv-service/ inside the image (its
# Dockerfile flattens the copy), ~/vision/cv-service/ on an rsync'd inference box.
PROTO_DIR=""
_dir="${CV_SERVICE_DIR}"
while [ "${_dir}" != "/" ]; do
  _dir="$(dirname -- "${_dir}")"
  if [ -f "${_dir}/proto/vision/v1/cv.proto" ]; then
    PROTO_DIR="${_dir}/proto"
    break
  fi
done
PROTO_DIR="${PROTO_DIR:-$(dirname -- "${CV_SERVICE_DIR}")/proto}"
OUT_DIR="${CV_SERVICE_DIR}/cv_service/gen"

if [ ! -f "${PROTO_DIR}/vision/v1/cv.proto" ]; then
  echo "error: ${PROTO_DIR}/vision/v1/cv.proto not found" >&2
  exit 1
fi

if ! python -c "import grpc_tools.protoc" >/dev/null 2>&1; then
  echo "error: grpc_tools (grpcio-tools) is not installed in this Python environment." >&2
  echo "       Install project deps first, e.g.: pip install -e ." >&2
  exit 1
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

python -m grpc_tools.protoc \
  -I "${PROTO_DIR}" \
  --python_out="${OUT_DIR}" \
  --grpc_python_out="${OUT_DIR}" \
  --pyi_out="${OUT_DIR}" \
  "${PROTO_DIR}/vision/v1/cv.proto"

# protoc mirrors the proto package path (vision/v1/...) under OUT_DIR; make
# every generated directory an importable regular package.
find "${OUT_DIR}" -type d -exec sh -c 'touch "${1}/__init__.py"' _ {} \;

echo "Generated Python stubs in ${OUT_DIR}"
