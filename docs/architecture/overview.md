# Architecture Overview

OpenVoice is designed as a modular, local-first AI assistant for Android. Every subsystem is independently replaceable through dependency injection and clear interfaces.

## Core Design Principles

1. **Local-first** — All processing happens on-device. No cloud dependency.
2. **Cheapest capable subsystem first** — Every request is handled by the fastest, most efficient capability that can fulfill it.
3. **Privacy by design** — No telemetry, no analytics, no hidden network requests.
4. **Modular** — Every component has a clear interface and can be swapped independently.
5. **Auditable** — ExplainMode provides full transparency into every decision.

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Voice Pipeline                           │
│  Audio → VAD → Wake Word → STT (Whisper.cpp) → Intent       │
└───────────────────────┬─────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│                    TaskBlackboard                             │
│  Shared context for all subsystems                           │
│  - User goal                                                │
│  - Current app/screen                                       │
│  - Action history                                           │
│  - Memory hits                                              │
│  - Planner state                                            │
└───────┬──────────────────────┬──────────────────┬───────────┘
        ↓                      ↓                  ↓
┌───────────────┐    ┌──────────────────┐  ┌──────────────┐
│   Planner      │    │  MemoryEngine    │  │ Perception   │
│   + ExecEngine │    │  + VectorStore   │  │ + OCR        │
│   + Replanner  │    │  + KnowledgeGraph│  │ + Vision     │
│   + CostModel  │    │  + Encryption    │  │ + Screenshot │
└───────┬───────┘    └────────┬─────────┘  └──────┬─────────┘
        ↓                     ↓                    ↓
┌─────────────────────────────────────────────────────────────┐
│                  Capability Selection                         │
│  1. Native Android API (0.10)   5.  Local LLM (0.70)        │
│  2. Accessibility (0.20)        6.  Vision Model (0.80)     │
│  3. Memory/Graph (0.15-0.60)   7.  Plugins (0.40)          │
│  4. OCR (0.30)                                              │
│                                                             │
│  Always selects the CHEAPEST capable subsystem.              │
└─────────────────────────────────────────────────────────────┘
```

## Subsystem Overview

### Voice Pipeline
Captures audio, detects voice activity, recognizes wake words, transcribes speech, and classifies intent. All audio processing is local and ephemeral — audio buffers are discarded after transcription.

### Planner
The assistant's "brain." Converts user goals into executable plans, selects the cheapest capability for each step, executes plans, handles failures, and learns from experience.

### Memory & Knowledge System
Two complementary retrieval systems:
- **Vector Store** — Semantic similarity search for "things that are semantically similar."
- **Knowledge Graph** — Triple store for "facts that are directly related."

Both are encrypted at rest with AES-256-GCM via Android Keystore.

### Perception
Multimodal screen understanding combining Accessibility tree, OCR, and vision models. The system always tries the cheapest source first (Accessibility → OCR → Vision).

### AI Runtime
Local LLM inference via llama.cpp (GGUF models). Provides prompt completion, chat, streaming, and embedding generation through a single `InferenceEngine` interface.

## Data Flow

### Simple Command: "Open Spotify"
```
Audio → VAD → WakeWord → STT("open spotify") → IntentClassifier
→ "LAUNCH_APP" → Planner → CostModel selects Native (0.10)
→ ExecutionEngine → OperatorRegistry → Intent.ACTION_VIEW → Spotify opens
→ ExplainMode: "Native was chosen because it's the fastest path"
```

### Complex Request: "Reply to this message and open Spotify"
```
Audio → VAD → WakeWord → STT → IntentClassifier
→ Planner decomposes: ["Reply to message", "Open Spotify"]
→ Step 1: Accessibility (0.20) — navigate to message, type reply, send
→ Step 2: Native (0.10) — LAUNCH_APP(spotify)
→ ExecutionEngine executes sequentially
→ DynamicReplanner handles any failures
```

### Memory Query: "What's my favorite music app?"
```
Audio → VAD → WakeWord → STT → IntentClassifier("QUERY")
→ Planner → CostModel selects MemoryRetrieval (0.15)
→ MemoryEngine.searchHybrid("favorite music app")
  → VectorStore.semantic("music app preference") → [Spotify: 0.87]
  → KnowledgeGraph.find("User", "prefers") → ["Spotify"]
→ TaskBlackboard.memoryHits = ["User prefers Spotify"]
→ Response: "Your preferred music app is Spotify."
```

## Threading Model

| Component | Thread | Notes |
|-----------|--------|-------|
| Audio capture | `Dispatchers.IO` | Real-time audio thread |
| VAD | `Dispatchers.Default` | Frame-by-frame processing |
| STT | `Dispatchers.IO` | Native inference via JNI |
| Planner | `Dispatchers.Default` | CPU-bound planning |
| Execution | `Dispatchers.IO` | Mixed I/O and UI operations |
| Memory | `Dispatchers.IO` | SQLite, vector index |
| Perception | `Dispatchers.IO` | Screenshot, OCR, vision |
| UI | Main thread | Compose rendering only |
| DI | Hilt-managed | Singleton components |

## Dependency Injection

Hilt is used throughout. Every subsystem is provided via `@Singleton` scoped modules:

- `AppModule` — Core pipeline components
- `AiModule` — AI Runtime
- `Phase4Module` — Accessibility Intelligence
- `Phase5Module` — Perception
- `Phase6Module` — Memory & Knowledge
- `Phase7Module` — Planner & Execution
- `Phase8Module` — System Integration

## Security Boundaries

- All memory data encrypted at rest (AES-256-GCM)
- Encryption keys stored in Android Keystore (never exported)
- No network communication
- No telemetry
- Permissions clearly explained during onboarding
- Accessibility actions are user-initiated
- JNI bridge validates all native inputs
