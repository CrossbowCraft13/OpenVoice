# Changelog

## [1.0.0-beta] — 2026-07-29

### Added
- Voice pipeline: VAD, wake word (OpenWakeWord), STT (Whisper.cpp), TTS (Piper)
- Local AI Runtime: llama.cpp integration, InferenceEngine, ModelManager, DeviceProfiler
- Accessibility Intelligence: semantic UI tree, search engine, action engine, screen state, workflows
- Multimodal Perception: screenshot pipeline, OCR, vision runtime, ScreenContext fusion
- Memory & Knowledge System: vector store, SQLite-backed knowledge graph, AES-256 encryption
- Autonomous Planner: plan decomposition, cost-based capability selection, execution engine, recovery
- System Integration: explain mode, resource management, reliability manager, privacy dashboard
- Developer Console: live pipeline visualization, timing, diagnostics
- Task Blackboard: shared context for all subsystems
- Capability Router: Native → A11y → OCR → LLM → Vision routing

### Architecture
- 73 Kotlin source files (14,000+ lines)
- 8 test suites (3,700+ test lines)
- 2 C++ JNI bridge files
- Clean Architecture with Hilt DI
- Material 3 Compose UI with neon orange theme
- All processing is local. Zero cloud dependencies.

### Known Limitations
- Plugin SDK not yet implemented
- ONNX/LLM models must be downloaded separately
- Whisper.cpp native library requires NDK compilation
- Vision models require separate multimodal GGUF download
- Accessibility Service must be enabled manually in system settings
