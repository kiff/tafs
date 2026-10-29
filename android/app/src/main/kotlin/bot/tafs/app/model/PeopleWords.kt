package bot.tafs.app.model

import bot.tafs.app.kiff.Group
import bot.tafs.app.kiff.GroupMember
import bot.tafs.app.kiff.Person
import bot.tafs.app.kiff.Share

/** The words of People and groups and of sharing a taf (docs/accounts.md), the same as iOS's. */
object PeopleWords {
  /** "a, b and c". */
  fun joined(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
  }

  fun label(subject: String, people: List<Person>): String = people.firstOrNull { it.subject == subject }?.label ?: subject

  /** Managers first, then by name. */
  fun managersFirst(members: List<GroupMember>, people: List<Person>): List<GroupMember> =
    members.sortedWith(compareBy({ if (it.isManager) 0 else 1 }, { label(it.subject, people).lowercase() }))

  /** "Uses Invoices and Refunds", or that it uses none yet. [tafName] names a KIFF agent the app lists, null for one it doesn't. */
  fun uses(group: Group, tafName: (String) -> String?): String {
    val names = group.agents.mapNotNull(tafName).sorted()
    if (names.isEmpty()) return if (group.everyone == true) "Everyone on the account · uses no taf" else "Uses no taf yet"
    return "Uses " + joined(names)
  }

  /** "Maria (manager), Ana, Luis". */
  fun peopleOf(group: Group, people: List<Person>): String {
    val names = managersFirst(group.members, people).map { m -> label(m.subject, people).let { if (m.isManager) "$it (manager)" else it } }
    return if (names.isEmpty()) "No one yet" else names.joinToString(", ")
  }

  private fun shareName(share: Share, groups: List<Group>, people: List<Person>): String =
    share.group?.let { g -> groups.firstOrNull { it.id == g }?.name } ?: share.subject?.let { label(it, people) } ?: ""

  /** "Shared with Support and ana@acme.com", not shared yet, or reading. */
  fun sharedWith(shares: List<Share>?, groups: List<Group>, people: List<Person>): String {
    if (shares == null) return "Sharing…"
    val names = shares.map { shareName(it, groups, people) }.filter { it.isNotEmpty() }
    return if (names.isEmpty()) "Not shared" else "Shared with " + joined(names)
  }

  /**
   * What the change means, as it holds today: each person's own chats and notes, which no one else sees in the app, and who
   * stops using it.
   */
  fun consequences(taf: String, had: Set<Share>, chosen: Set<Share>, groups: List<Group>, people: List<Person>): List<String> {
    val lines = mutableListOf<String>()
    if (chosen.isNotEmpty()) lines += "Each person gets their own chats and notes. No one else sees them in the app."
    for (share in chosen.sortedBy { it.group ?: it.subject ?: "" }) {
      val group = share.group?.let { g -> groups.firstOrNull { it.id == g } }
      when {
        group != null -> lines += "${group.name} uses $taf: ${peopleOf(group, people)}."
        share.subject != null -> lines += "${label(share.subject, people)} uses $taf."
      }
    }
    for (share in had - chosen) lines += "${shareName(share, groups, people)} stops using $taf. Their chats stay theirs."
    return lines
  }
}
