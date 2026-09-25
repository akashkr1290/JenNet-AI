package com.jannetai.backend.client.ai;

/**
 * Audit GAP-032: ai-service {@code POST /api/v1/ai/quality} response
 * (snake_case on the wire). {@code message} is the citizen-facing retake prompt.
 */
public record AiQualityResult(
        boolean acceptable,
        String qualityFlag,
        int width,
        int height,
        String message
) {
}
