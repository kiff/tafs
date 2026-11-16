package bot.tafs.app.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the owner left off, restored once for the process (docs/watch-wear.md): the screen and the Wear OS watch's relay share
 * one model, and whichever asks first restores it; the other, and every later ask, finds it done. One that failed is asked again.
 *
 * Only the screen can answer a prompt, so a restore for the watch ([run] with `onScreen` false) does only what needs nobody,
 * and the screen's first ask after it finishes the rest ([finishOnScreen]) once. Nothing that needs the person runs where
 * nobody can answer, and a watch read never uses up what the screen would have asked.
 */
class RestoreOnce(
  private val restore: suspend (onScreen: Boolean) -> Unit,
  private val finishOnScreen: suspend () -> Unit = {},
) {
  private val lock = Mutex()
  @Volatile private var done = false
  @Volatile private var screenDone = false

  suspend fun run(onScreen: Boolean) {
    if (done && (screenDone || !onScreen)) return
    lock.withLock {
      if (!done) {
        restore(onScreen)
        done = true
        screenDone = onScreen
      } else if (onScreen && !screenDone) {
        finishOnScreen()
        screenDone = true
      }
    }
  }
}
