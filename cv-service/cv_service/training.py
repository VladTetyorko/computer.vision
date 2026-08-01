"""Persistence for the operator's active/promoted model choice.

Stdlib-only (``json``) -- importable without the ``cv`` extra, the same
import discipline ``server.py`` keeps for its top-level imports (it only
imports ``cv2``/``ultralytics`` lazily). This module owns the small
**active-model marker**: a JSON file in the model directory recording which
roster ``model_id`` the operator promoted, so the choice survives a
cv-service restart/redeploy.

That restart-survival is the whole point of persisting it: the production
service is redeployed by rsync (MEMORY: gb4005-inference-box), so the
operational loop is *train a model offline -> rsync the artifact into the
model directory -> ``Training.PromoteModel`` it live*. ``PromoteModel`` both
re-points the in-memory ``ModelRegistry`` default and writes this marker;
``server._build_default_registry`` reads the marker at startup so the last
promotion is still in effect after the host restarts.

The marker is deliberately forgiving: a missing/corrupt/empty file reads as
"no promotion recorded" (fall back to the env/``DEFAULT_MODEL`` default),
never an error -- a bad marker must not brick startup.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

LOGGER = logging.getLogger("cv_service.training")

# Filename of the active-model marker, written directly under the model
# directory (`server._MODEL_SEARCH_DIR`, i.e. `cv-service/`). Gitignored --
# it is runtime state, not source.
ACTIVE_MODEL_MARKER = "active_model.json"


def read_active_model(model_dir: Path) -> Optional[str]:
    """Return the promoted ``model_id`` recorded in the marker, or ``None``.

    ``None`` covers every "nothing usable recorded" case -- the marker is
    absent, unreadable, not valid JSON, or carries no non-empty string
    ``id`` -- each logged at most as a warning, never raised: a corrupt
    marker degrades to "no promotion", it does not fail startup.
    """
    marker = Path(model_dir) / ACTIVE_MODEL_MARKER
    try:
        raw = marker.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    except OSError as exc:  # pragma: no cover - unusual FS error
        LOGGER.warning("could not read active-model marker %s (%s); ignoring", marker, exc)
        return None

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        LOGGER.warning("active-model marker %s is not valid JSON (%s); ignoring", marker, exc)
        return None

    model_id = data.get("id") if isinstance(data, dict) else None
    if not isinstance(model_id, str) or not model_id:
        LOGGER.warning("active-model marker %s has no usable 'id'; ignoring", marker)
        return None
    return model_id


def write_active_model(model_dir: Path, model_id: str, version: str = "") -> None:
    """Persist ``model_id`` (and optional ``version``) as the active model.

    Overwrites any existing marker. Raises ``OSError`` only if the model
    directory itself is not writable -- the caller (``PromoteModel``) treats
    that as the operation failing.
    """
    marker = Path(model_dir) / ACTIVE_MODEL_MARKER
    payload = {"id": model_id, "version": version or ""}
    marker.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    LOGGER.info("persisted active model %r (version=%r) to %s", model_id, version, marker)
