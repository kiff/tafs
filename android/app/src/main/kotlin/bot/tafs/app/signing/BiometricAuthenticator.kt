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
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
  }

  companion object {
    /** A biometric the phone counts as strong, or the screen lock. */
    const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
  }
}

/**
 * What this phone asks for before it signs, in the words the owner knows (kiff/tafs#87): a
 * fingerprint or face on most phones, else the screen lock. For "Approve once with …".
 */
object Biometry {
  fun name(strongEnrolled: Boolean, face: Boolean, fingerprint: Boolean): String = when {
    !strongEnrolled -> "your screen lock"
    fingerprint && face -> "your fingerprint or face"
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
