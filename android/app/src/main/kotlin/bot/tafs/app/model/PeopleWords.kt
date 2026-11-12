package bot.tafs.app.model

import bot.tafs.app.kiff.Group
import bot.tafs.app.kiff.GroupMember
import bot.tafs.app.kiff.Person
import bot.tafs.app.kiff.Share

/** The words of People and groups and of sharing a taf (docs/accounts.md), the same as iOS's. */
object PeopleWords {
  /** "a and b", "a, b, and c": as the lines' language lists names. */
  fun joined(names: List<String>): String = Res.list(names)

  /** The same list: every name, as the lines' language joins them. */
  fun all(names: List<String>): String = Res.list(names)

  fun label(subject: String, people: List<Person>): String = people.firstOrNull { it.subject == subject }?.label ?: subject

  /** Managers first, then by name. */
  fun managersFirst(members: List<GroupMember>, people: List<Person>): List<GroupMember> =
    members.sortedWith(compareBy({ if (it.isManager) 0 else 1 }, { label(it.subject, people).lowercase() }))

  /** "Uses Invoices and Refunds", or that it uses none yet. [tafName] names a KIFF agent the app lists, null for one it doesn't. */
  fun uses(group: Group, tafName: (String) -> String?): String {
    val names = group.agents.mapNotNull(tafName).sorted()
    if (names.isEmpty()) return if (group.everyone == true) Words.People.everyoneUsesNone else Words.People.usesNone
    return Words.People.uses(joined(names))
  }

  /** "Maria (manager), Ana, Luis". */
  fun peopleOf(group: Group, people: List<Person>): String {
    val names = managersFirst(group.members, people).map { m -> label(m.subject, people).let { if (m.isManager) Words.People.nameManager(it) else it } }
    return if (names.isEmpty()) Words.People.noOneYet else names.joinToString(", ")
  }

  private fun shareName(share: Share, groups: List<Group>, people: List<Person>): String =
    share.group?.let { g -> groups.firstOrNull { it.id == g }?.shownName } ?: share.subject?.let { label(it, people) } ?: ""

  /**
   * "Shared with Support and ana@acme.com", not shared yet, or reading. The person reading isn't someone it's shared with: their
   * own share (KIFF marks them `you`) is left out, so a taf shared with no one else reads "Not shared", never their own address.
   */
  fun sharedWith(shares: List<Share>?, groups: List<Group>, people: List<Person>): String {
    if (shares == null) return Words.People.sharing
    // Shared with KIFF's default group alone: its own sentence, so the languages that inflect it read right.
    shares.singleOrNull()?.group?.let { g -> if (groups.firstOrNull { it.id == g }?.everyone == true) return Words.People.sharedWithEveryone }
    val you = people.filter { it.you == true }.map { it.subject }.toSet()
    val names = shares.filter { it.subject == null || it.subject !in you }.map { shareName(it, groups, people) }.filter { it.isNotEmpty() }
    return if (names.isEmpty()) Words.People.notShared else Words.People.sharedWith(joined(names))
  }

  /**
   * What the change means, as it holds today: each person's own chats and notes, which no one else sees in the app, and who
   * stops using it.
   */
  fun consequences(taf: String, had: Set<Share>, chosen: Set<Share>, groups: List<Group>, people: List<Person>): List<String> {
    val lines = mutableListOf<String>()
    if (chosen.isNotEmpty()) lines += Words.People.ownChats
    for (share in chosen.sortedBy { it.group ?: it.subject ?: "" }) {
      val group = share.group?.let { g -> groups.firstOrNull { it.id == g } }
      when {
        group != null && group.everyone == true -> lines += Words.People.everyoneUses(taf, peopleOf(group, people))
        group != null -> lines += Words.People.groupUses(group.name, taf, peopleOf(group, people))
        share.subject != null -> lines += Words.People.personUses(label(share.subject, people), taf)
      }
    }
    for (share in had - chosen) lines += Words.People.stopsUsing(shareName(share, groups, people), taf)
    return lines
  }
}

/**
 * A group's name as the person reads it: KIFF's default group ([Group.everyone]) in the person's language ("Tout le monde"), since
 * KIFF keeps it as "Everyone"; any other group as it was named.
 */
val bot.tafs.app.kiff.Group.shownName: String get() = if (everyone == true) Words.People.everyoneGroup else name
