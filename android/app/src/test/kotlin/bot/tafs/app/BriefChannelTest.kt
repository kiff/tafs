package bot.tafs.app

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import bot.tafs.app.push.AlertNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Your brief is ready sounds: its channel is made at default importance, and the first, quiet one is gone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BriefChannelTest {
  @Test fun yourBriefsChannelSoundsAndTheQuietOneIsGone() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val manager = context.getSystemService(NotificationManager::class.java)
    // A phone that had the first, quiet channel.
    manager.createNotificationChannel(android.app.NotificationChannel(AlertNotifier.OLD_BRIEFS, "Briefs", NotificationManager.IMPORTANCE_LOW))
    AlertNotifier(context)
    assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(AlertNotifier.BRIEFS).importance)
    assertNull(manager.getNotificationChannel(AlertNotifier.OLD_BRIEFS))
  }
}
