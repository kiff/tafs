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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.ConnectApps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Connect apps' own words; every app's name and state comes from tafs cloud. */
object ConnectAppsWords {
  const val TITLE = "Connect apps"
  const val SEARCH = "Search apps"
  const val CONNECTED = "Connected ✓"
  const val CONNECT = "Connect"
  const val SIGN_IN_AGAIN = "Sign in again"
  const val NOT_LISTED = "Not listed? Ask Coach"
  const val NOT_LISTED_WHY = "Coach can connect an app that isn't here, from its own docs."
  const val NONE = "No app by that name. Coach can connect one that isn't listed."
  const val MORE = "Show more apps"
}

/**
 * Connect apps (A1): tafs cloud's catalog, first as it orders it, then KIFF's own apps for Google, Microsoft and Slack. Each
 * row says whether it's connected; Connect starts one ([onConnect], only for someone who manages Cards). An app nobody listed
 * goes to Coach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectAppsSheet(
  state: AppState,
  load: suspend (String?) -> Unit,
  loadMore: suspend () -> Unit = {},
  onConnect: ((ConnectApps.App, String?) -> Unit)?,
  onAskCoach: () -> Unit,
  onClose: () -> Unit,
) {
  var query by rememberSaveable { mutableStateOf("") }
  // An app is connected for a taf: with several, the owner picks which first; with none, there's nothing to connect it for.
  var picking by androidx.compose.runtime.remember { mutableStateOf<ConnectApps.App?>(null) }
  val tafs = state.connectableTafs
  val connect: ((ConnectApps.App) -> Unit)? = onConnect?.takeIf { tafs.isNotEmpty() }?.let { start ->
    { app -> if (tafs.size > 1 && !app.needsReconnect) picking = app else start(app, tafs.singleOrNull()?.id) }
  }
  // Read again as the search changes, once typing pauses.
  LaunchedEffect(query) {
    if (query.isNotEmpty()) delay(300)
    load(query.ifBlank { null })
  }
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Which taf: asked over the list, wherever Connect was tapped, before anything starts.
    picking?.let { app ->
      androidx.compose.material3.AlertDialog(
        onDismissRequest = { picking = null },
        containerColor = Theme.background,
        title = { Text(bot.tafs.app.model.ConnectWords.WHICH_TAF, style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column {
            Text(bot.tafs.app.model.ConnectWords.oneTaf(app.title), style = scaled(14, color = Theme.soft))
            tafs.forEach { taf ->
              Text(
                taf.name,
                style = scaled(16, FontWeight.SemiBold, Theme.amber),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = null; onConnect?.invoke(app, taf.id) }.padding(top = 14.dp),
              )
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text("Cancel", style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).clickable { picking = null }.padding(12.dp))
        },
      )
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(ConnectAppsWords.TITLE, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text(ConnectAppsWords.SEARCH) }, singleLine = true)
        if (onConnect != null && tafs.isEmpty()) Text(bot.tafs.app.model.ConnectWords.NO_TAF, style = scaled(14, color = Theme.soft))
        state.connectAppsError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        val apps = state.connectApps
        apps?.apps?.forEach { app -> AppRow(app, connect) }
        if (apps != null && apps.apps.isEmpty() && query.isNotBlank()) Text(ConnectAppsWords.NONE, style = scaled(14, color = Theme.soft))
        // The managed provider's catalog comes a page at a time.
        if (apps?.nextCursor != null) {
          val more = androidx.compose.runtime.rememberCoroutineScope()
          Text(
            ConnectAppsWords.MORE,
            style = scaled(15, FontWeight.SemiBold, Theme.amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { more.launch { loadMore() } }.padding(top = 10.dp),
          )
        }
        if (apps?.byKey == true) {
          Panel {
            Text(
              ConnectAppsWords.NOT_LISTED,
              style = scaled(15, FontWeight.SemiBold, Theme.ink),
              modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onAskCoach).padding(top = 10.dp),
            )
            Text(ConnectAppsWords.NOT_LISTED_WHY, style = scaled(13, color = Theme.soft))
          }
        }
      }
    }
  }
}

@Composable
private fun AppRow(app: ConnectApps.App, onConnect: ((ConnectApps.App) -> Unit)?) {
  Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      // The app's initial on a tile; tafs cloud names the app, the tile is only a marker.
      Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tileColor(app.title)), contentAlignment = Alignment.Center) {
        Text(app.title.take(1).uppercase(), style = scaled(15, FontWeight.ExtraBold, Color.White))
      }
      // Its title alone: the provider's own id for it means nothing to a person.
      Text(app.title, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
      when {
        app.connected -> Text(ConnectAppsWords.CONNECTED, style = scaled(14, FontWeight.SemiBold, Theme.ok))
        onConnect != null -> Text(
          if (app.needsReconnect) ConnectAppsWords.SIGN_IN_AGAIN else ConnectAppsWords.CONNECT,
          style = scaled(15, FontWeight.SemiBold, if (app.needsReconnect) Theme.amberText else Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable { onConnect(app) }.padding(top = 10.dp),
        )
        app.needsReconnect -> Text(ConnectAppsWords.SIGN_IN_AGAIN, style = scaled(14, color = Theme.amberText))
      }
    }
  }
}

/** A tile's colour, the same for an app every time. */
private fun tileColor(name: String): Color = Theme.tafColor(Math.floorMod(name.hashCode(), 6))

/**
 * The approval over Connect apps: the same as Coach's card. planque's words for the connection, KIFF's words for its profile
 * (Approve waits for them), then the signature, and only then the vendor's sign-in page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectApprovalSheet(
  state: AppState,
  grant: bot.tafs.app.kiff.Grant?,
  loadWords: suspend (String) -> Unit,
  onApprove: (bot.tafs.app.kiff.Grant) -> Unit,
  onClose: () -> Unit,
  /** An API-key connection: the key is typed here and given with the same confirmation, instead of a vendor's sign-in. */
  needsKey: Boolean = false,
  onGiveKey: (bot.tafs.app.kiff.Grant, String) -> Unit = { _, _ -> },
) {
  // Kept only while the sheet is open, never saved: it goes to planque once.
  var key by androidx.compose.runtime.remember(grant?.id) { mutableStateOf("") }
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(bot.tafs.app.model.ConnectWords.APPROVE_TITLE, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      if (grant == null) {
        Text("Reading what it asks for…", style = scaled(14, color = Theme.soft))
      } else {
        // planque's own words for what it will do.
        Text(grant.text, style = scaled(15, color = Theme.ink))
        Text(grant.vendor, style = scaled(13, color = Theme.dim, mono = true))
        val profiled = grant.request.profile != null
        val words = state.profileWords[grant.id]
        if (profiled) {
          LaunchedEffect(grant.id) { loadWords(grant.id) }
          when {
            words != null -> ProfileWordsLines(words)
            state.profileWordsErrors[grant.id] != null -> Text(state.profileWordsErrors.getValue(grant.id), style = scaled(13, color = Theme.no))
            else -> Text(bot.tafs.app.model.CoachWords.READING_KIFF_WORDS, style = scaled(13, color = Theme.soft))
          }
        }
        state.coachNotes[bot.tafs.app.model.ConnectWords.cardKey(grant.id)]?.let { Text(it, style = scaled(13, color = Theme.no)) }
        if (!grant.digestMatches) {
          Text(bot.tafs.app.model.CoachWords.DIGEST_MISMATCH, style = scaled(13, color = Theme.no))
        } else {
          val busy = state.connecting == grant.id
          if (needsKey) {
            OutlinedTextField(
              key, { key = it }, Modifier.fillMaxWidth(),
              placeholder = { Text(bot.tafs.app.model.ConnectWords.KEY) },
              singleLine = true,
              enabled = !busy,
              visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
              keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false,
              ),
            )
          }
          ApprovalGate {
            val biometry = bot.tafs.app.design.LocalBiometry.current
            bot.tafs.app.design.PrimaryButton(
              when {
                busy -> state.connectStep ?: "Approving…"
                needsKey -> bot.tafs.app.model.ConnectWords.giveKeyWith(biometry)
                else -> "Approve with $biometry"
              },
              { if (needsKey) onGiveKey(grant, key) else onApprove(grant) },
              enabled = !busy && (!profiled || words != null) && (!needsKey || key.isNotBlank()),
            )
          }
        }
        Text(
          bot.tafs.app.model.ConnectWords.CANCEL,
          style = scaled(15, FontWeight.SemiBold, Theme.dim),
          modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp),
        )
      }
    }
  }
}
