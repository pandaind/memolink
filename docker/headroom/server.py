"""
Headroom kompress compression HTTP API.

Wraps KompressCompressor (kompress-small ONNX) as a lightweight FastAPI
service.  MemoLink MCP server POSTs text bodies here and receives compressed
text before returning tool results to the LLM client.

Endpoints:
  GET  /health   → liveness / readiness probe
  POST /compress → compress a text string, returns compressed + stats
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s - %(message)s",
)
logger = logging.getLogger("headroom.server")

# ── Global compressor instance (loaded once at startup) ──────────────────────

_compressor = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _compressor

    model_id = os.getenv("KOMPRESS_MODEL_ID", "chopratejas/kompress-small")
    threshold = float(os.getenv("KOMPRESS_THRESHOLD", "0.5"))
    chunk_words = int(os.getenv("KOMPRESS_CHUNK_WORDS", "350"))

    logger.info("Loading kompress model: %s (threshold=%.2f)", model_id, threshold)

    from headroom.transforms.kompress_compressor import (
        KompressCompressor,
        KompressConfig,
    )

    config = KompressConfig(
        model_id=model_id,
        score_threshold=threshold,
        chunk_words=chunk_words,
        enable_ccr=False,   # disable CCR cache — MemoLink manages its own cache
    )
    _compressor = KompressCompressor(config)

    backend = _compressor.preload()
    logger.info("Kompress ready  model=%s  backend=%s", model_id, backend)

    yield  # server is live

    _compressor = None
    logger.info("Kompress compressor unloaded")


# ── FastAPI app ───────────────────────────────────────────────────────────────

app = FastAPI(
    title="Headroom Compression API",
    description="kompress-small ONNX sidecar for MemoLink MCP server",
    version="1.0.0",
    lifespan=lifespan,
)


# ── Request / Response models ─────────────────────────────────────────────────


class CompressRequest(BaseModel):
    content: str = Field(..., description="Text to compress")
    min_length: int = Field(
        300,
        description="Skip compression when content is shorter than this (chars)",
    )


class CompressResponse(BaseModel):
    compressed: str
    original_tokens: int
    compressed_tokens: int
    compression_ratio: float
    skipped: bool = False
    model_id: str = ""


# ── Endpoints ─────────────────────────────────────────────────────────────────


@app.get("/health", tags=["ops"])
def health():
    """Liveness + readiness probe used by Docker healthcheck."""
    return {
        "status": "ok" if _compressor is not None else "loading",
        "model_loaded": _compressor is not None,
    }


@app.post("/compress", response_model=CompressResponse, tags=["compression"])
def compress(req: CompressRequest):
    """
    Compress ``content`` with kompress-small ONNX.

    Returns the compressed text plus token counts.  If the content is
    shorter than ``min_length`` characters the original is returned unchanged
    with ``skipped=true``.
    """
    if _compressor is None:
        raise HTTPException(status_code=503, detail="Kompress model is still loading")

    if len(req.content) < req.min_length:
        n = len(req.content.split())
        logger.debug("Skipped (short content, %d chars)", len(req.content))
        return CompressResponse(
            compressed=req.content,
            original_tokens=n,
            compressed_tokens=n,
            compression_ratio=1.0,
            skipped=True,
            model_id=_compressor.config.model_id,
        )

    result = _compressor.compress(req.content)

    logger.info(
        "Compressed  words=%d→%d  ratio=%.2f  model=%s",
        result.original_tokens,
        result.compressed_tokens,
        result.compression_ratio,
        result.model_used,
    )

    return CompressResponse(
        compressed=result.compressed,
        original_tokens=result.original_tokens,
        compressed_tokens=result.compressed_tokens,
        compression_ratio=result.compression_ratio,
        skipped=False,
        model_id=result.model_used,
    )
