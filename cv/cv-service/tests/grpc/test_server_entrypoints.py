"""Tests for the per-role thin entrypoint wrappers
(`cv_service.grpc.server_inference`/`server_training`,
docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6).

Each wrapper's only real behavior is "default CV_SERVICE_ROLE to my role,
unless something already set it" -- reloaded per test (rather than spawning
a real subprocess) so the module-level `os.environ.setdefault` call
actually runs again against a controlled env; pure stdlib, no `cv`/gRPC
extras or generated stubs touched beyond what `cv_service.grpc.server`
itself already requires.
"""

from __future__ import annotations

import importlib
import os

import cv_service.grpc.server as server_module
import cv_service.grpc.server_inference as server_inference_module
import cv_service.grpc.server_training as server_training_module


def test_server_inference_defaults_cv_service_role_to_inference(monkeypatch):
    monkeypatch.delenv("CV_SERVICE_ROLE", raising=False)

    importlib.reload(server_inference_module)

    assert os.environ["CV_SERVICE_ROLE"] == "inference"


def test_server_inference_does_not_override_an_explicit_role(monkeypatch):
    monkeypatch.setenv("CV_SERVICE_ROLE", "all")

    importlib.reload(server_inference_module)

    assert os.environ["CV_SERVICE_ROLE"] == "all"


def test_server_inference_delegates_to_the_shared_main(monkeypatch):
    monkeypatch.delenv("CV_SERVICE_ROLE", raising=False)

    importlib.reload(server_inference_module)

    assert server_inference_module.main is server_module.main


def test_server_training_defaults_cv_service_role_to_training(monkeypatch):
    monkeypatch.delenv("CV_SERVICE_ROLE", raising=False)

    importlib.reload(server_training_module)

    assert os.environ["CV_SERVICE_ROLE"] == "training"


def test_server_training_does_not_override_an_explicit_role(monkeypatch):
    monkeypatch.setenv("CV_SERVICE_ROLE", "all")

    importlib.reload(server_training_module)

    assert os.environ["CV_SERVICE_ROLE"] == "all"


def test_server_training_delegates_to_the_shared_main(monkeypatch):
    monkeypatch.delenv("CV_SERVICE_ROLE", raising=False)

    importlib.reload(server_training_module)

    assert server_training_module.main is server_module.main
