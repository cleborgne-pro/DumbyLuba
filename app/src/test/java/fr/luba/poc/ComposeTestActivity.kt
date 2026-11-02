package fr.luba.poc

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric rule chain that declares the host activity of the Compose test rules ([ComponentActivity]) before
 * [composeRule] launches it. The checks and deployments test the fold variant, whose manifest has no test activity
 * (ui-test-manifest is a debug dependency, kept out of the APK installed on the Fold): without this declaration the
 * Compose tests could only run in debug, and were skipped by every check from 2026-10-29 to 2026-10-31.
 */
fun withComposeTestActivity(composeRule: TestRule): RuleChain = RuleChain.outerRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            base.evaluate()
        }
    }
}.around(composeRule)
