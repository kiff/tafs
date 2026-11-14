package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One line of KIFF's words for a connection's profile (`kiff.words.v1`), shown as KIFF wrote it. */
@Serializable
data class WordLine(
  /** The operation it's about: the gateway connection's tool name. */
  val op: String = "",
  /** For grouping and style only: does, outside, scope, counts, checks, missing, no_effect, probation or unpinned. */
  val kind: String = "",
  /** Shown verbatim. Neither Coach nor the app writes these. */
  val text: String = "",
) {
  /** What the owner should notice: something KIFF can't check, or a vendor that isn't pinned. */
  val caution: Boolean get() = kind == MISSING || kind == PROBATION || kind == UNPINNED

  companion object {
    const val MISSING = "missing"
    const val PROBATION = "probation"
    const val UNPINNED = "unpinned"
  }
}

/**
 * KIFF's words for a profile, checked on the phone (KIFF's profile contract, `kiff.profile.v1` and `kiff.words.v1`): [profileSha256] is the hash of the profile the owner
 * approves, [wordsSha256] of the words shown, each SHA-256 of the JCS form. Both go into the signed approval.
 */
data class ProfileWords(val profileSha256: String, val wordsSha256: String, val lines: List<WordLine>) {
  /** What the approve body carries, so the device's signature covers both digests. */
  fun approval(): JsonObject = JsonObject(mapOf("profile_sha256" to JsonPrimitive(profileSha256), "words_sha256" to JsonPrimitive(wordsSha256)))

  companion object {
    const val WORDS_V1 = "kiff.words.v1"
    /** v1's lines less KIFF's bookkeeping; asked for with `?v=2`. Either is shown and signed as KIFF sent it. */
    const val WORDS_V2 = "kiff.words.v2"

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A JSON value's digest as KIFF computes it: SHA-256 of its RFC 8785 form, in lowercase hex. */
    fun digest(value: JsonElement): String = sha256Hex(Jcs.canonical(value).toByteArray())

    /**
     * Reads KIFF's answer to `POST /v1/profiles/words` for [profile], and checks it before anything is shown: the words are
     * `kiff.words.v1` or `kiff.words.v2`, their hash is [words_sha256], and [profile_sha256] is the hash of the profile the phone sent. Otherwise
     * nothing is shown and nothing can be approved.
     */
    fun checked(profile: JsonObject, answer: JsonObject): ProfileWords {
      val words = answer["words"]?.jsonObject ?: throw Mismatch("no words")
      if (words["v"]?.jsonPrimitive?.content !in setOf(WORDS_V1, WORDS_V2)) throw Mismatch("words version")
      val profileSha = answer["profile_sha256"]?.jsonPrimitive?.content
      val wordsSha = answer["words_sha256"]?.jsonPrimitive?.content
      if (profileSha != digest(profile)) throw Mismatch("profile digest")
      if (wordsSha != digest(words)) throw Mismatch("words digest")
      val lines = KiffClient.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(WordLine.serializer()), words["lines"] ?: throw Mismatch("no lines"))
      return ProfileWords(profileSha, wordsSha, lines)
    }
  }

  /** KIFF's words don't match what the phone hashed: nothing is approved. */
  class Mismatch(what: String) : Exception(Words.Connect.wordsMismatch(what = what))
}
