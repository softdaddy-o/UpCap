package com.upcap.model

/**
 * Quality presets control three axes of the enhancement pipeline:
 *
 *  - usesAi: when false, the ONNX model is never loaded and each frame is only
 *    passed through denoise + sharpen. Fastest mode by far. "후보정만".
 *  - inputDownscale: how much we shrink the frame before SR. Because the output
 *    is rendered back at the original resolution, shrinking first lets 4× SR
 *    lift us back with far fewer tiles. Biggest AI-path speed lever.
 *  - frameStride: only every Nth frame is actually enhanced; the rest reuse the
 *    most recent enhanced output. Good for near-static scenes, causes judder on
 *    fast motion.
 *  - tileOverlap: seam smoothing. More overlap = more tiles, slower.
 *
 * The ONNX model input is fixed at 128×128 regardless of preset.
 */
enum class QualityPreset(
    val label: String,
    val description: String,
    val usesAi: Boolean,
    val tileOverlap: Int,
    val inputDownscale: Int,
    val frameStride: Int
) {
    POSTPROCESS(
        label = "후보정만",
        description = "AI 없이 노이즈 제거 + 샤프닝만 — 가장 빠름",
        usesAi = false,
        tileOverlap = 0,
        inputDownscale = 1,
        frameStride = 1
    ),
    FAST(
        label = "빠르게",
        description = "입력 4배 축소 + 2프레임마다 SR — 빠름",
        usesAi = true,
        tileOverlap = 4,
        inputDownscale = 4,
        frameStride = 2
    ),
    BALANCED(
        label = "균형",
        description = "입력 2배 축소 — 속도/품질 균형",
        usesAi = true,
        tileOverlap = 8,
        inputDownscale = 2,
        frameStride = 1
    ),
    HIGH(
        label = "고품질",
        description = "원본 해상도 SR — 느림, 최고 품질",
        usesAi = true,
        tileOverlap = 16,
        inputDownscale = 1,
        frameStride = 1
    );

    val tileStride: Int get() = MODEL_TILE_SIZE - 2 * tileOverlap

    companion object {
        /** The ONNX model's fixed input spatial dimension. */
        const val MODEL_TILE_SIZE = 128
    }
}
