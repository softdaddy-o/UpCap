package com.upcap.model

/**
 * Quality presets control tile overlap/stride — NOT the model input size.
 * The ONNX model always receives 128×128 tiles.
 * More overlap = smoother seams but more tiles (slower).
 * Less overlap = fewer tiles (faster) but rougher seams.
 */
enum class QualityPreset(
    val label: String,
    val description: String,
    val tileOverlap: Int
) {
    FAST(
        label = "빠르게",
        description = "타일 겹침 최소화로 빠르게 처리",
        tileOverlap = 4
    ),
    BALANCED(
        label = "균형",
        description = "속도와 품질의 균형",
        tileOverlap = 8
    ),
    HIGH(
        label = "고품질",
        description = "타일 겹침을 늘려 이음새 없이 처리 (느림)",
        tileOverlap = 16
    );

    val tileStride: Int get() = MODEL_TILE_SIZE - 2 * tileOverlap

    companion object {
        /** The ONNX model's fixed input spatial dimension. */
        const val MODEL_TILE_SIZE = 128
    }
}
