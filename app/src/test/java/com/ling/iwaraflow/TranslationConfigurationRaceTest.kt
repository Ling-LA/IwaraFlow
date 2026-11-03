package com.ling.iwaraflow

import android.app.Application
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No network or timing guesses: hold the startup settings read until the explicit choice finishes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TranslationConfigurationRaceTest.IsolatedApplication::class)
class TranslationConfigurationRaceTest {
    class IsolatedApplication : Application()

    private lateinit var previous: TranslationConfig
    private val initial = TranslationConfig(provider = Translator.PROVIDER_CUSTOM,
        endpoint = "https://initial.example.invalid/translate")
    private val persisted = TranslationConfig(provider = Translator.PROVIDER_CUSTOM,
        endpoint = "https://persisted.example.invalid/translate")
    private val selected = TranslationConfig(provider = Translator.PROVIDER_CUSTOM,
        endpoint = "https://selected.example.invalid/translate")

    @Before fun prepare() {
        previous = Translator.config
        Translator.configure(initial)
    }

    @After fun restore() {
        Translator.configure(previous)
    }

    private fun completeDelayedStartup(whileReadIsPending: () -> Unit): Boolean {
        // Application takes this snapshot before creating its reader thread.
        val generation = Translator.startupConfigurationGeneration()
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                reading.countDown()
                check(finishRead.await(5, TimeUnit.SECONDS)) { "Startup read was not released" }
                Translator.configureFromStartup(persisted, generation)
            }
            assertTrue("Startup reader did not reach its barrier", reading.await(5, TimeUnit.SECONDS))
            whileReadIsPending()
            finishRead.countDown()
            return result.get(5, TimeUnit.SECONDS)
        } finally {
            finishRead.countDown()
            executor.shutdownNow()
            assertTrue("Startup reader must not leak into another test", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun lateStartupReadCannotOverrideAnExplicitConfigurationChange() {
        assertFalse(completeDelayedStartup { Translator.configure(selected) })
        assertEquals(selected, Translator.config)
    }

    @Test fun normalStartupReadStillAppliesWhenThereWasNoExplicitChange() {
        assertTrue(completeDelayedStartup { assertEquals(initial, Translator.config) })
        assertEquals(persisted, Translator.config)
    }

    @Test fun explicitlyKeepingTheSameConfigurationAlsoRejectsTheOldStartupRead() {
        assertFalse(completeDelayedStartup { Translator.configure(initial.copy()) })
        assertEquals(initial, Translator.config)
    }
}
