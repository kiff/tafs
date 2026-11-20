package bot.tafs.app

import bot.tafs.app.app.Mode
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone sends the watch for each page, measured on the full sample (row 44, D0; docs/watch-wear.md, "Measuring the
 * link"): each reply's size in bytes and every field it carries, printed for the audit. A reply growing past its budget fails, so
 * a field nobody draws can't creep in unnoticed. The budgets are today's sizes with room; the audit decides what to cut.
 */
class WatchPayloadAuditTest {
  private fun fields(e: JsonElement, at: String = "", out: MutableSet<String> = sortedSetOf()): Set<String> {
    when (e) {
      is JsonObject -> e.forEach { (k, v) -> val p = if (at.isEmpty()) k else "$at.$k"; out += p; fields(v, p, out) }
      is JsonArray -> e.forEach { fields(it, "$at[]", out) }
      else -> Unit
    }
    return out
  }

  private fun audit(name: String, reply: WatchReply, budget: Int): Int {
    val bytes = WatchLink.encodeReply(reply)
    val json = Json.parseToJsonElement(bytes.decodeToString())
    println("watch payload · $name · ${bytes.size} B · fields: ${fields(json).joinToString(", ")}")
    assertTrue("$name is ${bytes.size} B, over its $budget B budget", bytes.size <= budget)
    return bytes.size
  }

  @Test fun eachPagesReplyIsMeasured() {
    val state = testModel().state.value.copy(mode = Mode.kiff)
    val total = audit("needs-you", WatchRelay.needsYou(state), 4_000) +
      audit("tafs", WatchRelay.tafs(state), 4_000) +
      audit("brief", WatchRelay.brief(state), 6_000) +
      audit("account", WatchRelay.account(state), 2_000)
    println("watch payload · first open, all pages · $total B")
  }
}
