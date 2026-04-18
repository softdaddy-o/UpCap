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
    // Qualcomm AI Hub XLSR — extremely light, mobile-NPU first. Same I/O shape
    // as the other SR models (128×128 → 512×512, 4×). Ideal default when the
    // device has a working NNAPI NPU path.
    XLSR_FAST(
        label = "초고속 (XLSR)",
        description = "Qualcomm XLSR — 110KB, NPU 실시간 (기본값)",
        fileName = "xlsr.onnx",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/xlsr/releases/v0.50.2/xlsr-onnx-float.zip",
        minimumBytes = 80_000L,
        sizeLabel = "약 110 KB",
        isArchived = true
    ),
    // w8a8-quantized XLSR. Same tensor shape as XLSR_FAST (128²→512²). If the
    // device's NNAPI driver exposes an INT8 NPU path (Qualcomm Hexagon HTP),
    // expect 3–5× over fp32. If not, NNAPI will refuse the graph and — because
    // CPU fallback is disabled in the pipeline — the session creation throws
    // and the user sees a clear error. Kept in its own subdirectory because the
    // zip's onnx+data filenames collide with XLSR_FAST's.
    XLSR_INT8(
        label = "양자화 초고속 (XLSR w8a8)",
        description = "Qualcomm XLSR w8a8 — NPU 호환 시 최대 5배 (실험)",
        fileName = "xlsr_w8a8/xlsr.onnx",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/xlsr/releases/v0.50.2/xlsr-onnx-w8a8.zip",
        minimumBytes = 20_000L,
        sizeLabel = "약 135 KB",
        isArchived = true
    ),
    SESR_M5(
        label = "빠름 (SESR-M5)",
        description = "Qualcomm SESR-M5 — 1.3MB, 속도/품질 균형",
        fileName = "sesr_m5.onnx",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/sesr_m5/releases/v0.50.2/sesr_m5-onnx-float.zip",
        minimumBytes = 1_000_000L,
        sizeLabel = "약 1.3 MB",
        isArchived = true
    ),
    MOBILE_V3(
        label = "모바일 최적화 (Real-ESRGAN)",
        description = "Real-ESRGAN x4v3 — 5MB, 품질 우수 / 느림",
        fileName = "real-esrgan-general-x4v3.onnx",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.50.2/real_esrgan_general_x4v3-onnx-float.zip",
        minimumBytes = 3_000_000L,
        sizeLabel = "약 5 MB",
        isArchived = true
    ),
    DESKTOP_PLUS(
        label = "고품질 (데스크탑)",
        description = "Real-ESRGAN x4plus — 64MB, 최고 품질 / 매우 느림",
        fileName = "Real-ESRGAN-x4plus.onnx",
        url = "https://huggingface.co/k4yt3x/upscaler-models/resolve/main/Real-ESRGAN/Real-ESRGAN-x4plus.onnx",
        minimumBytes = 60_000_000L,
        sizeLabel = "약 64 MB"
    )
}
