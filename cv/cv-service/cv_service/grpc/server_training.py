"""Training-host process entrypoint (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6).

Serves `Training` + `Geolocation` -- long-running, GPU-hungry work -- on the
same image as the combined ``cv_service.grpc.server`` entrypoint, so it can
never share a process (or a GIL) with `Inference`'s per-frame hot path. See
`cv_service.grpc.server`'s module docstring ("Process roles") for the full
role/registry story, including the one behavior a split deployment loses:
`PromoteModel` handled here does not update an already-running
`cv_service.grpc.server_inference` process's in-memory registry.

Defaults ``CV_SERVICE_ROLE`` to ``training`` -- an operator who sets the env
var explicitly still wins, this only supplies the default a plain container
`CMD` would otherwise have to repeat. Delegates straight to
`cv_service.grpc.server.main`; no servicer construction is duplicated here
-- `serve()` is the one place that lives.

Run with::

    python -m cv_service.grpc.server_training
"""

from __future__ import annotations

import os

from cv_service.config import ROLE_TRAINING
from cv_service.grpc.server import main

os.environ.setdefault("CV_SERVICE_ROLE", ROLE_TRAINING)

if __name__ == "__main__":
    main()
