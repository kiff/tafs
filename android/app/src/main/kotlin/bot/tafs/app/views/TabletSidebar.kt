package bot.tafs.app.views

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Mascot
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.drive.driveStopZone
import bot.tafs.app.model.HaltWords
import bot.tafs.app.model.HomeWords
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words
import bot.tafs.app.model.shortTime

/** What's open beside a tablet's sidebar, highlighted in it, as the iPad's `HomeSelection` (docs/android-app.md, Tablets). */
internal sealed interface Beside {
  data object Overview : Beside
  data object NeedsYou : Beside
  data object Record : Beside
  data object Calendar : Beside
  data class Taf(val id: String) : Beside
  data object Account : Beside
  data object Usage : Beside
  data object ConnectApps : Beside
  data object HaltAll : Beside
  /** Coach, a trial or a mission's Log: nothing in the sidebar is highlighted. */
  data object Other : Beside
}

/** The sidebar's width: the iPad's at its full width. */
internal val TABLET_SIDEBAR_WIDTH: Dp = 320.dp

/**
 * A tablet's one sidebar, as the iPad's (`HomeSplitView`): the app's mark, which opens the Overview; the app's screens (Needs
 * you with its count, Record, and signed in, Calendar, Plan & usage and Connect apps); Halt all; the held calls; the tafs, theirs,
 * those being set up, shared with them, and other agents; Explore jobs; then at its foot Start a new taf, Coach and the account.
 * What each opens is the caller's: the sidebar only draws and says what was tapped.
 */
@Composable
internal fun TabletSidebar(
  state: AppState,
  beside: Beside,
  onOverview: () -> Unit,
  onNeedsYou: () -> Unit,
  onRecord: () -> Unit,
  onCalendar: (() -> Unit)?,
  onUsage: (() -> Unit)?,
  onConnectApps: (() -> Unit)?,
  onHaltAll: () -> Unit,
  onOpenTaf: (String) -> Unit,
  onFinishSetup: ((bot.tafs.app.kiff.HomeFinish) -> Unit)?,
  onDiscardSetup: ((String) -> Unit)?,
  onExplore: (() -> Unit)?,
  onNewTaf: (() -> Unit)?,
  onPlayNext: (() -> Unit)?,
  onCoach: (() -> Unit)?,
  onAccount: () -> Unit,
  onFold: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.width(TABLET_SIDEBAR_WIDTH).fillMaxHeight().background(Theme.raised).statusBarsPadding().testTag("sidebar")) {
    // The way to fold the sidebar away, so what's open takes the width, at its top end, as the iPad's.
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
      Box(
        Modifier.size(40.dp).clip(CircleShape).background(Theme.panel).tapping(onClick = onFold)
          .semantics { contentDescription = Words.Common.hideTheList; role = Role.Button },
        contentAlignment = Alignment.Center,
      ) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(18.dp)) }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
      // The app's mark: the Overview, today across the tafs, as the iPad's.
      item {
        Selectable(beside == Beside.Overview, onOverview, Words.App.name) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Mascot(size = 34.dp)
            Text(Words.App.name, style = scaled(22, FontWeight.Bold, Theme.ink))
          }
        }
      }
      item { Spacer(Modifier.heightIn(min = 12.dp)) }
      item { ScreenItem(R.drawable.ic_bell, state.needsYouTabTitle, beside == Beside.NeedsYou, onNeedsYou, count = state.needsYouCount) }
      item { ScreenItem(R.drawable.ic_list, Words.Record.title, beside == Beside.Record, onRecord) }
      // A KIFF account's screens, as the iPad's signed-in sidebar: the sample has none of them.
      onCalendar?.let { open -> item { ScreenItem(R.drawable.ic_calendar, Words.Calendar.heading, beside == Beside.Calendar, open) } }
      onUsage?.let { open -> item { ScreenItem(R.drawable.ic_chart, Words.Usage.title, beside == Beside.Usage, open) } }
      onConnectApps?.let { open -> item { ScreenItem(R.drawable.ic_connect, Words.Connect.Apps.title, beside == Beside.ConnectApps, open) } }
      // Every taf at once, from its own screen, with where it stands when it isn't running.
      if (state.showsAccountHalt) item {
        val h = state.haltOf(null)
        Selectable(beside == Beside.HaltAll, onHaltAll, Words.Taf.Halt.titleAll, Modifier.padding(top = 12.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_hand), contentDescription = null, tint = Theme.no, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(Words.Taf.Halt.titleAll, style = scaled(15, FontWeight.SemiBold, Theme.ink))
              if (!h.running) Text(HaltWords.rowLine(h), style = scaled(13, color = Theme.no))
            }
          }
        }
      }
      // The held calls, each opening its taf, where Home's strip had the first.
      if (state.held.isNotEmpty()) {
        item { Header(Words.NeedsYou.title, Theme.amber) }
        state.held.forEach { call ->
          state.taf(call.tafId)?.let { taf ->
            item(key = "held-" + call.id) {
              Selectable(beside == Beside.Taf(taf.id), { onOpenTaf(taf.id) }, null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                  Box(Modifier.size(8.dp).clip(CircleShape).background(Theme.amber))
                  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${taf.name}: ${call.proposed.summary}", style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${call.reason} · " + Words.NeedsYou.answerBy(shortTime(call.expiresAt)), style = scaled(12, color = Theme.soft), maxLines = 1, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }
          }
        }
      }
      // Theirs and those being set up, then the ones shared with them, each in the list's order (kiff/tafs#306). Only those tafs
      // cloud says are relevant now (`relevant_now`), as the iPad's sidebar: every taf is behind the mark, on the Overview. A tafs
      // cloud that doesn't say yet: every taf.
      val (allOwn, allShared) = state.sections(state.listedTafs)
      val says = state.listedTafs.any { it.relevantNow != null }
      val own = if (says) allOwn.filter { it.relevantNow == true } else allOwn
      val shared = if (says) allShared.filter { it.relevantNow == true } else allShared
      if (own.isNotEmpty() || state.settingUp.isNotEmpty()) {
        item { Header(Words.Home.yourTafs) }
        own.forEach { taf -> item(key = "taf-" + taf.id) { TafItem(state, taf, beside == Beside.Taf(taf.id)) { onOpenTaf(taf.id) } } }
        state.settingUp.forEach { taf ->
          item(key = "setting-up-" + taf.id) {
            // Its setup stopped partway: Coach's chat where it stopped, for someone who may use Coach; no state, as on Home.
            val finish = taf.finish?.takeIf { state.mayUseCoach && it.taf == state.coachCloudId }
            Column {
              TafItem(state, taf, false) { if (finish != null && onFinishSetup != null) onFinishSetup(finish) }
              val discard = taf.discard
              if (discard != null && onDiscardSetup != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { DiscardSetup(taf.id, discard.words, onDiscardSetup) }
            }
          }
        }
      }
      if (shared.isNotEmpty()) {
        item { Header(Words.Home.sharedYou) }
        shared.forEach { taf -> item(key = "shared-" + taf.id) { TafItem(state, taf, beside == Beside.Taf(taf.id)) { onOpenTaf(taf.id) } } }
      }
      if (state.otherAgents.isNotEmpty()) {
        item { Header(Words.Home.otherAgents) }
        state.otherAgents.forEach { taf ->
          item(key = "other-" + taf.id) {
            val finish = taf.finish?.takeIf { state.mayUseCoach && it.taf == state.coachCloudId }
            TafItem(state, taf, beside == Beside.Taf(taf.id)) { if (finish != null && onFinishSetup != null) onFinishSetup(finish) else onOpenTaf(taf.id) }
          }
        }
      }
      // Every job, to browse with nothing created, once tafs cloud sends the row's words.
      val explore = HomeWords.exploreRow(state.gallery)
      if (onExplore != null && explore != null) item { ScreenItem(R.drawable.ic_grid, explore, false, onExplore, Modifier.padding(top = 12.dp)) }
    }
    // The foot: always in sight, never behind "…".
    Column(
      Modifier.fillMaxWidth().background(Theme.raised).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      onNewTaf?.let { PrimaryButton("+ " + Words.Home.startNewTaf, it) }
      // KIFF can't be reached just now, the plan when it needs the owner, a lost approval key, a newer build: as on Home.
      state.unreachable?.let { UnreachableLine(it, state.retryingRefresh) }
      if (state.mode == Mode.kiff) state.balance?.let { b -> b.homeLine?.let { PlanLine(it, b.planTone) { onUsage?.invoke() } } }
      if (state.mode == Mode.kiff && state.approvalKeyLost) ApprovalKeyLine()
      UpdateLine()
      // The sample's next round, where Home had it.
      onPlayNext?.let { OutlineButton(Words.Sample.playNext, it, Modifier.fillMaxWidth(), color = Theme.soft) }
      onCoach?.let { SidebarCoachButton(it) }
      AccountItem(state.accountLabel, beside == Beside.Account, onAccount)
    }
  }
}

/** A row that's chosen to open what's beside the sidebar: a rounded fill while it's the one open. */
@Composable
private fun Selectable(selected: Boolean, onClick: () -> Unit, label: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
    modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
      .background(if (selected) Theme.line else Color.Transparent)
      .tapping(onClick = onClick)
      .semantics { this.selected = selected; role = Role.Button; label?.let { contentDescription = it } }
      .padding(horizontal = 12.dp, vertical = 8.dp),
    contentAlignment = Alignment.CenterStart,
  ) { content() }
}

/** One of the app's screens: its icon and title, with its count where it has one. */
@Composable
private fun ScreenItem(@DrawableRes icon: Int, title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int = 0) {
  Selectable(selected, onClick, null, modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(painterResource(icon), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(22.dp))
      Text(title, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (count > 0) Text("$count", style = scaled(15, color = Theme.dim))
    }
  }
}

@Composable
private fun Header(text: String, color: Color = Theme.dim) {
  Box(Modifier.padding(start = 12.dp, top = 20.dp, bottom = 6.dp)) { Eyebrow(text, color) }
}

/** A taf: its avatar, its name, and one line under it, in the colour Home's row gives it (`tafLineTone`). */
@Composable
private fun TafItem(state: AppState, taf: Taf, selected: Boolean, onClick: () -> Unit) {
  val signedOut = state.signedOut(taf.id)?.let(bot.tafs.app.model.ConnectionWords::signedOut)
  Selectable(selected, onClick, null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      TafAvatar(taf, size = 34.dp)
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(taf.name, style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        (signedOut ?: taf.now).takeIf { it.isNotEmpty() }?.let {
          Text(it, style = scaled(13, color = tafLineTone(taf, signedOut)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

/** Coach at the sidebar's foot, as the iPad's: ink, 44 high, its sparkle; amber while it drives, and a touch on it stops the drive. */
@Composable
private fun SidebarCoachButton(onClick: () -> Unit) {
  val acting = bot.tafs.app.drive.DriveInput.driving
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).driveStopZone().clip(RoundedCornerShape(14.dp))
      .background(if (acting) Theme.amber else Theme.ink)
      .tapping(onClick = onClick)
      .semantics { role = Role.Button; contentDescription = Words.Coach.coachMode },
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, tint = Theme.background, modifier = Modifier.size(18.dp))
    Text(Words.Coach.title, style = scaled(15, FontWeight.SemiBold, Theme.background), maxLines = 1)
  }
}

/** The account, who is signed in, at the very foot: it opens Account. */
@Composable
private fun AccountItem(account: String?, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Theme.line else Color.Transparent)
      .tapping(onClick = onClick).padding(horizontal = 4.dp)
      .semantics(mergeDescendants = true) {
        this.selected = selected; role = Role.Button; contentDescription = account?.let(Words.Settings::accountNamed) ?: Words.Settings.title
      },
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.soft, modifier = Modifier.size(22.dp))
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(Words.Settings.title, style = scaled(14, FontWeight.SemiBold, Theme.ink))
      account?.let { Text(it, style = scaled(12, color = Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
  }
}
