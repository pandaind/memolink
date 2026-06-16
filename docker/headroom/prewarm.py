"""
Pre-warm script — run during Docker build to download and cache the kompress
ONNX weights inside the image layer.  This makes the first real request fast.

Usage (in Dockerfile):
  RUN python prewarm.py
"""

import logging
import os
import sys

logging.basicConfig(level=logging.INFO, format="%(levelname)s - %(message)s")
logger = logging.getLogger("prewarm")

model_id = os.getenv("KOMPRESS_MODEL_ID", "chopratejas/kompress-small")
logger.info("Pre-warming kompress model: %s", model_id)

try:
    from headroom.transforms.content_router import ContentRouter
    from headroom.transforms.kompress_compressor import KompressCompressor, KompressConfig

    router = ContentRouter()
    router._kompress = KompressCompressor(
        config=KompressConfig(
            model_id=model_id,
            enable_ccr=False
        )
    )
    status = router.eager_load_compressors()
    logger.info("Pre-warm complete  status=%s", status)
except Exception as exc:
    # Non-fatal: the server will retry at startup
    logger.warning("Pre-warm failed (will retry at server start): %s", exc)
    sys.exit(0)
