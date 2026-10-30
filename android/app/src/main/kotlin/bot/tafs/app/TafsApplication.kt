package bot.tafs.app

import android.app.Application
import android.content.pm.ApplicationInfo
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

  /** What KIFF's device list calls this phone, so the owner can tell it from their other devices. */
  val deviceName: String
    get() = "tafs on Android " + if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
}
