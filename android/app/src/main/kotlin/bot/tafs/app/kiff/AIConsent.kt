package bot.tafs.app.kiff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The person's AI-data consent, as tafs cloud keeps it (docs/accounts.md, "Your data and the AI"): whether they allowed it for
 * the [version] of the words they're shown, and the screen's words in their language. The app draws the words as sent and sends
 * the answer; tafs cloud decides whether anything reaches the model.
 */
@Serializable
data class AIConsent(
  val given: Boolean = false,
  val at: String? = null,
  val version: String = "",
  val words: AIConsentWords = AIConsentWords(),
) {
  /** tafs cloud sent the screen's words: a title, a line, Allow and Not now. Without them nothing is drawn, never a blank sheet. */
  val drawable: Boolean get() = words.title.isNotBlank() && words.lines.any { it.isNotBlank() } && words.allow.isNotBlank() && words.notNow.isNotBlank()
}

@Serializable
data class AIConsentWords(
  val title: String = "",
  val lines: List<String> = emptyList(),
  val allow: String = "",
  @SerialName("not_now") val notNow: String = "",
  val withdraw: String = "",
  val given: String = "",
)

@Serializable
internal data class AIConsentAnswer(val consent: AIConsent)

/** tafs cloud's code for a model turn refused until the person allows AI use. */
const val AI_CONSENT_REQUIRED = "ai_consent_required"
