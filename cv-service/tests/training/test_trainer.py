"""Unit tests for `cv_service.training.trainer`'s pure (non-ultralytics)
helpers: the produced-artifact naming scheme.

Moved out of `tests/test_training.py` (now `tests/grpc/test_training_servicer.py`).
Stdlib-only -- no `cv`/gRPC extras needed to run these; `ultralytics_train`
itself (the real fine-tune path) is only exercised via injected fakes in
`tests/grpc/test_training_servicer.py`'s `StartTraining` tests -- CI never
runs a real train (see MODULE.md).
"""

from __future__ import annotations

from cv_service.training import trainer


def test_output_model_id_scheme():
    assert trainer.output_model_id("ds-1", 3) == "ds-1-3e.pt"
    # path separators can never escape the model dir.
    assert "/" not in trainer.output_model_id("a/b", 5)


def test_output_model_id_is_deterministic_for_the_same_inputs():
    assert trainer.output_model_id("ds-1", 3) == trainer.output_model_id("ds-1", 3)
