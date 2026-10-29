package bot.tafs.app.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Group
import bot.tafs.app.kiff.GroupMember
import bot.tafs.app.kiff.Share
import bot.tafs.app.model.PeopleWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What People and groups and Share ask of the model: each change is one confirmation, then everything is read again. */
class PeopleActions(
  val refresh: suspend () -> Unit,
  val createGroup: suspend (String) -> Boolean,
  val renameGroup: suspend (Group, String) -> Boolean,
  val deleteGroup: suspend (Group) -> Boolean,
  /** Adds someone to a group, or changes their role in it: manager or member. */
  val setMember: suspend (Group, String, String) -> Boolean,
  val removeMember: suspend (Group, String) -> Boolean,
  val shares: suspend (String) -> List<Share>?,
  /** Shares a taf (its KIFF agent, its name) with some and stops sharing it with others, behind one confirmation. */
  val setShares: suspend (String, String, Set<Share>, Set<Share>) -> Boolean,
)

/** How often People and groups is read again while it's open, so someone who joins shows without leaving it. */
private const val PEOPLE_REFRESH_MS = 20_000L

/**
 * People and groups, in Settings: the account's groups, each with its people and the tafs it uses. A manager sees the group's
 * work on those tafs; its members use them. KIFF keeps all of it; the app shows what KIFF says, and each change is the
 * person's act behind one confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleGroupsSheet(state: AppState, actions: PeopleActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var loaded by remember { mutableStateOf(false) }
  var naming by remember { mutableStateOf<Group?>(null) }
  var namingNew by remember { mutableStateOf(false) }
  var adding by remember { mutableStateOf<Group?>(null) }
  var deleting by remember { mutableStateOf<Group?>(null) }
  LaunchedEffect(Unit) {
    while (true) {
      actions.refresh()
      loaded = true
      delay(PEOPLE_REFRESH_MS)
    }
  }
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("People & groups", style = scaled(28, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
        // Words, not "+": it makes a group.
        if (state.canManagePeople) {
          Text("+ Group", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable { namingNew = true }.padding(top = 10.dp))
        }
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        state.peopleError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        state.kiffAccount?.let { account ->
          Text("${account.name} · ${state.people.size} ${if (state.people.size == 1) "person" else "people"}", style = scaled(14, color = Theme.soft), modifier = Modifier.padding(horizontal = 4.dp))
        }
        state.groups.forEach { group ->
          GroupPanel(
            group, state,
            onAdd = { adding = group },
            onRename = { naming = group },
            onDelete = { deleting = group },
            setMember = { subject, role -> scope.launch { actions.setMember(group, subject, role) } },
            removeMember = { subject -> scope.launch { actions.removeMember(group, subject) } },
          )
        }
        if (loaded && state.groups.isEmpty() && state.peopleError == null) Text("No groups yet.", style = scaled(14, color = Theme.dim))
      }
    }
  }
  if (namingNew || naming != null) {
    GroupNameDialog(
      group = naming,
      onClose = { namingNew = false; naming = null },
      onSave = { name -> naming.let { g -> if (g == null) actions.createGroup(name) else actions.renameGroup(g, name) } },
    )
  }
  adding?.let { group -> AddToGroupSheet(group, state, actions, onClose = { adding = null }) }
  deleting?.let { group ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("Delete ${group.name}?") },
      text = { Text("Its people lose the tafs they used through it, unless another group or a share of their own gives them those tafs. Their chats stay theirs.") },
      confirmButton = { TextButton({ deleting = null; scope.launch { actions.deleteGroup(group) } }) { Text("Delete ${group.name}", color = Theme.no) } },
      dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

/** A group: its name, the tafs it uses, its people (managers first), and its actions for whoever may take them. */
@Composable
private fun GroupPanel(
  group: Group,
  state: AppState,
  onAdd: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  setMember: (String, String) -> Unit,
  removeMember: (String) -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(group.name, style = scaled(16, FontWeight.SemiBold, Theme.ink))
        Text(PeopleWords.uses(group) { agent -> state.taf(agent)?.name }, style = scaled(12, color = Theme.dim))
      }
      if (state.canManagePeople && group.everyone != true) {
        Box {
          Text("•••", style = scaled(16, FontWeight.SemiBold, Theme.soft), modifier = Modifier.heightIn(min = 44.dp).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 12.dp))
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Delete", color = Theme.no) }, onClick = { menu = false; onDelete() })
          }
        }
      }
    }
    PeopleWords.managersFirst(group.members, state.people).forEach { member -> MemberRow(member, group, state, setMember, removeMember) }
    if (state.managesGroup(group)) {
      Text("+ Add someone to ${group.name}", style = scaled(15, color = Theme.amberText), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onAdd).padding(top = 12.dp))
    }
  }
}

@Composable
private fun MemberRow(member: GroupMember, group: Group, state: AppState, setMember: (String, String) -> Unit, removeMember: (String) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  val label = PeopleWords.label(member.subject, state.people)
  // Naming or changing a manager is for whoever manages people (KIFF's members.manage), not for the group's manager; a group's
  // manager removes its members, and removing a manager is for whoever manages people, as KIFF allows.
  val mayRole = state.canManagePeople
  val mayRemove = state.canManagePeople || (state.managesGroup(group) && !member.isManager)
  Box {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = mayRole || mayRemove) { menu = true }.padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PersonBadge(label)
      Column(Modifier.weight(1f)) {
        Text(label, style = scaled(15, color = Theme.ink), maxLines = 1)
        Text(if (member.isManager) "Manager" else "Member", style = scaled(12, color = if (member.isManager) Theme.amber else Theme.dim))
      }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
      if (mayRole) {
        val role = if (member.isManager) "member" else "manager"
        DropdownMenuItem(text = { Text(if (member.isManager) "Make a member" else "Make the manager") }, onClick = { menu = false; setMember(member.subject, role) })
      }
      if (mayRemove) DropdownMenuItem(text = { Text("Remove from ${group.name}", color = Theme.no) }, onClick = { menu = false; removeMember(member.subject) })
    }
  }
}

/** A new group's name, or a group's new one. */
@Composable
private fun GroupNameDialog(group: Group?, onClose: () -> Unit, onSave: suspend (String) -> Boolean) {
  val scope = rememberCoroutineScope()
  var name by remember { mutableStateOf(group?.name ?: "") }
  var saving by remember { mutableStateOf(false) }
  val trimmed = name.trim()
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text(if (group == null) "New group" else "Rename ${group.name}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(name, { name = it }, "Support, Finance…")
        Text(
          if (group == null) "Add its people next, then share a taf with it from the taf's page." else "Only the name changes: its people and tafs stay.",
          style = scaled(13, color = Theme.dim),
        )
      }
    },
    confirmButton = {
      TextButton({ saving = true; scope.launch { val done = onSave(trimmed); saving = false; if (done) onClose() } }, enabled = trimmed.isNotEmpty() && !saving) {
        Text(if (group == null) "Create" else "Save", color = Theme.amber)
      }
    },
    dismissButton = { TextButton(onClose) { Text("Cancel", color = Theme.soft) } },
    containerColor = Theme.panel,
  )
}

/** Adds one of the account's people to a group, as a member or its manager. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToGroupSheet(group: Group, state: AppState, actions: PeopleActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var asManager by remember { mutableStateOf(false) }
  val inside = group.members.map { it.subject }.toSet()
  val outside = state.people.filter { it.subject !in inside }
  ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Add to ${group.name}", style = scaled(24, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
        Text("Cancel", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
      }
      Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Naming a manager is for whoever manages people (KIFF's members.manage).
        if (state.canManagePeople) {
          Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("As the group's manager", style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
              Switch(checked = asManager, onCheckedChange = { asManager = it }, colors = SwitchDefaults.colors(checkedTrackColor = Theme.ok))
            }
            Text("A manager adds and removes the group's members. Managing doesn't let them change a taf's Cards.", style = scaled(12, color = Theme.dim))
          }
        }
        Panel {
          Text("THE ACCOUNT'S PEOPLE", style = scaled(12, color = Theme.dim, mono = true))
          outside.forEach { person ->
            Row(
              Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                scope.launch {
                  val role = if (asManager && state.canManagePeople) "manager" else "member"
                  if (actions.setMember(group, person.subject, role)) onClose()
                }
              }.padding(vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              PersonBadge(person.label)
              Text(person.label, style = scaled(15, color = Theme.ink), maxLines = 1)
            }
          }
          if (outside.isEmpty()) Text("Everyone on the account is in ${group.name}. Invite people from the KIFF dashboard.", style = scaled(14, color = Theme.dim))
        }
        state.peopleError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
      }
    }
  }
}

/** A person's initial in a circle: KIFF names people by email, so it's the email's first letter. */
@Composable
fun PersonBadge(label: String, size: Int = 32) {
  Box(Modifier.size(size.dp).clip(CircleShape).background(Theme.raised), contentAlignment = Alignment.Center) {
    Text(label.take(1).uppercase(), style = scaled(14, FontWeight.SemiBold, Theme.ink))
  }
}
