package bot.tafs.app.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the owner left off, restored once for the process (docs/watch-wear.md): the screen and the Wear OS watch's relay share
 * one model, and whichever asks first restores it; the other, and every later ask, finds it done. One that failed is asked again.
 */
class RestoreOnce(private val restore: suspend () -> Unit) {
  private val lock = Mutex()
  @Volatile private var done = false

  suspend fun run() {
    if (done) return
    lock.withLock {
      if (!done) {
        restore()
        done = true
      }
    }
  }
}
