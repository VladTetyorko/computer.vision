"""cv-service/spikes/geo/analytical_circle_telemetry.py

Fallback telemetry generator for the SITL-simulated Wave-0 eval (docs/VISUAL-GEO-PLAN.md
§5 extension) -- used only because a *live* capture attempt against infra/sitl was made and
failed for a reason unrelated to networking, and is documented rather than silently patched
over. See infra/sitl/README.md's "Known issue: GCS stream requests" section for the full
diagnostic trail.

**This is NOT SITL output.** It is a closed-form ArduCopter CIRCLE-mode track: a real
ArduCopter instance was booted, armed, and confirmed flying CIRCLE mode around this exact
home point (docker logs showed "flying default circuit in CIRCLE mode") -- what's synthetic
here is only the position *trace*, computed from ArduCopter's own documented default flight
parameters for that mode, not the fact that it flies a circle at all. Every report this feeds
must carry a banner saying so.

ArduCopter CIRCLE mode defaults (stock, unchanged by infra/sitl/entrypoint.sh's own
--defaults param file -- verified against that file, neither CIRCLE_RADIUS nor CIRCLE_RATE is
set there): `CIRCLE_RADIUS` = 1000 (cm) = 10m, `CIRCLE_RATE` = 20 (deg/s). At those values one
full loop takes 360/20 = 18s and the vehicle's tangential speed is
`radius * angular_rate_rad_s` = 10 * (20*pi/180) ~= 3.49 m/s -- both reproduced below rather
than hand-picked.

Usage:
    python3 -m spikes.geo.analytical_circle_telemetry --out track.csv \
        --home-lat 50.4501 --home-lon 30.5238 --altitude-m 75 --duration-seconds 150

CSV schema matches infra/sitl/log_telemetry.py's real-capture output exactly (same header,
same units) so spikes/geo/sitl_render.py never needs to know which one produced its input.
"""
from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path
from typing import Optional

EARTH_RADIUS_M = 6_371_000.0
CIRCLE_RADIUS_M = 10.0
CIRCLE_RATE_DEG_S = 20.0
SAMPLE_INTERVAL_S = 0.5

# Realistic small-sensor jitter -- NOT zero, so a query frame rendered from consecutive samples
# isn't from bit-identical positions. 1-sigma, applied independently per sample.
POSITION_NOISE_M = 0.4
ALTITUDE_NOISE_M = 0.5
HEADING_NOISE_DEG = 1.5

CSV_HEADER = ["timestamp_ms", "lat", "lon", "alt_m", "heading_deg", "groundspeed_mps", "sysid"]


def _destination(lat: float, lon: float, bearing_deg: float, distance_m: float) -> tuple[float, float]:
    """Spherical destination point -- same formula as vision-domain's GeoProjection.destinationPoint."""
    lat_rad, lon_rad, brg_rad = math.radians(lat), math.radians(lon), math.radians(bearing_deg)
    ang = distance_m / EARTH_RADIUS_M
    lat2 = math.asin(math.sin(lat_rad) * math.cos(ang) + math.cos(lat_rad) * math.sin(ang) * math.cos(brg_rad))
    lon2 = lon_rad + math.atan2(
        math.sin(brg_rad) * math.sin(ang) * math.cos(lat_rad),
        math.cos(ang) - math.sin(lat_rad) * math.sin(lat2),
    )
    return math.degrees(lat2), math.degrees(lon2)


def generate_rows(
    home_lat: float, home_lon: float, altitude_m: float, duration_seconds: float,
    seed: int = 20260806,
) -> list[list[str]]:
    import random
    rng = random.Random(seed)

    angular_rate_rad_s = math.radians(CIRCLE_RATE_DEG_S)
    tangential_speed_mps = CIRCLE_RADIUS_M * angular_rate_rad_s

    # Circle center = home point projected CIRCLE_RADIUS_M north of the arm/launch point --
    # ArduCopter's own CIRCLE mode centers on the point it entered the mode from, but the exact
    # entry point relative to home is a launch-sequence detail this fallback doesn't need to
    # match; centering on home is the simplest defensible choice and keeps every sample within
    # the smoke test's already-fetched tile bbox.
    center_lat, center_lon = home_lat, home_lon

    rows: list[list[str]] = []
    t = 0.0
    while t <= duration_seconds:
        angle_deg = (CIRCLE_RATE_DEG_S * t) % 360.0
        lat, lon = _destination(center_lat, center_lon, angle_deg, CIRCLE_RADIUS_M)
        lat += rng.gauss(0, POSITION_NOISE_M) / 111_320.0
        lon += rng.gauss(0, POSITION_NOISE_M) / (111_320.0 * math.cos(math.radians(center_lat)))
        alt = altitude_m + rng.gauss(0, ALTITUDE_NOISE_M)
        # Tangential heading: direction of travel around the circle, +90deg from the outward
        # radius bearing for a clockwise loop (ArduCopter CIRCLE mode's default direction).
        heading = (angle_deg + 90.0 + rng.gauss(0, HEADING_NOISE_DEG)) % 360.0
        speed = max(0.0, tangential_speed_mps + rng.gauss(0, 0.15))
        rows.append([
            str(int(t * 1000)),
            f"{lat:.7f}", f"{lon:.7f}", f"{alt:.2f}", f"{heading:.2f}", f"{speed:.2f}", "1",
        ])
        t += SAMPLE_INTERVAL_S
    return rows


def generate_lawnmower_rows(
    center_lat: float, center_lon: float, altitude_m: float,
    width_m: float, height_m: float, leg_spacing_m: float, speed_mps: float,
    seed: int = 20260806,
) -> list[list[str]]:
    """A boustrophedon coverage pattern (back-and-forth legs, offset each turn) -- NOT what this
    SITL image's stock `autofly.py` flies (it only ever does GUIDED takeoff -> CIRCLE). This is a
    deliberately *different*, wider, more realistic stand-in for an actual survey/cross-country
    flight, built to answer a narrower question the tight stock circle can't: does recall improve
    once query frames stop being near-duplicate crops of one ~20m patch? Still fully honest about
    what it is -- see the CSV's own accompanying report banner, never conflated with a captured or
    even a stock-circle trace.

    Legs run east-west, offset north each pass, starting at the south-west corner of a
    `width_m` x `height_m` box centered on `(center_lat, center_lon)` -- plain flat-earth local
    tangent plane math (fine at this scale; ~1e-4 relative error over a few hundred meters is
    irrelevant next to the meters-scale positional-noise this function already injects).
    """
    import random
    rng = random.Random(seed)

    m_per_deg_lat = 111_320.0
    m_per_deg_lon = 111_320.0 * math.cos(math.radians(center_lat))

    n_legs = max(2, round(height_m / leg_spacing_m) + 1)
    sw_lat = center_lat - (height_m / 2) / m_per_deg_lat
    sw_lon = center_lon - (width_m / 2) / m_per_deg_lon

    # Waypoints: south-west corner, then alternating east/west across each leg, north-offset per leg.
    waypoints: list[tuple[float, float]] = []
    for leg in range(n_legs):
        leg_lat = sw_lat + (leg * leg_spacing_m) / m_per_deg_lat
        if leg % 2 == 0:
            waypoints.append((leg_lat, sw_lon))
            waypoints.append((leg_lat, sw_lon + width_m / m_per_deg_lon))
        else:
            waypoints.append((leg_lat, sw_lon + width_m / m_per_deg_lon))
            waypoints.append((leg_lat, sw_lon))

    rows: list[list[str]] = []
    t = 0.0
    for i in range(len(waypoints) - 1):
        lat1, lon1 = waypoints[i]
        lat2, lon2 = waypoints[i + 1]
        dy = (lat2 - lat1) * m_per_deg_lat
        dx = (lon2 - lon1) * m_per_deg_lon
        leg_len_m = math.hypot(dx, dy)
        heading = (math.degrees(math.atan2(dx, dy)) + 360) % 360
        leg_duration = leg_len_m / speed_mps if speed_mps > 0 else 0.0
        n_samples = max(1, round(leg_duration / SAMPLE_INTERVAL_S))
        for s in range(n_samples):
            frac = s / n_samples
            lat = lat1 + (lat2 - lat1) * frac + rng.gauss(0, POSITION_NOISE_M) / m_per_deg_lat
            lon = lon1 + (lon2 - lon1) * frac + rng.gauss(0, POSITION_NOISE_M) / m_per_deg_lon
            alt = altitude_m + rng.gauss(0, ALTITUDE_NOISE_M)
            hdg = (heading + rng.gauss(0, HEADING_NOISE_DEG)) % 360
            speed = max(0.0, speed_mps + rng.gauss(0, 0.15))
            rows.append([
                str(int(t * 1000)),
                f"{lat:.7f}", f"{lon:.7f}", f"{alt:.2f}", f"{hdg:.2f}", f"{speed:.2f}", "1",
            ])
            t += SAMPLE_INTERVAL_S
    return rows


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--home-lat", type=float, required=True)
    parser.add_argument("--home-lon", type=float, required=True)
    parser.add_argument("--altitude-m", type=float, default=75.0)
    parser.add_argument("--duration-seconds", type=float, default=150.0, help="circle pattern only")
    parser.add_argument("--seed", type=int, default=20260806)
    parser.add_argument(
        "--pattern", choices=["circle", "lawnmower"], default="circle",
        help="'circle' = this SITL image's real stock CIRCLE-mode default (10m radius, 20deg/s). "
             "'lawnmower' = a wider hypothetical coverage flight, NOT what autofly.py actually flies "
             "-- use this to test recall without the tight-circle near-duplicate-frame confound.",
    )
    parser.add_argument("--width-m", type=float, default=300.0, help="lawnmower pattern only")
    parser.add_argument("--height-m", type=float, default=200.0, help="lawnmower pattern only")
    parser.add_argument("--leg-spacing-m", type=float, default=50.0, help="lawnmower pattern only")
    parser.add_argument("--speed-mps", type=float, default=12.0, help="lawnmower pattern only")
    args = parser.parse_args(argv)

    if args.pattern == "lawnmower":
        rows = generate_lawnmower_rows(
            args.home_lat, args.home_lon, args.altitude_m,
            args.width_m, args.height_m, args.leg_spacing_m, args.speed_mps, args.seed,
        )
    else:
        rows = generate_rows(args.home_lat, args.home_lon, args.altitude_m, args.duration_seconds, args.seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(CSV_HEADER)
        writer.writerows(rows)
    print(
        f"[analytical_circle_telemetry] wrote {len(rows)} rows ({args.pattern}) to {args.out} "
        "(NOT live SITL capture)"
    )
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
