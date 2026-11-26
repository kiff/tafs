package bot.tafs.app

import android.os.Looper
import bot.tafs.app.push.PlayUpdates
import bot.tafs.app.push.UpdateStage
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home's update line follows Play's answer: offered, downloading, ready to restart, or nothing. The app decides no version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdatesTest {
  @Test fun aNewerVersionPlayAllowsAsFlexibleIsOffered() {
    assertEquals(UpdateStage.Available, UpdateStage.of(UpdateAvailability.UPDATE_AVAILABLE, true, InstallStatus.UNKNOWN))
  }

  @Test fun oneOnlyAnImmediateUpdateWouldInstallIsNotOffered() {
    assertEquals(UpdateStage.None, UpdateStage.of(UpdateAvailability.UPDATE_AVAILABLE, false, InstallStatus.UNKNOWN))
  }

  @Test fun noUpdateOrABuildPlayDidntInstallShowsNothing() {
    assertEquals(UpdateStage.None, UpdateStage.of(UpdateAvailability.UPDATE_NOT_AVAILABLE, true, InstallStatus.UNKNOWN))
    assertEquals(UpdateStage.None, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.UNKNOWN))
  }

  @Test fun aDownloadUnderWayReadsSoWhateverTheAvailability() {
    assertEquals(UpdateStage.Downloading, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.DOWNLOADING))
    assertEquals(UpdateStage.Downloading, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.PENDING))
  }

  @Test fun readyOnlyWhenPlayStillHasTheUpdate() {
    assertEquals(UpdateStage.Ready, UpdateStage.of(UpdateAvailability.UPDATE_AVAILABLE, true, InstallStatus.DOWNLOADED))
    assertEquals(UpdateStage.Ready, UpdateStage.of(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, false, InstallStatus.DOWNLOADED))
    // A "downloaded" left over from an update Play no longer has (already installed, or withdrawn): no Restart that can't work.
    assertEquals(UpdateStage.None, UpdateStage.of(UpdateAvailability.UPDATE_NOT_AVAILABLE, true, InstallStatus.DOWNLOADED))
    assertEquals(UpdateStage.None, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.DOWNLOADED))
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  @Test fun aRestartPlayCantDoAsksPlayAgain() {
    // Nothing downloaded: Play's completeUpdate fails with -7 (ERROR_DOWNLOAD_NOT_PRESENT), as on the owner's phone.
    val play = FakeAppUpdateManager(RuntimeEnvironment.getApplication())
    play.setUpdateAvailable(147)
    val updates = PlayUpdates(play)
    assertEquals(UpdateStage.None, updates.stage.value)
    updates.restart()
    idle()
    // The row follows Play's answer again: the update is offered, not left on a Restart that does nothing.
    assertEquals(UpdateStage.Available, updates.stage.value)
  }

  @Test fun aRestartWithNoUpdateLeftHidesTheRow() {
    val play = FakeAppUpdateManager(RuntimeEnvironment.getApplication())
    play.setUpdateNotAvailable()
    val updates = PlayUpdates(play)
    updates.restart()
    idle()
    assertEquals(UpdateStage.None, updates.stage.value)
  }

  @Test fun aDownloadThatCompletesIsReadyOncePlayConfirms() {
    val play = FakeAppUpdateManager(RuntimeEnvironment.getApplication())
    play.setUpdateAvailable(147)
    val updates = PlayUpdates(play)
    val info = play.appUpdateInfo.result
    play.startUpdateFlowForResult(info, { _, _, _, _, _, _, _ -> }, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(), 0)
    play.userAcceptsUpdate()
    play.downloadStarts()
    idle()
    assertEquals(UpdateStage.Downloading, updates.stage.value)
    play.downloadCompletes()
    idle()
    assertEquals(UpdateStage.Ready, updates.stage.value)
  }
}
