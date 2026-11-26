package bot.tafs.app.push

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a newer version of tafs stands, as Play says: none, offered, downloading, or downloaded and waiting for a restart. */
enum class UpdateStage {
  None, Available, Downloading, Ready;

  companion object {
    /**
     * The stage from Play's answer: its availability, whether a flexible update is allowed, and an install under way. Ready only
     * when Play also says the update is there: a "downloaded" left over from an update Play no longer has (already installed,
     * or withdrawn) would offer a Restart that can do nothing (`ERROR_DOWNLOAD_NOT_PRESENT`).
     */
    fun of(availability: Int, flexibleAllowed: Boolean, installStatus: Int): UpdateStage = when {
      installStatus == InstallStatus.DOWNLOADED ->
        if (availability == UpdateAvailability.UPDATE_AVAILABLE || availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) Ready
        else None
      installStatus == InstallStatus.DOWNLOADING || installStatus == InstallStatus.PENDING -> Downloading
      availability == UpdateAvailability.UPDATE_AVAILABLE && flexibleAllowed -> Available
      else -> None
    }
  }
}

/**
 * Play's flexible in-app update: when the track has a newer tafs, Home offers it in one tap, it downloads in the background, and a
 * second tap restarts into it. Play decides what's newer and installs it; the app only asks and shows the stage. Nothing happens
 * on a build that didn't come from Play (a debug build, an emulator without Play), where Play answers with no update.
 */
class PlayUpdates(private val manager: AppUpdateManager) {
  constructor(activity: Activity) : this(AppUpdateManagerFactory.create(activity))

  private val _stage = MutableStateFlow(UpdateStage.None)
  val stage: StateFlow<UpdateStage> = _stage

  private val listener = InstallStateUpdatedListener { state ->
    // Downloaded: Play is asked whether the update is still there before Restart is offered.
    if (state.installStatus() == InstallStatus.DOWNLOADED) check()
    else _stage.value = UpdateStage.of(UpdateAvailability.UNKNOWN, false, state.installStatus())
  }

  init {
    manager.registerListener(listener)
  }

  /** Asks Play again, as the app comes to the front. When Play can't answer, nothing is offered. */
  fun check() {
    manager.appUpdateInfo
      .addOnSuccessListener { info ->
        _stage.value = UpdateStage.of(info.updateAvailability(), info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE), info.installStatus())
      }
      .addOnFailureListener { _stage.value = UpdateStage.None }
  }

  /** Update: Play's own confirmation, then the download in the background. */
  fun start(activity: Activity) {
    manager.appUpdateInfo.addOnSuccessListener { info ->
      if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
        runCatching { manager.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()) }
      }
    }
  }

  /**
   * Restart: Play installs what it downloaded and opens tafs again. When it can't (nothing downloaded, -7, or Play already up to
   * date), the row follows Play's answer again, so it never stays on a Restart that does nothing.
   */
  fun restart() {
    manager.completeUpdate().addOnCompleteListener { check() }
  }

  fun close() {
    manager.unregisterListener(listener)
  }
}

/** Home's update row: the stage, and what its one button does at each. Null where Play isn't asked (previews, tests). */
data class AppUpdateRow(val stage: UpdateStage, val update: () -> Unit, val restart: () -> Unit)

val LocalAppUpdate = androidx.compose.runtime.staticCompositionLocalOf<AppUpdateRow?> { null }

