# Frequently Asked Questions

## General

### What is OpenVoice?
OpenVoice is a private, local-first AI voice assistant for Android. It runs entirely on your device — no cloud services, no telemetry, no data leaves your phone.

### How is this different from Google Assistant or Siri?
Unlike commercial assistants, OpenVoice is fully open source, processes everything locally, has no telemetry or analytics, and gives you complete control over your data.

### Is it free?
Yes. OpenVoice is free and open source under the Apache 2.0 license.

## Privacy & Security

### Does OpenVoice send my voice recordings to a server?
No. All audio processing happens locally on your device. Audio is captured in short buffers (20ms frames), processed for speech recognition, and immediately discarded. Nothing is recorded, stored, or transmitted.

### Does OpenVoice collect analytics or telemetry?
No. OpenVoice has absolutely zero telemetry, analytics, crash reporting, or usage statistics. The app makes no network connections.

### Where are my memories stored?
All memories are stored locally on your device in an encrypted SQLite database. Encryption keys are stored in the Android Keystore and are never exported.

### Can I delete my data?
Yes. You can browse, edit, export, or delete any stored memory through the Privacy Dashboard.

## Setup

### What permissions does OpenVoice need?
- **Microphone**: For voice commands (required)
- **Notifications**: For background service (required)
- **Accessibility**: For app automation (optional)
- **SMS/Call**: For sending messages and making calls (optional)

### Why does OpenVoice need Accessibility Service?
OpenVoice uses the Android Accessibility Service to read your screen and perform actions in other apps (tapping buttons, filling forms, scrolling). This is optional and only used when you request screen interaction.

## Models

### Do I need to download AI models?
Yes. OpenVoice does not bundle AI models. You can download them through the app's Model Manager. Models include Whisper (STT), Piper (TTS), and optional LLMs for reasoning.

### What models are supported?
- **Whisper.cpp** (GGML/GGUF) for speech recognition
- **Piper TTS** (ONNX) for text-to-speech
- **OpenWakeWord** (TFLite) for wake word detection
- **Silero VAD** (ONNX) for voice activity detection
- **Llama.cpp** (GGUF) for local LLM reasoning
- **Florence-2 / SmolVLM** (ONNX) for vision

### How much RAM do I need?
- 4GB minimum for basic voice commands
- 6GB+ for local LLM (Qwen2 0.5B)
- 8GB+ for larger models (Gemma 2B, Phi-3)

## Compatibility

### What Android versions are supported?
Android 8.0 (API 26) and above.

### Does it work on tablets?
Yes, OpenVoice works on Android tablets.

### Does it work on Android Auto?
Not yet. This is on the roadmap.

## Troubleshooting

### Voice commands aren't working
1. Check that microphone permission is granted
2. Ensure you have a model downloaded in Model Manager
3. Check that the foreground service is running (look for the notification)
4. Try restarting the app

### Accessibility Automation isn't working
1. Enable OpenVoice in System Settings → Accessibility
2. Ensure the Accessibility Service toggle is on
3. The service requires manual enablement — OpenVoice cannot enable it automatically

### The app is using too much battery
1. Open the Resource Manager in Developer Console
2. Enable Battery Saver mode
3. Reduce wake word sensitivity
4. Disable continuous listening when not needed

## Contributing

### How can I contribute?
See [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines. We welcome bug reports, feature requests, documentation improvements, and code contributions.

### How do I build from source?
See the [Build Guide](../developer/build-guide.md).
