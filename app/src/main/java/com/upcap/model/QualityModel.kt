package com.upcap.model

enum class QualityModel(
    val label: String,
    val description: String,
    val fileName: String,
    val url: String,
    val minimumBytes: Long,
    val sizeLabel: String,
    val isArchived: Boolean = false
) {
    MOBILE_V3(
        label = "모바일 최적화",
        description = "Real-ESRGAN x4v3 — 가볍고 빠름 (기본값)",
        fileName = "real-esrgan-general-x4v3.onnx",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.50.2/real_esrgan_general_x4v3-onnx-float.zip",
        minimumBytes = 3_000_000L,
        sizeLabel = "약 5 MB",
        isArchived = true
    ),
    DESKTOP_PLUS(
        label = "고품질 (데스크탑)",
        description = "Real-ESRGAN x4plus — 높은 품질, 느림 (테스트용)",
        fileName = "Real-ESRGAN-x4plus.onnx",
        url = "https://huggingface.co/k4yt3x/upscaler-models/resolve/main/Real-ESRGAN/Real-ESRGAN-x4plus.onnx",
        minimumBytes = 60_000_000L,
        sizeLabel = "약 64 MB"
    )
}
