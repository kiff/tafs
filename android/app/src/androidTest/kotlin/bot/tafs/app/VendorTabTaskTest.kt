package bot.tafs.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner on Android 97: Google's sign-in opened, they went to their authenticator app for a code and came back to tafs, and the
 * sign-in read "The sign-in was closed" while Google's page was still waiting. The vendor's tab is in tafs's own task, so coming
 * back to tafs from Recents comes back to the tab, and the sign-in goes on.
 */
@RunWith(AndroidJUnit4::class)
class VendorTabTaskTest {
  private val app: TafsApplication get() = ApplicationProvider.getApplicationContext()

  private fun mainResumed(): Boolean {
    var resumed = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
    }
    return resumed
  }

  /**
   * A fresh emulator's Chrome opens its first-run screen, in a task of its own, instead of the Custom Tab: a phone's Chrome is past
   * it. Chrome reads its flags from /data/local/tmp/chrome-command-line for the debug app, so the test skips the first run there.
   */
  @Before fun chromeIsPastItsFirstRun() {
    val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    val (out, input) = ui.executeShellCommandRw("tee /data/local/tmp/chrome-command-line")
    android.os.ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("_ --disable-fre --no-default-browser-check --no-first-run\n".toByteArray()) }
    android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    for (command in listOf("am set-debug-app --persistent com.android.chrome", "am force-stop com.android.chrome")) {
      android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
    }
  }

  private fun tasks(): String {
    val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity activities")
    val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().readText()
    return text.lines().filter { ("Hist  #" in it || "* Task{" in it) && ("dev.kiff.tafs" in it || "chrome" in it) }.joinToString(" | ") { it.trim() }
  }

  @Test fun aSignInGoesOnAfterLeavingForAnotherAppAndComingBackFromRecents() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Opened as the owner opens it, from the launcher: the task's base intent is the launcher's.
    val launcher = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    app.startActivity(Intent(launcher))
    run {
      Thread.sleep(4_000)
      assertTrue("tafs is up", mainResumed())
            val signIn = scope.async { runCatching { app.vendorSignIn.signIn("https://example.com/") } }
      // Chrome may start cold: wait for its tab to take the screen, up to 20 s.
      repeat(40) { if (!mainResumed()) return@repeat; Thread.sleep(500) }
      Thread.sleep(2_000)
      assertTrue("the vendor's tab is in front: ${tasks()}", app.vendorSignIn.isWaiting && !mainResumed())
      // Away to another app (Home stands in for the authenticator), then back to tafs from Recents: its task, as it was.
      app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      Thread.sleep(2_000)
      app.getSystemService(android.app.ActivityManager::class.java).appTasks.first().moveToFront()
      Thread.sleep(4_000)
      assertFalse("Recents comes back to the vendor's tab, not its own screen: ${tasks()}", mainResumed())
      assertTrue("the sign-in still waits on the vendor", app.vendorSignIn.isWaiting)
      app.vendorSignIn.cancel()
      Thread.sleep(500)
      assertTrue(signIn.isCompleted)
    }
    scope.cancel()
  }

  // Back from the launcher's icon, tafs's screen closes the tab above it (singleTask): the sign-in is left, not dropped, and the
  // same page opens again.
  @Test fun backFromTheLauncherTheSignInIsLeftAndOpensAgain() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val launcher = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    app.startActivity(Intent(launcher))
    Thread.sleep(4_000)
    val signIn = scope.async { runCatching { app.vendorSignIn.signIn("https://example.com/") } }
    repeat(40) { if (!mainResumed()) return@repeat; Thread.sleep(500) }
    Thread.sleep(2_000)
    assertTrue("the vendor's tab is in front: ${tasks()}", app.vendorSignIn.isWaiting && !mainResumed())
    app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Thread.sleep(2_000)
    app.startActivity(Intent(launcher))
    Thread.sleep(4_000)
    // Either the launcher brought the task back as it was (the page in front), or tafs's screen closed the tab above it: then,
    // and only then, the sign-in is marked left. Either way it still waits.
    assertTrue("the sign-in still waits", app.vendorSignIn.isWaiting)
    val back = mainResumed()
    assertEquals("marked left exactly when tafs's own screen is in front: ${tasks()}", back, app.vendorSignIn.left.value)
    if (back) {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { app.vendorSignIn.reopen() }
      repeat(40) { if (!mainResumed()) return@repeat; Thread.sleep(500) }
      Thread.sleep(1_000)
      assertFalse("the page is in front again: ${tasks()}", mainResumed())
      assertFalse(app.vendorSignIn.left.value)
    }
    app.vendorSignIn.cancel()
    Thread.sleep(500)
    assertTrue(signIn.isCompleted)
    scope.cancel()
  }
}
