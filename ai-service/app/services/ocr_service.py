"""
Embedded text extraction (SRS 15.4 Features, 21.4).

Extracts visible text (department signage, work-order stickers, hazard
warnings) from the normalized image via pytesseract. SRS 21.4 Fallback
Logic: "if no text is detected, the field is stored as null and
classification proceeds using image and GPS data alone." - ocr_text
itself still follows that contract exactly (None either way).

Gap-backlog Patch 3 (Sep 2026 audit): the original version of this module
went further than the SRS required and also collapsed "no text found"
and "OCR engine unavailable" into the same caller-visible None, on the
reasoning that SRS's contract only distinguishes "text" vs "no text".
That reasoning held for ocr_text; it did not hold for the API as a
whole, which had no field at all telling an operator or client which of
those two cases actually happened - only a log line, invisible to any
API consumer. extract_text now returns both the text (unchanged
contract) and an OcrStatus, so ClassifyResponse.ocr_status (see
app/schemas/classify.py) can surface the distinction for real.
"""
from __future__ import annotations

import logging

import numpy as np

from app.config import get_settings
from app.schemas.classify import OcrStatus

logger = logging.getLogger(__name__)


def extract_text(normalized_image: np.ndarray) -> tuple[str | None, OcrStatus]:
    settings = get_settings()
    if not settings.ocr_enabled:
        return None, OcrStatus.UNAVAILABLE

    try:
        import pytesseract
        from PIL import Image
        import cv2

        if settings.tesseract_cmd_path:
            pytesseract.pytesseract.tesseract_cmd = settings.tesseract_cmd_path

        rgb_image = cv2.cvtColor(normalized_image, cv2.COLOR_BGR2RGB)
        pil_image = Image.fromarray(rgb_image)
        text = pytesseract.image_to_string(pil_image).strip()
        if text:
            return text, OcrStatus.SUCCESS
        return None, OcrStatus.NO_TEXT
    except ImportError as exc:
        logger.info("OCR skipped - pytesseract not installed: %s", exc)
        return None, OcrStatus.UNAVAILABLE
    except Exception as exc:
        # Covers TesseractNotFoundError (binary missing) and any other
        # runtime OCR failure - genuinely "unavailable", not "no text",
        # since the engine never successfully ran to completion.
        logger.info("OCR skipped - engine unavailable or failed: %s", exc)
        return None, OcrStatus.UNAVAILABLE
