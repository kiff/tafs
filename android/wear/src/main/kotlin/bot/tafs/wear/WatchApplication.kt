package bot.tafs.wear

import android.app.Application

/** The watch app: its words, from its own resources, before anything is shown. */
class WatchApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    Res.install(this)
  }
}
