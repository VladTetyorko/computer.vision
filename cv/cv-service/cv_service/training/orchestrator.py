"""Job-lifecycle / queue-poll state machine for ``Training.StartTraining``.

Lifted out of the (formerly ~120-line) ``StartTraining`` gRPC handler so the
"run a trainer on a background thread, poll a queue for progress, honor
cancellation" machinery can be read/tested independent of wire translation.
This module never imports ``cv_pb2`` or ``grpc`` -- it yields plain
:class:`JobEvent`s; ``cv_service/grpc/servicers.py`` is the sole place that
turns those into ``cv_pb2.TrainingProgress`` messages (see its
``StartTraining`` method).
"""

from __future__ import annotations

import logging
import queue
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, Optional

from cv_service.training import trainer

LOGGER = logging.getLogger("cv_service.training.orchestrator")


@dataclass(frozen=True)
class JobEvent:
    """One reported step of a training job's lifecycle -- the plain,
    wire-agnostic counterpart of ``cv_pb2.TrainingProgress``.

    ``kind`` is one of ``"running"`` (one per epoch) or the two terminal
    kinds ``"succeeded"``/``"failed"``. A job ending via cancellation (client
    disconnect, or the gRPC context going inactive) yields no terminal event
    at all -- exactly like before this state machine was extracted -- the
    generator simply stops.
    """

    kind: str
    epoch: int = 0
    total_epochs: int = 0
    loss: float = 0.0
    map50: float = 0.0
    message: str = ""
    model_id: str = ""  # populated only for "succeeded"


def run_training_job(
    spec: trainer.TrainingSpec,
    *,
    job_id: str,
    train_fn: trainer.TrainFn,
    is_context_active: Callable[[], bool],
    register_cancel_callback: Callable[[Callable[[], None]], None],
    publish_artifact: Callable[[Path, trainer.TrainingSpec], str],
) -> Iterator[JobEvent]:
    """Run `train_fn` on a background worker thread; yield one `JobEvent`
    per epoch then exactly one terminal event (`"succeeded"`/`"failed"`), or
    none at all if cancelled.

    - The blocking `.train()` call runs entirely on a background thread
      (`worker`, below) -- this generator only polls a `queue.Queue` for
      progress + yields, so it stays responsive to caller cancellation and
      never wedges the calling thread with long-lived compute (mirrors
      `DetectStream`'s own reader-thread/mailbox shape).
    - `is_context_active()` is polled every 0.5s; `register_cancel_callback`
      is invoked once up front so an immediate/async cancellation signal
      (e.g. a gRPC client disconnect) also flips the same cancel flag,
      belt-and-braces with the polling.
    - Whatever ends this generator (a terminal event yielded and returned,
      cancellation observed, or the caller simply stops iterating), the
      worker thread is always told to stop and reaped best-effort.
    """
    events: "queue.Queue[tuple[str, object]]" = queue.Queue()
    cancel_event = threading.Event()

    def worker() -> None:
        try:
            best = train_fn(
                spec,
                on_epoch=lambda progress: events.put(("epoch", progress)),
                is_cancelled=cancel_event.is_set,
            )
            events.put(("done", best))
        except trainer.TrainingCancelled:
            events.put(("cancelled", None))
        except Exception as exc:  # noqa: BLE001 - reported to the caller as "failed"
            LOGGER.exception("training job=%s failed", job_id)
            events.put(("error", exc))

    thread = threading.Thread(target=worker, name=f"cv-training-{job_id[:8]}", daemon=True)
    thread.start()

    register_cancel_callback(cancel_event.set)

    last: Optional[trainer.EpochProgress] = None
    try:
        while True:
            if not is_context_active():
                cancel_event.set()
                return
            try:
                kind, payload = events.get(timeout=0.5)
            except queue.Empty:
                continue

            if kind == "epoch":
                last = payload  # type: ignore[assignment]
                yield JobEvent(
                    kind="running",
                    epoch=payload.epoch,
                    total_epochs=payload.total_epochs,
                    loss=payload.loss,
                    map50=payload.map50,
                )
            elif kind == "done":
                model_id = publish_artifact(payload, spec)  # type: ignore[arg-type]
                yield JobEvent(
                    kind="succeeded",
                    epoch=spec.epochs,
                    total_epochs=spec.epochs,
                    loss=last.loss if last else 0.0,
                    map50=last.map50 if last else 0.0,
                    model_id=model_id,
                    message=(
                        f"trained model saved as {model_id!r}; it now shows in ListModels -- "
                        f"promote it via PromoteModel to make it the live default"
                    ),
                )
                return
            elif kind == "cancelled":
                LOGGER.info("training job=%s cancelled", job_id)
                return
            elif kind == "error":
                yield JobEvent(
                    kind="failed",
                    total_epochs=spec.epochs,
                    message=f"training failed: {payload}",
                )
                return
    finally:
        cancel_event.set()
        thread.join(timeout=5)
