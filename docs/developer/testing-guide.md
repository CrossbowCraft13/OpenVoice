# Testing Guide

## Test Structure

```
app/src/
  test/                        # Unit tests (run on JVM)
  androidTest/                 # Instrumentation tests (run on device)
```

## Test Suites

| Test File | Type | Tests | Coverage |
|-----------|------|-------|----------|
| `PluginRegistryTest.kt` | JVM unit | 4 | Plugin registration, lifecycle, dispatch, failure isolation |
| `VoicePipelineTest.kt` | Instrumentation | 12 | Voice pipeline, operators |
| `AccessibilityAutomationTest.kt` | Instrumentation | 30+ | UI search, action commands |
| `AccessibilityIntelligenceTest.kt` | Instrumentation | 40+ | Search engine, blackboard, workflows |
| `AiRuntimeTest.kt` | Instrumentation | 25 | Profiler, settings, router, benchmarks |
| `PerceptionEngineTest.kt` | Instrumentation | 35+ | ScreenContext, fusion, cache, benchmarks |
| `MemoryEngineTest.kt` | Instrumentation | 30+ | Graph, encryption, lifecycle, benchmarks |
| `PlannerExecutionTest.kt` | Instrumentation | 35+ | CostModel, replanner, confirmation |
| `SystemIntegrationTest.kt` | Instrumentation | 30+ | Explain mode, resource manager, reliability |

## Running Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumentation tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Single test class
./gradlew testDebugUnitTest --tests "*PlannerExecutionTest*"

# Single test method
./gradlew testDebugUnitTest --tests "*PlannerExecutionTest.benchmark_costModelResolution*"

# All checks
./gradlew check
```

## Writing Tests

### Unit Test Example

```kotlin
@Test
fun intent_classifiesAppLaunch() = runBlocking {
    val classifier = IntentClassifier()
    val result = classifier.classify("open Spotify")
    assertEquals("LAUNCH_APP", result.intent)
    assertTrue(result.confidence > 0.8f)
}
```

### Benchmark Example

```kotlin
@Test
fun benchmark_routerSpeed() {
    val timings = mutableListOf<Long>()
    for (i in 0 until 100) {
        val start = System.nanoTime()
        router.resolve(intent)
        timings.add((System.nanoTime() - start) / 1_000)
    }
    val avgUs = timings.average().toLong()
    println("Router: avg=${avgUs}µs")
    assertTrue("Router < 1000µs", avgUs < 1000)
}
```

## Coverage Goals

| Package | Target Coverage |
|---------|----------------|
| `intent/` | 95%+ |
| `operator/` | 90%+ |
| `router/` | 95%+ |
| `vad/` | 85%+ |
| `audio/` | 80%+ |
| `task/` | 95%+ |
| `memory/` | 85%+ |
| `planner/` | 85%+ |
| `perception/` | 80%+ |
| `system/` | 80%+ |
| `plugin/` | 95%+ |
