# Getting Started as a Developer

Welcome to OpenVoice development! This guide will help you understand the codebase and start contributing.

## First-Time Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/openvoice.git
   cd openvoice
   ```

2. **Open in Android Studio**
   - File → Open → Select the `openvoice` directory
   - Wait for Gradle sync to complete

3. **Set up the Android SDK**
   - Tools → SDK Manager → Install SDK 35 + NDK 25+

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```
   Then install on your device or emulator.

## Project Map

The project follows Clean Architecture with Hilt dependency injection.

### Entry Points

| File | Purpose |
|------|---------|
| `OpenVoiceApplication.kt` | Application class, Hilt initialization |
| `ui/MainActivity.kt` | Main Compose activity |
| `service/AssistantService.kt` | Foreground service for background operation |

### Core Modules

| Package | Responsibility |
|---------|---------------|
| `audio/` | Real-time audio capture, 16kHz PCM |
| `vad/` | Voice activity detection (Silero ONNX + energy fallback) |
| `wakeword/` | Wake word detection (OpenWakeWord TFLite) |
| `stt/` | Speech-to-text (Whisper.cpp JNI) |
| `tts/` | Text-to-speech (Piper ONNX Runtime) |
| `intent/` | Regex-based intent classification |
| `router/` | Capability routing (cheapest path selection) |
| `operator/` | Android API operators (7 built-in) |
| `pipeline/` | Voice pipeline orchestration |
| `accessibility/` | Accessibility automation + Intelligence |
| `perception/` | Multimodal screen understanding |
| `memory/` | Memory engine, vector store, knowledge graph |
| `planner/` | Planning, execution, recovery, learning |
| `task/` | TaskBlackboard — shared context |
| `ai/` | Local LLM inference, model management, device profiling |
| `system/` | Orchestration, resource management, explain mode |
| `developer/` | Developer console, live diagnostics |
| `ui/` | Material 3 Compose UI |
| `di/` | Hilt DI modules |
| `util/` | Logging, permissions |

## Key Interfaces

Every major subsystem has a clean interface that can be swapped via DI:

| Interface | Implementation | DI Module |
|-----------|---------------|-----------|
| `InferenceEngine` | LlamaCppBridge + InferenceEngine | AiModule |
| `MemoryEngine` | SQLite + VectorStore + KnowledgeGraph | Phase6Module |
| `Planner` | Planner + CostModel | Phase7Module |
| `ExecutionEngine` | ExecutionEngine + DynamicReplanner | Phase7Module |
| `PerceptionEngine` | Screenshot + OCR + Vision fusion | Phase5Module |
| `CapabilityRouter` | Cost-based selection | AppModule |

## Adding a New Feature

1. Create your interface in the appropriate package
2. Implement the interface
3. Create a Hilt `@Module` providing it
4. Register with the Planner if it's a new capability
5. Add tests
6. Add documentation

## Testing

```bash
# Run all tests
./gradlew testDebugUnitTest

# Run specific test
./gradlew testDebugUnitTest --tests "*AccessibilityIntelligenceTest*"

# Run instrumentation tests
./gradlew connectedDebugAndroidTest

# Check test coverage
./gradlew koverReport
```
