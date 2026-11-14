package bot.tafs.app.design

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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

  /** Whether the phone already said once that it played no feedback: said in the diagnostics once per launch. */
  @Volatile private var saidNone = false

  /** [kind] as a predefined effect marked as touch, on Android 13+: true when the phone has a vibrator and it was asked. */
  private fun touchEffect(context: Context, kind: Kind): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return false
    if (!vibrator.hasVibrator()) return false
    val effect = VibrationEffect.createPredefined(
      when (kind) {
        Kind.Tap, Kind.Step -> VibrationEffect.EFFECT_TICK
        Kind.Success -> VibrationEffect.EFFECT_CLICK
        Kind.Warning -> VibrationEffect.EFFECT_DOUBLE_CLICK
      },
    )
    return runCatching { vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)); true }.getOrDefault(false)
  }

  /**
   * The platform's own feedback for each kind, on [view] (Android 11+: confirm and reject exist). Where the view plays none
   * (some phones keep the standard touch feedback switch off and use their own touch slider: ColorOS), Android 13+ plays the
   * same kind as a predefined effect marked as touch (`USAGE_TOUCH`), so the phone's own touch intensity decides, and off
   * there is still off. Nothing played at all is said once in the diagnostics, so a "nothing vibrates" report can be told
   * apart from a tap the app never asked for.
   */
  fun on(view: View): (Kind) -> Unit = { kind ->
    view.post {
      val played = view.performHapticFeedback(
        when (kind) {
          Kind.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
          Kind.Success -> HapticFeedbackConstants.CONFIRM
          Kind.Warning -> HapticFeedbackConstants.REJECT
          Kind.Step -> HapticFeedbackConstants.CLOCK_TICK
        },
      ) || touchEffect(view.context, kind)
      if (!played && !saidNone) {
        saidNone = true
        // The only log Coach and a tester can read (docs/drive.md, diagnostics). false also comes from a view not attached
        // yet, or one with feedback off, so the line names both.
        bot.tafs.app.drive.DriveDiagnostics.error("haptics: no feedback played (view or phone setting, ${kind.name})")
      }
    }
  }
}
