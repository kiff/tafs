package bot.tafs.app.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the owner left off, restored once for the process (docs/watch-wear.md): the screen and the Wear OS watch's relay share
 * one model, and whichever asks first restores it; the other, and every later ask, finds it done. One that failed, or was
 * cancelled, is asked again: only a restore that returned counts as done.
 *
 * Only the screen can answer a prompt, so a restore for the watch ([run] with `onScreen` false) does only what needs nobody,
 * and the screen's first ask after it finishes the rest ([finishOnScreen]) once. Nothing that needs the person runs where
 * nobody can answer, and a watch read never uses up what the screen would have asked.
 *
 * With a [scope] (the app's), the restore runs there, not in its caller: a caller that stops waiting (the watch's limit, a job
 * cut short) leaves it going, and the next caller joins it rather than starting it over.
 */
class RestoreOnce(
  private val restore: suspend (onScreen: Boolean) -> Unit,
  private val finishOnScreen: suspend () -> Unit = {},
  private val scope: CoroutineScope? = null,
) {
  private val lock = Mutex()
  @Volatile private var done = false
  @Volatile private var screenDone = false

  private val starting = Mutex()
  private var running: Deferred<Unit>? = null

  suspend fun run(onScreen: Boolean) {
    if (done && (screenDone || !onScreen)) return
    scope?.let { if (!done) join(it, onScreen) }
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

  /** The restore under way in [scope], or a new one there: one that failed or was cancelled is started again, never joined. */
  private suspend fun join(scope: CoroutineScope, onScreen: Boolean) {
    val restoring = starting.withLock {
      running?.takeIf { it.isActive } ?: scope.async {
        lock.withLock {
          if (!done) {
            restore(onScreen)
            done = true
            screenDone = onScreen
          }
        }
      }.also { running = it }
    }
    restoring.await()
  }
}
