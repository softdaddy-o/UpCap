# Wink Studio Reference And Quality Roadmap

Official references:
- Wink desktop/mobile: https://wink.ai/
- Wink mobile feature page: https://wink.ai/mobile
- Wink iOS app listing: https://apps.apple.com/us/app/wink-video-enhancer-editor/id1594288016

Current goal:
- Prioritize same-resolution quality enhancement over simple resolution upscaling.
- Match Wink-style "repair" workflow where the app chooses a scene-aware enhancement path.

High-priority TODO:
- [ ] Replace the current shader-only enhancement pass with a real AI enhancement pipeline.
- [ ] Add scene classification before enhancement.
- [ ] Add enhancement presets similar to Wink categories: Portrait, Scenery, Concert, Game.
- [ ] Add quality restore strength slider with before/after preview.
- [ ] Add denoise model path for noisy clips.
- [ ] Add deblur/detail recovery model path for soft clips.
- [ ] Add low-light enhancement model path for dark concert/night clips.
- [ ] Add face restoration pass for portrait clips.
- [ ] Add frame interpolation option for low-FPS or jittery clips.
- [ ] Add AI subtitle backend that does not depend on device speech services.

Suggested AI model stack:
- Scene classifier: lightweight ONNX classifier to route clips into portrait, scenery, concert, or game presets.
- Denoise/deblur/restore: Real-ESRGAN family or compact restoration model converted to ONNX for mobile-friendly inference.
- Face restoration: GFPGAN-style face refinement model for portrait segments only.
- Frame interpolation: RIFE-style model for optional motion smoothing.
- Subtitle STT: Whisper tiny/base ONNX or whisper.cpp JNI bridge with Korean support.

Execution order:
1. Build a scene classifier and preset router.
2. Add tile-based AI inference so longer videos fit mobile memory limits.
3. Run AI enhancement on sampled keyframes first for preview.
4. Add full-video batch processing with the same model path.
5. Add before/after split preview and quality intensity control.
6. Add optional face restore and interpolation passes.
7. Replace device STT fallback with bundled Whisper inference.

Product behaviors to copy from Wink:
- One-tap quality repair.
- Scene-specific enhancement presets.
- Strong preview UX with before/after comparison.
- Quality restoration plus denoise and low-light recovery.
- Auto captions as a first-class workflow.

Technical notes for this project:
- The current `UpscaleProcessor` uses Media3 Transformer effects as a fallback enhancement pass only.
- This is not enough for Wink-level restoration quality.
- ONNX Runtime is already included in the project, so the next implementation step should be model loading, tiled inference, and frame re-encoding.
