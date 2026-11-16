package bot.tafs.app

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * The app's one model (docs/watch-wear.md): the screen draws the same instance the Wear OS watch's relay reads, and it outlives
 * the activity, so a relay asking after the person backed out of the app finds a model whose work still runs.
 */
class SharedModelTest {
  @get:Rule val awake = AwakeDevice()

  private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TafsApplication

  @Test fun theScreensModelIsTheRelays() {
    val model = app.model
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { }
      runBlocking { assertSame(model, app.restoredModel()) }
    }
  }

  @Test fun itsWorkStillRunsAfterTheActivityFinishes() {
    ActivityScenario.launch(MainActivity::class.java).close()
    val ran = CompletableDeferred<Unit>()
    app.model.viewModelScope.launch { ran.complete(Unit) }
    runBlocking { withTimeout(5_000) { ran.await() } }
  }
}
