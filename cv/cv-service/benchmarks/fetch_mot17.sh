#!/usr/bin/env bash
# fetch_mot17.sh -- reproducible re-download of MOT17 TRAIN annotations
# (no images) from the Hugging Face mirror this benchmark was built
# against: https://huggingface.co/datasets/Lekim89/MOT17
#
# `cv-service/benchmarks/data/mot17/` already holds this data (16 MB,
# integrity-checked against published MOT17 specs -- every sequence's
# seqLength/imWidth/imHeight/frameRate matches). Running this script is
# NOT required for a normal benchmark run; it exists so the dataset can be
# reproduced from scratch (a fresh checkout, a wiped data/ dir, or a
# different mirror outage recovery) without re-discovering the mirror's own
# bug by hand a second time.
#
# **The bug this script exists to not reproduce.** This mirror's directory
# layout mirrors the official MOT17 distribution exactly (`seqinfo.ini` and
# `gt/gt.txt` are properties of the SCENE and were only ever published once
# per scene, conventionally alongside the -FRCNN detector's own directory
# -- see cv-service/benchmarks/mot17.py's module docstring for the same
# quirk from the READING side). Requesting `seqinfo.ini`/`gt/gt.txt` under
# a `-DPM`/`-SDP` URL does NOT 404 on this mirror -- it returns HTTP 200
# with a small HTML "Entry not found" error PAGE as the response body. A
# plain `curl -f` (fail on HTTP error status) does not catch this, because
# the status code is 200; only inspecting the body does. Writing that HTML
# page to `gt.txt` unfiltered is the exact bug already found and cleaned up
# by hand once -- `is_error_page` below is the permanent fix, applied to
# EVERY file this script fetches, not only the ones expected to need it,
# so a future change to the mirror's own layout cannot silently reintroduce
# it.
#
# Usage (from anywhere; paths are resolved relative to this script):
#     cv-service/benchmarks/fetch_mot17.sh
#
# Idempotent-ish: re-running overwrites whatever this script itself
# previously wrote. It does not touch any file it did not write.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/data/mot17"
BASE_URL="https://huggingface.co/datasets/Lekim89/MOT17/resolve/main/train"

SCENES=(02 04 05 09 10 11 13)
DETECTORS=(DPM FRCNN SDP)

# The mirror's own error-page marker (see the header comment above). Case
# insensitive: observed exact casing was "Entry not found", but a marker
# check that only matched that exact string would be one mirror wording
# change away from silently breaking again.
is_error_page() {
  grep -qi "entry not found" "$1" 2>/dev/null
}

fetch_one() {
  local url="$1" dest="$2"
  local tmp
  tmp="$(mktemp)"
  if ! curl -sSfL "$url" -o "$tmp"; then
    echo "skip: ${url#"$BASE_URL/"} (request failed -- does not exist on this mirror)" >&2
    rm -f "$tmp"
    return 0
  fi
  if is_error_page "$tmp"; then
    echo "skip: ${url#"$BASE_URL/"} (mirror served an 'Entry not found' page, not the real file)" >&2
    rm -f "$tmp"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  mv "$tmp" "$dest"
  echo "fetched: ${dest#"$DATA_DIR/"}"
}

for scene in "${SCENES[@]}"; do
  for detector in "${DETECTORS[@]}"; do
    seq="MOT17-${scene}-${detector}"
    # det.txt: every one of the 21 (scene, detector) directories has its
    # own real detections -- always attempted.
    fetch_one "${BASE_URL}/${seq}/det/det.txt" "${DATA_DIR}/${seq}/det/det.txt"
    # seqinfo.ini / gt/gt.txt: attempted for EVERY (scene, detector), not
    # only -FRCNN -- see the header comment on why this script does not
    # hard-code which directories are expected to succeed. In practice
    # today this succeeds for the 7 -FRCNN directories and is skipped
    # (error page detected) for the other 14.
    fetch_one "${BASE_URL}/${seq}/seqinfo.ini" "${DATA_DIR}/${seq}/seqinfo.ini"
    fetch_one "${BASE_URL}/${seq}/gt/gt.txt" "${DATA_DIR}/${seq}/gt/gt.txt"
  done
done

echo
echo "done. ${DATA_DIR} is covered by cv-service/.gitignore (CC BY-NC-SA license -- never commit it)."
echo "sanity check: cv-service/benchmarks/mot17.py's load_sequence() reads gt/seqinfo from the"
echo "-FRCNN sibling regardless of which detector you ask for -- confirm the 7 -FRCNN directories"
echo "above actually fetched (not skipped) before trusting a DPM/SDP composition."
