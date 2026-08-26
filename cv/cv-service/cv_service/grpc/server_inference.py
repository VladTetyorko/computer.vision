"""Inference-only process entrypoint (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6).

Serves only `Inference` -- the ~50ms-budget per-frame hot path -- on the
same image as the combined ``cv_service.grpc.server`` entrypoint, so a
`Training`/`Geolocation` job running as a *separate* process (`cv_service.
grpc.server_training`) can never share this process's GIL and degrade live
detection. See `cv_service.grpc.server`'s module docstring ("Process
roles") for the full role/registry story.

Defaults ``CV_SERVICE_ROLE`` to ``inference`` -- an operator who sets the
env var explicitly (e.g. a test harness pinning ``all``) still wins, this
only supplies the default a plain container `CMD` would otherwise have to
repeat. Delegates straight to `cv_service.grpc.server.main`; no servicer
construction is duplicated here -- `serve()` is the one place that lives.

Run with::

    python -m cv_service.grpc.server_inference
"""

from __future__ import annotations

import os

from cv_service.config import ROLE_INFERENCE
from cv_service.grpc.server import main

os.environ.setdefault("CV_SERVICE_ROLE", ROLE_INFERENCE)

if __name__ == "__main__":
    main()
