package bot.tafs.app.model

import bot.tafs.app.kiff.TafsCloud

/**
 * The words for deleting a conversation. tafs cloud sends them, in the person's language, with a chats-list read (`delete`), and
 * the busy line as its 409's `error`. The English here are stand-ins only, for a tafs cloud that doesn't send them yet; each word the
 * cloud does send replaces its own.
 */
data class DeleteChatWords(val menu: String, val title: String, val body: String, val button: String, val failed: String) {
  companion object {
    // STAND-INS: English, used only while tafs cloud sends none (an older cloud). Never translated here: tafs cloud's words are.
    const val MENU = "Delete conversation"
    const val TITLE = "Delete this conversation?"
    const val BODY = "Its messages and the files you added to it are deleted now, for good. Anything it saved to Memory stays there until you remove it. What was already done stays done, and what it did stays in your Record."
    const val BUTTON = "Delete"
    const val FAILED = "It wasn't deleted. Check your connection and try again."
    const val BUSY = "This conversation is still working or waiting for you. Stop it or answer it first, then delete it."

    /** tafs cloud's words where it sent them, each blank or missing one taken by its stand-in. */
    fun of(sent: TafsCloud.DeleteWords?): DeleteChatWords = DeleteChatWords(
      menu = sent?.menu?.ifBlank { null } ?: MENU,
      title = sent?.title?.ifBlank { null } ?: TITLE,
      body = sent?.body?.ifBlank { null } ?: BODY,
      button = sent?.button?.ifBlank { null } ?: BUTTON,
      failed = sent?.failed?.ifBlank { null } ?: FAILED,
    )
  }
}
