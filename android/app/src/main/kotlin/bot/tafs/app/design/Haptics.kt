package bot.tafs.app.design

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The app's taps (docs/android-app.md, "Haptics"), the same rule as on iPhone and iPad: a light tap on every button press, a
 * success tick when an approval or a send goes through, a warning tick when something is refused or fails, and a step tick on a
 * selector's stops. All of it off when Haptics is off in Settings, on this phone.
 */
object Haptics {
  enum class Kind { Tap, Success, Warning, Step }

  private const val FILE = "tafs.display"
  private const val KEY = "haptics"

  /** Haptics in Settings, on this phone: on until turned off. */
  @Volatile var enabled: Boolean = true
    private set

  /** What makes the tap: the activity's window, set when it starts. A test puts its own here. */
  @Volatile var performer: ((Kind) -> Unit)? = null

  fun load(context: Context) {
    enabled = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
  }

  fun set(context: Context, on: Boolean) {
    enabled = on
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
  }

  /** For tests: the switch without a phone's storage. */
  internal fun setEnabledForTest(on: Boolean) { enabled = on }

  fun tap() = fire(Kind.Tap)
  fun success() = fire(Kind.Success)
  fun warning() = fire(Kind.Warning)
  fun step() = fire(Kind.Step)

  private fun fire(kind: Kind) {
    if (!enabled) return
    runCatching { performer?.invoke(kind) }
  }

  /** The platform's own feedback for each kind, on [view] (Android 11+: confirm and reject exist). */
  fun on(view: View): (Kind) -> Unit = { kind ->
    view.post {
      view.performHapticFeedback(
        when (kind) {
          Kind.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
          Kind.Success -> HapticFeedbackConstants.CONFIRM
          Kind.Warning -> HapticFeedbackConstants.REJECT
          Kind.Step -> HapticFeedbackConstants.CLOCK_TICK
        },
      )
    }
  }
}
