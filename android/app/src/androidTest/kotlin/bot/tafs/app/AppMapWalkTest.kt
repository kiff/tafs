package bot.tafs.app

import android.content.res.Configuration
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.model.AppRoute
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The app map's walk (docs/android-app.md, "The app map"): the app in the sample, every route in app/routes.json opened on the
 * activity's own model by its path, as `AppModel.open` takes it, and what each did written as JSON, which CI keeps. A route the
 * committed map (app/map/android.json, packed as this test's asset) says opens must show its English title, and the walk must
 * match that map: when a screen changes what it opens, the map is updated with it.
 */
class AppMapWalkTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  /** The sample this walk saves (MainActivity's SampleStore): removed after, unless one was there before, so later tests start as they did. */
  private val sample = File(instrumentation.targetContext.filesDir, "sample.json")
  private val sampleBefore = sample.exists()

  @After fun forgetTheSample() {
    if (!sampleBefore) sample.delete()
  }

  /** One route's line in the map. */
  private data class Visit(val route: String, val opened: Boolean, val titleShown: Boolean, val refusedBecause: String?) {
    fun json(): String = buildString {
      append("{\"route\": \"").append(route).append("\", \"opened\": ").append(opened).append(", \"title_shown\": ").append(titleShown)
      if (refusedBecause != null) append(", \"refused_because\": \"").append(refusedBecause).append('"')
      append('}')
    }
  }

  private fun map(visits: List<Visit>): String = buildString {
    append("{\n")
    append("  \"generated_by\": \"").append(GENERATED_BY).append("\",\n")
    append("  \"mode\": \"sample\",\n")
    append("  \"routes\": [\n")
    append(visits.sortedBy { it.route }.joinToString(",\n") { "    " + it.json() })
    append("\n  ]\n}\n")
  }

  /** The route's title as the app's English lines have it (app/strings/en), with the taf's name where it names one. */
  private fun englishTitle(route: AppRoute, tafName: String): String {
    val target = instrumentation.targetContext
    val english = target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    val id = R.string::class.java.getField(route.titleKey.replace('.', '_')).getInt(null)
    return english.resources.getString(id, tafName)
  }

  /** Whether the person may use [route] here, by what it needs, as `AppModel.open` asks. */
  private fun AppState.hasWhatItNeeds(route: AppRoute, tafId: String): Boolean = when (route.needs) {
    null -> true
    "manages" -> manages(tafId)
    "cards.manage" -> canManageCards
    "billing.manage" -> managesBilling
    "members.manage" -> canManagePeople
    "account.delete" -> canDeleteAccount
    else -> false
  }

  private fun roots(): Int = runCatching { rule.onAllNodes(isRoot()).fetchSemanticsNodes().size }.getOrDefault(0)

  /** Back to Home with nothing over it: back closes each sheet or dialog in its own window, then the Tafs route clears the rest. */
  private fun neutral(model: AppModel) {
    // Only while something is over the activity's own window: back on Home itself would leave the app.
    var tries = 0
    while (roots() > 1 && tries++ < 6) {
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      rule.waitForIdle()
    }
    instrumentation.runOnMainSync { model.routeTaken(); model.open(AppRoute.Tafs) }
    rule.waitForIdle()
  }

  @Test fun everyRouteInTheSampleMatchesTheMap() {
    rule.waitForIdle()
    lateinit var model: AppModel
    instrumentation.runOnMainSync {
      model = ViewModelProvider(rule.activity)[AppModel::class.java]
      model.startSample()
    }
    rule.waitUntil(30_000) { model.state.value.mode == Mode.sample && model.state.value.tafs.isNotEmpty() }
    rule.waitForIdle()
    val taf = model.state.value.tafs.first()

    val visits = AppRoute.entries.map { route ->
      neutral(model)
      val path = route.pattern.replace("{taf}", taf.id)
      var opened = false
      instrumentation.runOnMainSync { opened = model.open(path) }
      // The screens take what they open; a request nothing takes is still waited out, and read as it stands.
      runCatching { rule.waitUntil(5_000) { model.state.value.requestedRoute == null } }
      rule.waitForIdle()
      // A refused route opened nothing: what's on screen is the last one's, so its title isn't looked for.
      val shown = opened && rule.onAllNodes(hasText(englishTitle(route, taf.name), ignoreCase = true), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
      val because = if (opened) null else if (!model.state.value.hasWhatItNeeds(route, taf.id)) route.needs else SIGNED_IN_ONLY
      Visit(route.pattern, opened, shown, because)
    }
    neutral(model)

    val walked = map(visits)
    write(walked)

    val committed = instrumentation.context.assets.open(MAP_ASSET).bufferedReader().use { it.readText() }
    val expected = Json.parseToJsonElement(committed)
    val notShown = visits.filter { v -> v.opened && !v.titleShown }.map { it.route }
    assertTrue("These routes opened in the sample but their title wasn't on screen: $notShown\n$walked", notShown.isEmpty())
    assertEquals(
      "The walk differs from app/map/android.json. If the change is meant, replace that file with the walk's map (below, and in " +
        "the connected test's additional output, app-map-android.json):\n$walked",
      expected, Json.parseToJsonElement(walked) as JsonElement,
    )
  }

  /** The map to the test's additional output, which the connected run copies off the device, and to logcat between markers. */
  private fun write(walked: String) {
    InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { dir ->
      runCatching { File(dir).apply { mkdirs() }.resolve(OUTPUT_FILE).writeText(walked) }.onFailure { Log.w(TAG, "map not written", it) }
    }
    Log.i(TAG, BEGIN)
    walked.lineSequence().forEach { Log.i(TAG, it) }
    Log.i(TAG, END)
  }

  private companion object {
    const val GENERATED_BY = "android/app/src/androidTest/kotlin/bot/tafs/app/AppMapWalkTest.kt"
    const val MAP_ASSET = "android.json"
    const val OUTPUT_FILE = "app-map-android.json"
    const val SIGNED_IN_ONLY = "signed_in_only"
    const val TAG = "AppMap"
    const val BEGIN = "APP MAP BEGIN"
    const val END = "APP MAP END"
  }
}
