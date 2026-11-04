package bot.tafs.app.signing

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

  override suspend fun authenticate(reason: String): Boolean = suspendCancellableCoroutine { cont ->
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
          if (cont.isActive) cont.resume(false)
        }
      },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(reason)
      .setAllowedAuthenticators(ALLOWED)
      .build()
    prompt.authenticate(info)
    watch(host, prompt, cont)
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
  }

  override suspend fun authenticate(reason: String, signature: Signature): Boolean = suspendCancellableCoroutine { cont ->
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
          if (cont.isActive) cont.resume(false)
        }
      },
    )
    // A strong biometric only: the screen lock never approves, so the prompt offers Cancel in its place.
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(reason)
      .setAllowedAuthenticators(BIOMETRIC_STRONG)
      .setNegativeButtonText("Cancel")
      .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    watch(host, prompt, cont)
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
  }

  /**
   * A prompt that never opens (the system didn't show it) would leave the button waiting with nothing said: if this activity still
   * has its focus [PromptWatch]'s 10 seconds after asking, the prompt is cancelled and the ask fails with words saying so.
   */
  private fun watch(host: FragmentActivity, prompt: BiometricPrompt, cont: kotlinx.coroutines.CancellableContinuation<Boolean>) {
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val watch = PromptWatch()
    val started = android.os.SystemClock.uptimeMillis()
    val check = object : Runnable {
      override fun run() {
        if (!cont.isActive) return
        if (watch.gaveUp(android.os.SystemClock.uptimeMillis() - started, host.hasWindowFocus())) {
          // Answered first, so the cancellation's own error callback finds nothing left to answer.
          if (cont.isActive) cont.resumeWith(Result.failure(PromptNotOpened()))
          prompt.cancelAuthentication()
        } else {
          handler.postDelayed(this, CHECK_MS)
        }
      }
    }
    handler.postDelayed(check, CHECK_MS)
    cont.invokeOnCancellation { handler.removeCallbacks(check) }
  }

  companion object {
    private const val CHECK_MS = 250L

    /** A biometric the phone counts as strong, or the screen lock: what signs with the signing key asks with either. */
    const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
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
    !strongEnrolled -> "your screen lock"
    fingerprint -> "your fingerprint"
    face -> "face unlock"
    else -> "your screen lock"
  }

  fun of(context: Context): String {
    val strong = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    val pm = context.packageManager
    return name(strong, pm.hasSystemFeature(PackageManager.FEATURE_FACE), pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
  }
}
