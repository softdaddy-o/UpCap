package com.upcap.model

enum class QualityPreset(
    val label: String,
    val description: String,
    val tileSize: Int,
    val tileOverlap: Int
) {
    FAST(
        label = "빠르게",
        description = "큰 타일로 빠르게 처리 (NNAPI 가속)",
        tileSize = 192,
        tileOverlap = 12
    ),
    BALANCED(
        label = "균형",
        description = "속도와 품질의 균형",
        tileSize = 128,
        tileOverlap = 8
    ),
    HIGH(
        label = "고품질",
        description = "작은 타일로 세밀하게 처리 (느림)",
        tileSize = 64,
        tileOverlap = 4
    );

    val tileStride: Int get() = tileSize - 2 * tileOverlap
}
