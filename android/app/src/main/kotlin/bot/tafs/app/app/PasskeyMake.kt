package bot.tafs.app.app

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException

/**
 * Makes a kiff.dev passkey on this phone through Android's Credential Manager ([requestJson]: WebAuthn's creation options,
 * [bot.tafs.app.kiff.PasskeyRegistration.createOptions]). Android draws its own sheet and asks for the screen lock; the key
 * stays with the phone's passkey provider. Answers the creation's response as WebAuthn's JSON (`registrationResponseJson`),
 * which KIFF reads the credential from; null when the person put the sheet away. KIFF takes the passkey only from a build Play
 * signed: Credential Manager names the app by its signing certificate (`android:apk-key-hash:…`), and a debug build's is
 * refused (`origin`).
 */
suspend fun makePasskey(activity: Activity, requestJson: String): String? = try {
  val made = CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))
  (made as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
    ?: throw IllegalStateException("not a passkey: ${made.type}")
} catch (_: CreateCredentialCancellationException) {
  null
}
