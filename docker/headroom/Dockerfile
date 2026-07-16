# ─────────────────────────────────────────────────────────────────────────────
# Headroom kompress-small ONNX sidecar
# ─────────────────────────────────────────────────────────────────────────────
# Build arg:
#   KOMPRESS_MODEL_ID   Model to bake into the image layer (default: kompress-small)
#                       Set to chopratejas/kompress-base for higher quality.
#
# Runtime env (override at `docker run` or in docker-compose.yml):
#   KOMPRESS_MODEL_ID             chopratejas/kompress-small (default)
#   KOMPRESS_THRESHOLD            0.5 (0.0 = keep everything, 1.0 = discard everything)
#   KOMPRESS_CHUNK_WORDS          350 (words per inference chunk)
#   HEADROOM_KOMPRESS_BACKEND     pytorch (or onnx_cpu if using an ONNX model)
#   HEADROOM_KOMPRESS_ONNX_INTRA_THREADS  2
#   HEADROOM_KOMPRESS_ONNX_INTER_THREADS  1
# ─────────────────────────────────────────────────────────────────────────────

FROM python:3.12-slim AS base

# System deps: libgomp for ONNX Runtime CPU thread pool; curl for healthcheck; build-essential/g++ for building hnswlib
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 curl build-essential g++ \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ── Install Python dependencies ───────────────────────────────────────────────
# headroom-ai[all] pulls: onnxruntime, transformers, tokenizers, safetensors
# uvicorn[standard] adds uvloop + httptools for better async throughput
RUN pip install --no-cache-dir \
    "headroom-ai[all]" \
    "fastapi>=0.111" \
    "uvicorn[standard]>=0.29" \
    && apt-get purge -y --auto-remove build-essential g++ \
    && rm -rf /var/lib/apt/lists/*

# ── Copy application files ─────────────────────────────────────────────────────
COPY prewarm.py .
COPY server.py  .

# ── Pre-warm: download + cache ONNX weights into the image layer ──────────────
# Change KOMPRESS_MODEL_ID build-arg to bake kompress-base instead:
#   docker build --build-arg KOMPRESS_MODEL_ID=chopratejas/kompress-base .
ARG KOMPRESS_MODEL_ID=chopratejas/kompress-small
ENV KOMPRESS_MODEL_ID=${KOMPRESS_MODEL_ID}
ENV HF_ENDPOINT="https://hf-mirror.com"

# HuggingFace cache goes into /root/.cache/huggingface (persisted in image layer)
RUN python prewarm.py

# ── Runtime defaults ──────────────────────────────────────────────────────────
ENV KOMPRESS_THRESHOLD=0.5
ENV KOMPRESS_CHUNK_WORDS=350
# Force CPU ONNX backend — avoids CUDA/MPS detection overhead in containers
ENV HEADROOM_KOMPRESS_BACKEND=pytorch
# Cap ONNX internal thread pools to avoid CPU contention in constrained envs
ENV HEADROOM_KOMPRESS_ONNX_INTRA_THREADS=2
ENV HEADROOM_KOMPRESS_ONNX_INTER_THREADS=1

EXPOSE 8787

# ── Healthcheck ───────────────────────────────────────────────────────────────
HEALTHCHECK --interval=15s --timeout=10s --start-period=90s --retries=5 \
    CMD curl -sf http://localhost:8787/health | python -c \
        "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d['model_loaded'] else 1)"

CMD ["uvicorn", "server:app", \
     "--host", "0.0.0.0", \
     "--port", "8787", \
     "--workers", "1"]
