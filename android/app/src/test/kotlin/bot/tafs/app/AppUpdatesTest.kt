package bot.tafs.app

import bot.tafs.app.push.UpdateStage
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's update line follows Play's answer: offered, downloading, ready to restart, or nothing. The app decides no version. */
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

  @Test fun aDownloadUnderWayThenDoneReadsSoWhateverTheAvailability() {
    assertEquals(UpdateStage.Downloading, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.DOWNLOADING))
    assertEquals(UpdateStage.Downloading, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.PENDING))
    assertEquals(UpdateStage.Ready, UpdateStage.of(UpdateAvailability.UNKNOWN, false, InstallStatus.DOWNLOADED))
  }
}
