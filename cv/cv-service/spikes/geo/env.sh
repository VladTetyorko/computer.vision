# Source before running any spikes/geo script: cd cv/cv-service && source spikes/geo/env.sh
# Points every model-weight download (torch.hub, HF) at the gitignored, machine-local
# .model-cache/ instead of the default ~/.cache -- VISUAL-GEO-V2-PLAN.md §3.6's CV_GEO_MODEL_CACHE,
# H0-scoped (production H4 reads the same env var name from cv_service/config.py).
export CV_GEO_MODEL_CACHE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.model-cache"
export TORCH_HOME="$CV_GEO_MODEL_CACHE"
export HF_HOME="$CV_GEO_MODEL_CACHE/huggingface"
export PYTHONPATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
echo "CV_GEO_MODEL_CACHE=$CV_GEO_MODEL_CACHE"
