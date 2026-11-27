package bot.tafs.app.signing

import bot.tafs.app.model.Words
import android.content.Context
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import java.security.Signature
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Asks with the system's own prompt: fingerprint or face, else the screen lock. The prompt shows
 * on the activity that is on screen, which attaches itself; the app's state outlives an activity,
 * so this holds it weakly.
 */
class BiometricAuthenticator(private val context: Context) : Authenticator {
  private var activity: WeakReference<FragmentActivity>? = null

  fun attach(activity: FragmentActivity) {
    this.activity = WeakReference(activity)
  }

  fun detach(activity: FragmentActivity) {
    if (this.activity?.get() === activity) this.activity = null
  }

  override val canAuthenticate: Boolean
    get() = BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

  override suspend fun authenticate(reason: String): Boolean = prompted { ask(reason) }

  override suspend fun authenticate(reason: String, signature: Signature): Boolean = prompted { ask(reason, signature) }

  /** The system's prompt, which Coach can wait on but never reach (docs/drive.md): shown, then done. */
  private suspend fun prompted(block: suspend () -> Boolean): Boolean {
    bot.tafs.app.drive.DriveSignals.emit("prompt_shown")
    try {
      return block()
    } finally {
      bot.tafs.app.drive.DriveSignals.emit("prompt_done")
    }
  }

  private suspend fun ask(reason: String): Boolean = suspendCancellableCoroutine { cont ->
    val host = activity?.get()
    if (host == null) {
      cont.resume(false)
      return@suspendCancellableCoroutine
    }
    val prompt = BiometricPrompt(
      host,
      ContextCompat.getMainExecutor(host),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          if (cont.isActive) cont.resume(true)
        }

        // A face or finger that doesn't match leaves the prompt up for another try; an error ends it.
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (cont.isActive) ended(cont, errorCode)
        }
      },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(reason)
      .apply { promptDescription(reason)?.let(::setDescription) }
      .setAllowedAuthenticators(ALLOWED)
      .build()
    prompt.authenticate(info)
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
  }

  private suspend fun ask(reason: String, signature: Signature): Boolean = suspendCancellableCoroutine { cont ->
    val host = activity?.get()
    if (host == null) {
      cont.resume(false)
      return@suspendCancellableCoroutine
    }
    val prompt = BiometricPrompt(
      host,
      ContextCompat.getMainExecutor(host),
      object : BiometricPrompt.AuthenticationCallback() {
        // The Keystore unlocked this signature for one use: the one the caller holds.
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          if (cont.isActive) cont.resume(result.cryptoObject?.signature === signature)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (cont.isActive) ended(cont, errorCode)
        }
      },
    )
    // A strong biometric only: the screen lock never approves, so the prompt offers Cancel in its place.
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(reason)
      .apply { promptDescription(reason)?.let(::setDescription) }
      .setAllowedAuthenticators(BIOMETRIC_STRONG)
      .setNegativeButtonText(Words.Common.cancel)
      .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
  }

  /** How the prompt ended without a match: closed by the person or the system ([PromptClosed]), or anything else (false). */
  private fun ended(cont: kotlinx.coroutines.CancellableContinuation<Boolean>, errorCode: Int) {
    if (closed(errorCode)) cont.resumeWithException(PromptClosed()) else cont.resume(false)
  }

  companion object {

    /** A biometric the phone counts as strong, or the screen lock: what signs with the signing key asks with either. */
    const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * The prompt's errors that mean it was closed, not that a finger failed: Cancel, back or a tap outside, or the system
     * closing it (another app, the screen going off). A lockout, a timeout or a sensor error is not one of them.
     */
    fun closed(errorCode: Int): Boolean = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
      errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
      errorCode == BiometricPrompt.ERROR_CANCELED
  }
}

/**
 * What this phone asks for before it signs, in the words the owner knows (kiff/tafs#87): for "Approve once with …". An
 * approval takes a strong biometric only, and face unlock on most Android phones isn't one, so a phone with a fingerprint
 * sensor names the fingerprint alone; face unlock only where there's no sensor. With no strong biometric enrolled it's the
 * screen lock, which signs what takes authority away; approving then says to add a fingerprint.
 */
object Biometry {
  fun name(strongEnrolled: Boolean, face: Boolean, fingerprint: Boolean): String = when {
    !strongEnrolled -> Words.Approval.Biometry.screenLock
    fingerprint -> Words.Approval.Biometry.fingerprint
    face -> Words.Approval.Biometry.face
    else -> Words.Approval.Biometry.screenLock
  }

  /**
   * What an approve button names, from [name]: never the screen lock, which never approves. A real approve button sits in
   * `ApprovalGate`, which asks for a fingerprint in its place; a picture of one (the first-run card) names the fingerprint.
   */
  fun approvingWith(name: String): String = if (name == Words.Approval.Biometry.screenLock) Words.Approval.Biometry.fingerprint else name

  fun of(context: Context): String {
    val strong = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    val pm = context.packageManager
    return name(strong, pm.hasSystemFeature(PackageManager.FEATURE_FACE), pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
  }
}

/**
 * The line under a prompt's title: why the fingerprint is asked, said first (the owner's ask). On Coach's own prompts (Start Coach,
 * Give Coach its Card, Connect your apps), `coach.prompt.why`: "Coach can act only within what you approve." A whole sentence of
 * its own, so no language has two strings glued together. Any other approval keeps its title alone. Words only: when the prompt is
 * asked, and the approval key it unlocks, are unchanged.
 */
internal fun promptDescription(reason: String): String? =
  Words.Coach.Prompt.why.takeIf { reason in setOf(Words.Coach.Prompt.start, Words.Coach.Prompt.giveCard, Words.Coach.Prompt.connectTools) }
