"""Throughput harness for the tracker/detector split (CV-ORCHESTRATION W4).

See `__main__.py`. Deliberately separate from `tools/trackeval`: that harness
measures TRACKING QUALITY against ground truth with a synthetic detector and
no gRPC at all, this one measures how many real 10 fps streams a deployment
SHAPE sustains, over the real wire, with a real model. Neither can answer the
other's question.
"""
