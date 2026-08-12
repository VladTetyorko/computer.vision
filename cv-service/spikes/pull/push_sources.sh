#!/usr/bin/env bash
# M0 spike -- publishes two synthetic H.264 sources (720p, 1080p; moving `testsrc` pattern,
# not a still one, so decode/inference cost isn't artificially cheap) into a mediamtx
# instance via RTSP, ~4 Mbps CBR-ish with a realistic 2s GOP (g=60 @ 30fps).
#
# Prereq: a mediamtx instance reachable at $MTX_RTSP_URL (default: the throwaway spike
# instance from mediamtx-spike.yml, NOT the product docker-compose one -- see
# CV-PULL-SPIKE.md's setup section for why a second instance was used: the product
# mediamtx already holds host port 8554).
#
#   docker run -d --rm --name mtx-spike \
#     -v "$(pwd)/results/mediamtx-spike.yml:/mediamtx.yml:ro" \
#     -p 18554:8554 -p 9997:9997 -p 8000-8001:8000-8001/udp \
#     bluenviron/mediamtx:1.19.3
#
# Usage: ./push_sources.sh [rtsp-base, default rtsp://localhost:18554]
set -euo pipefail

MTX_RTSP_URL="${1:-rtsp://localhost:18554}"

ffmpeg -hide_banner -loglevel warning -re -f lavfi -i "testsrc=size=1280x720:rate=30" \
  -c:v libx264 -preset veryfast -profile:v baseline -pix_fmt yuv420p \
  -b:v 4M -maxrate 4M -bufsize 8M -g 60 -keyint_min 60 \
  -f rtsp -rtsp_transport tcp "${MTX_RTSP_URL}/push720" &
echo "720p publisher PID: $!"

ffmpeg -hide_banner -loglevel warning -re -f lavfi -i "testsrc=size=1920x1080:rate=30" \
  -c:v libx264 -preset veryfast -profile:v baseline -pix_fmt yuv420p \
  -b:v 4M -maxrate 4M -bufsize 8M -g 60 -keyint_min 60 \
  -f rtsp -rtsp_transport tcp "${MTX_RTSP_URL}/push1080" &
echo "1080p publisher PID: $!"

wait
