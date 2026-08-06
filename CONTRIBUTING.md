# Contributing to OpenVoice

First off, thank you for considering contributing to OpenVoice. It's because of people like you that this project can become a genuine alternative to proprietary voice assistants.

## Code of Conduct

This project and everyone participating in it is governed by the [OpenVoice Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check the existing issues list. When you create a bug report, include as many details as possible:

- **Use a clear and descriptive title**
- **Describe the exact steps to reproduce the problem**
- **Describe the behavior you observed and why it's wrong**
- **Include screenshots or screen recordings if possible**
- **Include device information** (model, Android version, RAM)
- **Include OpenVoice version and model versions**

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating one:

- **Use a clear and descriptive title**
- **Provide a step-by-step description of the suggested enhancement**
- **Explain why this enhancement would be useful**
- **List any alternative solutions you've considered**

### Adding a New Skill/Plugin

See [Plugin Development Guide](docs/developer/plugin-development.md).

### Improving Documentation

Documentation improvements are always welcome. You can edit documentation files directly and submit a pull request.

## Development Process

### Setting Up the Development Environment

1. Install Android Studio Hedgehog (2023.1.1) or later.
2. In SDK Manager install Android SDK Platform 35, Android SDK Build-Tools, NDK 25+, and CMake 3.22.1.
3. Use Java 17. Android Studio includes a compatible JDK; command-line builds require `JAVA_HOME` to point to JDK 17.
4. Clone the repository and open it in Android Studio.
5. Run the read-only prerequisite check:
   ```bash
   ./scripts/check-environment.sh
   ```
6. Wait for Gradle sync to complete, then build with `./gradlew assembleDebug`.

### Building from Source

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumentation tests
./gradlew connectedDebugAndroidTest

# Run lint
./gradlew lint

```

### Project Structure

```
OpenVoice/
├── app/                          # Android application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/openvoice/
│   │   │   │   ├── ai/           # AI Runtime & Inference
│   │   │   │   ├── audio/        # Audio capture
│   │   │   │   ├── vad/          # Voice activity detection
│   │   │   │   ├── wakeword/     # Wake word detection
│   │   │   │   ├── stt/          # Speech-to-text
│   │   │   │   ├── tts/          # Text-to-speech
│   │   │   │   ├── intent/       # Intent classification
│   │   │   │   ├── router/       # Capability routing
│   │   │   │   ├── operator/     # Android operators
│   │   │   │   ├── pipeline/     # Voice pipeline
│   │   │   │   ├── accessibility/ # Accessibility automation
│   │   │   │   ├── perception/   # Screen understanding
│   │   │   │   ├── memory/       # Memory & knowledge graph
│   │   │   │   ├── planner/      # Planning & execution
│   │   │   │   ├── task/         # Task blackboard
│   │   │   │   ├── service/      # Background services
│   │   │   │   ├── developer/    # Developer console
│   │   │   │   ├── system/       # System integration
│   │   │   │   ├── ui/           # Compose UI
│   │   │   │   ├── di/           # Dependency injection
│   │   │   │   └── util/         # Utilities
│   │   │   └── cpp/              # Native JNI code
│   │   ├── test/                 # Unit tests
│   │   └── androidTest/          # Instrumentation tests
│   ├── build.gradle.kts
│   └── CMakeLists.txt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                 # Gradle 8.6 wrapper metadata
└── scripts/check-environment.sh    # Read-only prerequisite check
```

### Branch Organization

- `main` — Stable, release-ready code
- `develop` — Integration branch for features
- `feature/*` — Feature branches (branch from `develop`)
- `fix/*` — Bug fix branches
- `release/*` — Release preparation branches

### Pull Request Process

1. Fork the repository
2. Create a feature branch from `develop`
3. Make your changes
4. Write or update tests as needed
5. Run all tests locally
6. Ensure lint passes
7. Update documentation if needed
8. Submit a pull request to `develop`

### PR Requirements

- All CI checks must pass
- Test coverage should not decrease
- No hardcoded cloud service URLs
- No telemetry or analytics code
- All new features must be local-first
- Documentation must be updated

### Coding Standards

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Write documentation for public APIs
- Keep functions small and focused
- Prefer immutability
- Handle errors gracefully
- Log with appropriate levels

### Testing Standards

Before submitting changes, run `./scripts/check-environment.sh` so missing Java, SDK, NDK, CMake, or Gradle prerequisites are reported clearly.

- Unit tests for all business logic
- Instrumentation tests for UI and integration
- Performance benchmarks for critical paths
- Test edge cases and error conditions
- Mock external dependencies in unit tests

## Getting Help

- Open a [Discussion](https://github.com/CrossbowCraft13/openvoice/discussions)
- Join our community chat
- Check the [Documentation](docs/)
- Read the [FAQ](docs/user/faq.md)

## Recognition

Contributors will be recognized in:
- The README contributors section
- Release notes
- Project website

Thank you for helping make OpenVoice a privacy-first, local AI assistant for everyone.
