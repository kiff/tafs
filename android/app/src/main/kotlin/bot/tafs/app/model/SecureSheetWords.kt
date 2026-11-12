package bot.tafs.app.model

import bot.tafs.app.kiff.SecureSheet

/**
 * The secure connection sheet's words (docs/secure-connection.md) are tafs cloud's. The phone fills only the placeholders the
 * contract names: `{last4}` from the key it pasted, and `{vendor}` from the same answer. The key itself is never in any of them.
 */
object SecureSheetWords {
  /** A key's last 4 characters, the only part of it ever shown. A key shorter than 12 shows none of itself, as on iOS. */
  fun last4(key: String): String = key.trim().let { if (it.length < 12) "" else it.takeLast(4) }

  /** The pasted key, as tafs cloud says it ("Key …ab12"). */
  fun pasted(sheet: SecureSheet, key: String): String = sheet.paste.pasted.replace("{last4}", last4(key))

  /** The receipt's line ("Qonto connected · key …ab12"). */
  fun receipt(sheet: SecureSheet, last4: String): String =
    sheet.receipt?.text.orEmpty().replace("{vendor}", sheet.vendor.name).replace("{last4}", last4)

  /**
   * Whether the sheet's first line holds for the grant this phone read to sign its digest: "read only" only when the grant lists
   * at least one tool and every one is `read_only`. When it doesn't hold, there's nothing on the sheet to sign.
   */
  fun firstLineHolds(sheet: SecureSheet, grant: bot.tafs.app.kiff.Grant): Boolean {
    if (sheet.lines.none { it.kind == "reads_only" }) return true
    val tools = grant.request.tools
    return tools.isNotEmpty() && tools.all { it.readOnly == true }
  }

  /** A pasted key, as it's given: the outer whitespace a paste adds is not the key's. */
  fun cleaned(pasted: String): String = pasted.trim()
}
