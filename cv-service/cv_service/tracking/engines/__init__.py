"""Tracker engines: pixels and geometry only.

An engine never sees `TrackingParams`, never decides cadence, and never
knows what a lock is (TRACKING-ORCHESTRATION §2.1). It implements exactly
one of the two protocols in `base.py` and nothing else.

`base.py` is stdlib-only; `bytetrack.py`, `lk.py` and `ncc.py` import
`numpy`/`cv2`/`ultralytics` at module scope and are therefore imported
lazily, by `cv_service.tracking.registry`'s factories, never at package
import time.
"""
