package bot.tafs.app

import android.app.Application
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.launch
import bot.tafs.app.billing.NoPlayStore
import bot.tafs.app.billing.PlayBillingStore
import bot.tafs.app.billing.Purchases
import bot.tafs.app.kiff.BrowserVendorSignIn
import bot.tafs.app.kiff.KeystoreSecretStore
import bot.tafs.app.kiff.PlanqueClient
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SecretStore
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.kiff.UrlConnectionTransport
import bot.tafs.app.push.FirebasePush
import bot.tafs.app.signing.ApprovalSigning
import bot.tafs.app.signing.BiometricAuthenticator
import bot.tafs.app.signing.DeviceSigner
import bot.tafs.app.signing.KeystoreKeys

/**
 * What outlives a screen: the phone's keys, the way it asks the owner to confirm, and the KIFF
 * session. The signer stays across a rotation; the prompt shows on whichever activity is on screen.
 */
class TafsApplication : Application() {
  // The language picked in Account › Language, on Android 11 and 12, where the app keeps it (AppLocale); 13+ does it itself.
  override fun attachBaseContext(base: android.content.Context) {
    super.attachBaseContext(bot.tafs.app.design.AppLocale.wrap(base))
  }

  override fun onCreate() {
    super.onCreate()
    // A language picked on Android 12 goes on after the phone moves to 13, as its per-app language.
    bot.tafs.app.design.AppLocale.carryOver(this)
    // The words, in the language Android resolved for tafs, before anything is shown (docs/languages.md).
    bot.tafs.app.model.Res.install(bot.tafs.app.design.AndroidWords(this))
    // The lines that name this device say tablet on a large screen, as Android's own sw600dp does.
    bot.tafs.app.model.ThisDevice.tablet = resources.configuration.smallestScreenWidthDp >= 600
  }

  val authenticator: BiometricAuthenticator by lazy { BiometricAuthenticator(this) }

  /** Created when it first signs: the Keystore makes the keys then. */
  val signer: ApprovalSigning by lazy {
    // A debug build on a phone with no screen lock lets the demo through, as iOS's Simulator has no Face ID.
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    DeviceSigner(KeystoreKeys(), authenticator, demoWithoutLock = debuggable)
  }

  /** The KIFF session, kept on this phone only. */
  val secrets: SecretStore by lazy { KeystoreSecretStore(this) }

  private val transport by lazy { UrlConnectionTransport() }

  /** KIFF's public API, as any customer uses it. */
  val kiff: KiffClient by lazy { KiffClient(transport) }

  /** tafs cloud, which lists the account's tafs and their Cards from KIFF's. */
  val cloud: TafsCloud by lazy { TafsCloud(transport) }

  /** planque, which keeps the vendor sign-ins Coach's connections use. */
  val planque: PlanqueClient by lazy { PlanqueClient(transport) }

  /** The vendor's own sign-in page, in a browser tab. */
  val vendorSignIn: BrowserVendorSignIn by lazy { BrowserVendorSignIn(this) }
  /** KIFF's sign-in page in a Custom Tab, waiting for `dev.kiff.tafs:/oauth` (docs/onboarding.md, off). */
  val appSignInTab: BrowserVendorSignIn by lazy { BrowserVendorSignIn(this, privateBrowsing = true) }

  /** Firebase and the notification permission: alerts that reach this phone with tafs closed. */
  val push: FirebasePush by lazy { FirebasePush(this) }

  /** Buying through Google Play is in this build (res/values/flags.xml): off until KIFF records Play purchases. */
  val playBilling: Boolean get() = resources.getBoolean(R.bool.play_billing)

  /** Google Play's Billing Library where Play Billing is on; nothing otherwise, so no billing connection is opened. */
  val playStore: PlayBillingStore? by lazy { if (playBilling) PlayBillingStore(this) else null }

  val purchases: Purchases by lazy { Purchases(playStore ?: NoPlayStore) }

  /** The app's model, while one is alive: told when Firebase gives this phone a new push token. */
  @Volatile var tokenListener: ((String) -> Unit)? = null

  fun pushTokenArrived(token: String) {
    tokenListener?.invoke(token)
  }

  /** The app's model, while one is alive: told what changed on the account, to read it again. */
  @Volatile var changeListener: ((bot.tafs.app.push.Alert.Changed) -> Unit)? = null

  fun changeArrived(change: bot.tafs.app.push.Alert.Changed) {
    changeListener?.invoke(change)
  }

  /**
   * The app's one model for the process (docs/watch-wear.md): the screen draws it and the watch's relay reads it, so the
   * session, its hooks on [kiff] and [cloud], and what was saved are never two models' at once. Owned here, not by an
   * activity's ViewModelStore, so closing the screen doesn't end it while the relay still answers.
   */
  val model: bot.tafs.app.app.AppModel by lazy {
    bot.tafs.app.app.AppModel(
      bot.tafs.app.app.SampleStore(java.io.File(filesDir, "sample.json")), signer, kiff, cloud, secrets, deviceName = deviceName,
      push = push, planque = planque, vendorSignIn = vendorSignIn, appSignInTab = appSignInTab, purchases = purchases, playBilling = playBilling,
      openedFiles = java.io.File(cacheDir, "attachments"),
    ).also { model ->
      // Signed out: the watch forgets the account, now or when it's next in reach (docs/watch-wear.md, "What the watch keeps").
      model.watchSignedOut = { mark ->
        watchChats.forget()
        watchTafs.forget()
        watchNeedsYou.forget()
        // A sign-in ask waiting here was this account's: it goes with it.
        runCatching { model.watchSignInKeep?.clear() }
        background.launch { bot.tafs.app.watch.WatchSignedOut.of(this@TafsApplication).tell(mark) }
      }
      // A watch signed out on itself asks to be signed in again (row 18): the person is told, with the app closed too; the
      // question itself is asked in the app, behind the phone's lock, never in the notification. The ask is kept, so a process
      // the system ends before the person opens tafs still asks it.
      model.watchSignInKeep = bot.tafs.app.app.PreferencesWatchSignInKeep(this)
      model.watchSignInAsked = { watchSignInNotice() }
      // Their answer goes to the watch, for that ask alone.
      model.watchSignInAnswered = { ask, allowed, mark ->
        runCatching { getSystemService(android.app.NotificationManager::class.java)?.cancel(WATCH_SIGN_IN_TAG, 1) }
        background.launch {
          runCatching {
            bot.tafs.app.watch.DataLayerKeep(this@TafsApplication).put(
              bot.tafs.watchlink.WatchLink.KEPT_SIGN_IN,
              bot.tafs.watchlink.WatchLink.encodeKept(bot.tafs.watchlink.KeptSignIn(System.currentTimeMillis(), ask, allowed, mark?.takeIf(bot.tafs.watchlink.WatchLink::isMark))),
            )
          }
        }
      }
    }
  }

  /** "Sign this watch in as you?", as a notification that opens the app, where it's answered. */
  private fun watchSignInNotice() {
    bot.tafs.app.push.AlertNotifier(this)
    val open = android.app.PendingIntent.getActivity(
      this, WATCH_SIGN_IN_TAG.hashCode(), android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
      android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val shown = androidx.core.app.NotificationCompat.Builder(this, bot.tafs.app.push.AlertNotifier.HOLDS)
      .setSmallIcon(R.drawable.ic_bell)
      .setContentTitle(bot.tafs.app.model.Words.Watch.signInWatchQ)
      .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(WATCH_SIGN_IN_TAG, 1, shown) }
  }

  /** Needs you, kept for the watch as it changes ([bot.tafs.app.watch.WatchNeedsYou]): a held call reaches the wrist at once. */
  val watchNeedsYou: bot.tafs.app.watch.WatchNeedsYou by lazy {
    bot.tafs.app.watch.WatchNeedsYou(bot.tafs.app.watch.DataLayerKeep(this), mark = { model.watchMark() })
      .also { it.follow(model.state, background) }
  }

  /** The taf list, kept for the watch as it changes ([bot.tafs.app.watch.WatchTafs]), once the watch has used tafs. */
  val watchTafs: bot.tafs.app.watch.WatchTafs by lazy {
    bot.tafs.app.watch.WatchTafs(bot.tafs.app.watch.DataLayerKeep(this), mark = { model.watchMark() })
      .also { it.follow(model.state, background) }
  }

  /**
   * The process's own work, outliving any one caller: a restore or a read the watch asked for goes on here once the watch has
   * stopped waiting, so the next ask finds it done or joins it (docs/watch-wear.md, Delivery).
   */
  val background: kotlinx.coroutines.CoroutineScope =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

  /**
   * The taf chats the watch opened, kept for it as they change here (docs/watch-wear.md, Delivery): made, and following the model's
   * threads, at the watch's first read.
   */
  val watchChats: bot.tafs.app.watch.WatchChats by lazy {
    bot.tafs.app.watch.WatchChats(bot.tafs.app.watch.DataLayerKeep(this), zone = { bot.tafs.app.model.zone.id },
      store = bot.tafs.app.watch.WatchChats.OpenedStore.Preferences(this), mark = { model.watchMark() })
      .also { it.follow(bot.tafs.app.watch.WatchChats.of(model.state), background) }
  }

  private val restoring = bot.tafs.app.app.RestoreOnce(
    restore = { onScreen -> model.restore(onScreen) },
    finishOnScreen = { model.finishRestoreOnScreen() },
    scope = background,
  )

  /**
   * [model], with where the owner left off restored once for the process, whoever asks first: the screen ([onScreen]) or the
   * watch. The watch's restore asks the person nothing; the screen's first ask after it finishes what needs them.
   */
  suspend fun restoredModel(onScreen: Boolean): bot.tafs.app.app.AppModel {
    restoring.run(onScreen)
    return model
  }

  /** What KIFF's device list calls this phone, so the owner can tell it from their other devices. */
  val deviceName: String
    get() = bot.tafs.app.model.Words.Settings.Devices.thisDeviceName(device = bot.tafs.app.model.Words.Settings.Devices.androidDevice(device = bot.tafs.app.model.ThisDevice.word))
}

/** The notification that asks to sign a watch in again: one at a time, taken away once answered. */
private const val WATCH_SIGN_IN_TAG = "watch-sign-in"
