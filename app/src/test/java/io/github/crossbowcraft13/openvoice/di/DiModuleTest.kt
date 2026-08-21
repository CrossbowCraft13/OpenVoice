package io.github.crossbowcraft13.openvoice.di

import io.github.crossbowcraft13.openvoice.intent.IntentClassifier
import io.github.crossbowcraft13.openvoice.intent.IntentResult
import io.github.crossbowcraft13.openvoice.operator.OperatorRegistry
import io.github.crossbowcraft13.openvoice.router.CapabilityRouter
import io.github.crossbowcraft13.openvoice.router.Resolution
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the context-free Hilt @Provides bindings directly so the DI wiring
 * itself carries some coverage (the graph as a whole is verified by the app
 * booting + the instrumented suite).
 */
class DiModuleTest {

    @Test
    fun pureAppModuleProvidersConstruct() {
        assertNotNull(AppModule.provideCapabilityRouter())
        assertNotNull(AppModule.provideIntentClassifier())
        assertNotNull(AppModule.provideOperatorRegistry())
    }

    @Test
    fun routerProviderReturnsResolvableRouter() {
        val router = AppModule.provideCapabilityRouter()
        assertTrue(router.resolve(IntentResult("SET_TIMER", 0.9f, emptyMap())) is Resolution.Native)
    }

    @Test
    fun intentClassifierProviderClassifies() {
        val classifier = AppModule.provideIntentClassifier()
        assertTrue(classifier is IntentClassifier)
    }

    @Test
    fun operatorRegistryProviderIsRegistered() {
        val registry = AppModule.provideOperatorRegistry()
        assertTrue(registry is OperatorRegistry)
        assertTrue(registry.ids().contains("HELP"))
    }
}
