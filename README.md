<div align="center">
  
# 🎙️ OpenVoice

**A private, local-first AI assistant for Android**

[![License](https://img.shields.io/badge/License-Apache%202.0-neonorange.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/CrossbowCraft13/openvoice/ci.yml?branch=main&label=build&color=FF6D00)](.github/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-FF6D00?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-14-FF6D00?logo=android)](https://developer.android.com)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-FF6D00)](CONTRIBUTING.md)
[![GitHub Stars](https://img.shields.io/github/stars/CrossbowCraft13/openvoice?style=social)](https://github.com/CrossbowCraft13/openvoice)

---

**All processing is local. No cloud. No telemetry. Your voice stays on your phone, as it should be.**

[Install](#installation) · [Build](#building-from-source) · [Docs](docs/SUMMARY.md) · [Contributing](CONTRIBUTING.md) · [Roadmap](ROADMAP.md)

</div>

---

## ✨ Features

| Feature | OpenVoice | Google Assistant | Siri | Gemini |
|---------|-----------|-----------------|------|--------|
| Fully offline | ✅ | ❌ | ❌ | ❌ |
| Open source | ✅ | ❌ | ❌ | ❌ |
| No telemetry | ✅ | ❌ | ❌ | ❌ |
| Local AI models | ✅ | ❌ | ❌ | ❌ |
| Accessibility UI control | ✅ | ❌ | ❌ | ❌ |
| Screen understanding | ✅ | ❌ | ❌ | ❌ |
| Memory + knowledge graph | ✅ | ❌ | ❌ | ❌ |
| Autonomous planning | ✅ | ❌ | ❌ | ❌ |
| Plugin SDK | ✅ | ❌ | ❌ | ❌ |
| Encrypted storage | ✅ | ❌ | ❌ | ❌ |
| User controls data | ✅ | ❌ | ❌ | ❌ |
| Works on any Android | ✅ | ✅ | ❌ | ✅ |

## 🔒 Privacy Guarantees

- **Zero cloud services** — Everything runs on your device
- **Zero telemetry** — No analytics, no crash reporting, no usage stats
- **Zero network requests** — The app makes no hidden connections
- **AES-256-GCM encryption** — All memories encrypted at rest via Android Keystore
- **Full transparency** — Privacy dashboard shows every stored memory
- **You control your data** — Browse, edit, export, or delete anything. (WE DONT WANT YOUR DATA!!!)

## 🏗 Architecture

```
Audio → VAD → WakeWord → STT → Intent → Planner
                                            ├── Native API (fastest)
                                            ├── Accessibility
                                            ├── Memory / Knowledge Graph
                                            ├── OCR
                                            ├── Local LLM
                                            ├── Vision
                                            └── Plugins (in-process SDK)

Always uses the CHEAPEST capable subsystem first.
```

[Full architecture documentation](docs/architecture/overview.md)

## 🎯 Capabilities

### Voice Commands
- Open apps · Send messages · Make calls · Set timers/alarms · Adjust volume

### Device Control
- Settings navigation · Wi-Fi/Bluetooth management · Media control

### App Automation (via Accessibility)
- Tap buttons · Fill forms · Scroll · Navigate menus · Complete workflows

### Screen Understanding
- Read text (OCR) · Identify UI elements · Describe layouts · Visual analysis

### Memory & Learning
- Remembers preferences · Learns routines · Semantic search · Knowledge graph

### Autonomous Planning
- Multi-step tasks · Failure recovery · Capability escalation · Self-improvement

## 📱 Installation

### Pre-built APK
1. Download the latest APK from [Releases](https://github.com/CrossbowCraft13/openvoice/releases)
2. Enable "Install from unknown sources" in Android settings
3. Install the APK
4. Launch OpenVoice

### Requirements
- **Android**: 8.0 (API 26) or higher
- **RAM**: 4GB minimum (8GB recommended)
- **Storage**: 500MB for app + 2-8GB for AI models (optional)

## 🔧 Building from Source

### Prerequisites
- Android Studio Hedgehog (2023.1.1+) or later
- Java 17
- Android SDK Platform 35
- Android NDK 25.2.9519653
- CMake 3.22.1
- Gradle Wrapper 8.6 (included; no separate Gradle install required)

After cloning, run the read-only environment check:

```bash
./scripts/check-environment.sh
```

It reports missing tools and exact setup guidance before Gradle fails with a less actionable error.

### Build
```bash
git clone https://github.com/CrossbowCraft13/openvoice.git
cd openvoice
./gradlew assembleDebug
```

### Test
```bash
./gradlew testDebugUnitTest           # Unit tests
./gradlew connectedDebugAndroidTest   # Instrumentation tests
./gradlew lint                        # Lint check
```

## 🧠 AI Models

OpenVoice supports multiple local AI models. None are bundled — you choose what to download.

| Model | Purpose | Size | RAM |
|-------|---------|------|-----|
| Whisper (tiny/base) | Speech recognition | 75-150MB | 1GB+ |
| Piper TTS | Text-to-speech | 10-50MB | 512MB+ |
| OpenWakeWord | Wake word detection | 200KB | 256MB+ |
| Silero VAD | Voice activity detection | 5MB | 256MB+ |
| Qwen2 0.5B | Lightweight LLM | 380MB | 2GB+ |
| Gemma 2B | Small LLM | 1.4GB | 4GB+ |
| Phi-3 Mini | Medium LLM | 2.6GB | 6GB+ |
| Mistral 7B | Large LLM (Q4) | 4.4GB | 8GB+ |

Models are automatically recommended based on your device's RAM.

## 📊 Performance Targets

| Operation | Target | Measured |
|-----------|--------|----------|
| Wake word detection | <200ms | — |
| VAD latency | <5ms/frame | — |
| STT (1s audio) | <500ms | — |
| Intent classification | <10ms | ✓ <1ms |
| Memory retrieval | <50ms | — |
| Planning | <100ms | — |
| Cold start | <3s | — |
| Continuous listening battery | <5%/hr | — |

## 🛠 Technology Stack

| Component | Technology |
|-----------|------------|
| UI | Jetpack Compose, Material 3 |
| DI | Hilt |
| Database | Room / SQLite |
| STT | whisper.cpp (via JNI) |
| TTS | Piper (via ONNX Runtime) |
| LLM | llama.cpp (GGUF) |
| VAD | Silero (ONNX) |
| Wake Word | OpenWakeWord (TFLite) |
| OCR | ML Kit |
| Build | Gradle KTS, AGP |
| CI | GitHub Actions |

## 📚 Documentation

- [Architecture Overview](docs/architecture/overview.md)
- [Voice Pipeline](docs/architecture/voice-pipeline.md)
- [Planner & Execution](docs/architecture/planner.md)
- [Memory & Knowledge](docs/architecture/memory.md)
- [Developer Guide](docs/developer/getting-started.md)
- [Build Guide](docs/developer/build-guide.md)
- [Developer Setup](docs/developer/getting-started.md)
- [Model Guide](docs/developer/model-guide.md)
- [Plugin Development](docs/developer/plugin-development.md)
- [FAQ](docs/user/faq.md)

## 🤝 Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

- 📝 Submit [bug reports](.github/ISSUE_TEMPLATE/bug_report.yml)
- 💡 Suggest [features](.github/ISSUE_TEMPLATE/feature_request.yml)
- 🔧 Open a [pull request](.github/pull_request_template.md)

## 📋 Roadmap

- **v1.0.0-beta** — Current release (Phases 1-9 core complete)
- **v1.1.0** — Plugin marketplace, custom wake words
- **v2.0.0** — Multi-device sync, voice cloning

See [ROADMAP.md](ROADMAP.md) for details.

## 📄 License

Apache 2.0 — See [LICENSE](LICENSE) for details.

## 🙏 Acknowledgments

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) by Georgi Gerganov
- [llama.cpp](https://github.com/ggerganov/llama.cpp) by Georgi Gerganov
- [piper](https://github.com/rhasspy/piper) by Rhasspy/Home Assistant
- [OpenWakeWord](https://github.com/dscripka/openWakeWord) by David Scripka
- [Silero VAD](https://github.com/snakers4/silero-vad) by Silero Team
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) by Microsoft
- [Material 3](https://m3.material.io/) by Google
- All contributors and beta testers ❤️

---

<div align="center">
  <strong>Made with ❤️ for privacy and open source</strong><br>
  <sub>OpenVoice is not affiliated with Google, Apple, or OpenAI</sub>
</div>
