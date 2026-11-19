package bot.tafs.app.views

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Segmented
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.drive.DriveAlert
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.LocalDrawnBeside
import bot.tafs.app.files.FileDetail
import bot.tafs.app.files.FileGroups
import bot.tafs.app.files.FileNames
import bot.tafs.app.files.FilePreview
import bot.tafs.app.files.FilesModel
import bot.tafs.app.files.FilesState
import bot.tafs.app.files.FilesView
import bot.tafs.app.files.Goes
import bot.tafs.app.files.KeptFile
import bot.tafs.app.model.Words
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import bot.tafs.app.model.shortTime
import bot.tafs.app.model.shownDate
import bot.tafs.app.model.zone
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch

/*
 * Files (docs/android-app.md, Files): the account's files, from Account, a taf's page and a taf's chat, over [FilesModel]. Drawn
 * only where [LocalFiles] holds a model, which it does only with FilesFeature on: off, nothing of Files is drawn.
 */

/** The Files model, where Files is on; null, and nothing of it drawn, where it's off. */
val LocalFiles = staticCompositionLocalOf<FilesModel?> { null }

/** What the Files screen does, each through [FilesModel]; [seeInChat] where the chat a file came from can be opened. */
class FilesActions(
  val close: () -> Unit,
  val view: (FilesView) -> Unit,
  val pickTaf: (String?) -> Unit,
  val open: (KeptFile) -> Unit,
  val closeFile: () -> Unit,
  val download: (KeptFile) -> Unit,
  val share: (KeptFile) -> Unit,
  val askDelete: (KeptFile) -> Unit,
  val cancelDelete: () -> Unit,
  val confirmDelete: () -> Unit,
  val seeInChat: ((KeptFile) -> Unit)? = null,
)

/** A file's name as shown: no path, no control or direction characters; its id when nothing's left. */
internal fun shownName(file: KeptFile): String = FileNames.name(file.name, file.id)

/** When it goes, in the list's words: "goes today", "goes tomorrow", "goes in 12 days". */
internal fun goesWords(file: KeptFile, today: LocalDate = LocalDate.now(zone)): String = when (val goes = Goes.of(file.expires, today, zone)) {
  Goes.Today -> Words.Files.goesToday
  Goes.Tomorrow -> Words.Files.goesTomorrow
  is Goes.InDays -> Words.Files.goesInDays(goes.days.toInt())
}

/** A size as this phone writes one ("14 kB"). */
internal fun sizeWords(context: Context, file: KeptFile): String = android.text.format.Formatter.formatShortFileSize(context, file.size)

/** Files' row on Account: how many files the account has, and that they're kept 30 days. Nothing where Files is off. */
@Composable
internal fun FilesAccountRow(onOpen: () -> Unit) {
  val files = LocalFiles.current ?: return
  val state by files.state.collectAsState()
  LaunchedEffect(files) { files.refresh() }
  val count = state.files?.size ?: 0
  FilesRow(if (count == 0) Words.Files.noneYet else Words.Files.accountRow(count), Modifier.testTag(FILES_ACCOUNT_ROW), onOpen)
  HorizontalDivider(color = Theme.line, modifier = Modifier.padding(start = 12.dp))
}

/** Files' row on a taf's page: how many files its runs made, and until when they're kept. Nothing where Files is off. */
@Composable
internal fun TafFilesRow(tafCloudId: String?) {
  val files = LocalFiles.current ?: return
  if (tafCloudId == null) return
  val state by files.state.collectAsState()
  LaunchedEffect(files) { if (files.state.value.files == null) files.refresh() }
  val its = FileGroups.forTaf(state.files.orEmpty(), tafCloudId)
  Panel(Modifier.testTag(FILES_TAF_ROW)) {
    FilesRow(if (its.isEmpty()) Words.Files.noneYet else Words.Files.tafRow(its.size), Modifier) { files.show(tafCloudId, FilesView.ByRun) }
    FileGroups.keptUntil(its)?.let { Text(Words.Files.keptUntil(shownDate(it)), style = scaled(13, color = Theme.amberText), modifier = Modifier.padding(horizontal = 12.dp)) }
  }
}

@Composable
private fun FilesRow(meta: String, modifier: Modifier, onOpen: () -> Unit) {
  Column(modifier.fillMaxWidth().heightIn(min = 48.dp).tapping(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp)) {
    Text("${Words.Files.title} ›", style = scaled(16, color = Theme.ink))
    Text(meta, style = scaled(13, color = Theme.dim))
  }
}

/**
 * A chat's files, under the last answer of the chat they came from (`chat`, its session): each a chip with its type and size that
 * opens it, then until when they're kept. Nothing where Files is off, or the chat made none.
 */
@Composable
internal fun ChatFiles(session: String) {
  val files = LocalFiles.current ?: return
  val state by files.state.collectAsState()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  // Read once, if nothing has read the list yet: the chat's files are the list's, by their chat.
  LaunchedEffect(files) { if (files.state.value.files == null) files.refresh() }
  val its = state.files.orEmpty().filter { it.chat == session }
  if (its.isEmpty()) return
  Column(Modifier.fillMaxWidth().testTag(FILES_CHAT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (file in its) {
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.raised).tapping { scope.launch { files.open(file.id, alone = true) } }
          .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TypeTile(file)
        Column(Modifier.weight(1f)) {
          Text(shownName(file), style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 2)
          Text("${FileNames.type(file)} · ${sizeWords(context, file)}", style = scaled(12, color = Theme.dim))
        }
        Text("›", style = scaled(16, color = Theme.dim))
      }
    }
    FileGroups.keptUntil(its)?.let { Text(Words.Files.keptUntil(shownDate(it)), style = scaled(12, color = Theme.amberText)) }
  }
}

/** The file's type in a small tile: its tag ("CSV") on the ink. */
@Composable
private fun TypeTile(file: KeptFile) {
  Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Theme.ink), contentAlignment = Alignment.Center) {
    Text(FileNames.type(file), style = scaled(9, FontWeight.Bold, Theme.background), maxLines = 1)
  }
}

/**
 * Files on a phone: a sheet over the app, its list, and a file in the list's place. A tablet draws [FilesPane] beside its sidebar
 * instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(files: FilesModel, onClose: () -> Unit, seeInChat: ((KeptFile) -> Unit)? = null) {
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Box(Modifier.fillMaxSize().navigationBarsPadding()) { FilesHost(files, onClose, seeInChat) }
  }
}

/** Files beside a tablet's sidebar: the list, and the file beside it. Back closes it. */
@Composable
fun FilesPane(files: FilesModel, onClose: () -> Unit, seeInChat: ((KeptFile) -> Unit)? = null) {
  androidx.activity.compose.BackHandler(onBack = onClose)
  androidx.compose.runtime.CompositionLocalProvider(LocalDrawnBeside provides true) {
    Box(Modifier.fillMaxSize().background(Theme.background).statusBarsPadding().padding(top = 12.dp)) { FilesHost(files, onClose, seeInChat) }
  }
}

@Composable
private fun FilesHost(files: FilesModel, onClose: () -> Unit, seeInChat: ((KeptFile) -> Unit)?) {
  val state by files.state.collectAsState()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  LaunchedEffect(files) { files.refresh() }
  val act = FilesActions(
    close = onClose,
    view = files::view,
    pickTaf = files::pickTaf,
    open = { f -> scope.launch { files.open(f.id) } },
    closeFile = files::closeFile,
    download = { f -> scope.launch { files.bytes(f.id)?.let { if (saveToDownloads(context, f, it)) files.said(Words.Files.saved) } } },
    share = { f -> scope.launch { files.bytes(f.id)?.let { shareFile(context, f, it) } } },
    askDelete = files::askDelete,
    cancelDelete = files::cancelDelete,
    confirmDelete = { scope.launch { files.confirmDelete(Words.Files.deleted) } },
    seeInChat = seeInChat,
  )
  BoxWithConstraints(Modifier.fillMaxSize()) { FilesScreen(state, act, twoPane = maxWidth >= TWO_PANE) }
}

/** Where the list and the file sit side by side: a tablet, or a wide window. */
internal val TWO_PANE = 600.dp

/**
 * Files: the list, and the file open. [twoPane] puts the file beside the list ("Pick a file to see it here" until one is), else
 * the file takes the list's place, with Back.
 */
@Composable
fun FilesScreen(state: FilesState, act: FilesActions, twoPane: Boolean, today: LocalDate = LocalDate.now(zone)) {
  val open = state.open
  Column(Modifier.fillMaxSize().testTag(FILES_SCREEN)) {
    if (!twoPane && open != null) {
      BackBar(act.closeFile)
      FileScreen(open, state, act, today)
    } else {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(Words.Files.title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        if (!LocalDrawnBeside.current) TextButton(act.close) { Text(Words.Common.done, color = Theme.amberText) }
      }
      Row(Modifier.weight(1f).fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { FilesList(state, act, today) }
        if (twoPane) {
          Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.line))
          Box(Modifier.weight(1f).fillMaxHeight()) {
            if (open != null) FileScreen(open, state, act, today)
            else Text(Words.Files.pickOne, style = scaled(15, color = Theme.dim), modifier = Modifier.align(Alignment.Center).padding(24.dp))
          }
        }
      }
    }
  }
  state.asking?.let { file ->
    DriveAlert(
      onDismissRequest = act.cancelDelete,
      title = { Text(Words.Files.deleteQuestion(shownName(file))) },
      text = { Text(Words.Files.deleteBody) },
      confirmButton = { TextButton(act.confirmDelete, modifier = Modifier.confirms()) { Text(Words.Files.deleteNow, color = Theme.no) } },
      dismissButton = { TextButton(act.cancelDelete) { Text(Words.Common.cancel, color = Theme.ink) } },
    )
  }
}

@Composable
private fun FilesList(state: FilesState, act: FilesActions, today: LocalDate) {
  val context = LocalContext.current
  val all = state.files
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Segmented(FilesView.entries, state.view, { when (it) { FilesView.All -> Words.Files.all; FilesView.ByTaf -> Words.Files.byTaf; FilesView.ByRun -> Words.Files.byRun } }, act.view)
    val tafs = FileGroups.tafs(all.orEmpty())
    if (tafs.size > 1) {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilesChip(Words.Files.allTafs, state.taf == null) { act.pickTaf(null) }
        for ((id, name) in tafs) FilesChip(name, state.taf == id) { act.pickTaf(id) }
      }
    }
    state.line?.takeIf { state.open == null }?.let { Text(it, style = scaled(14, color = Theme.ok)) }
    state.error?.let { Text(it, style = scaled(14, color = Theme.no)) }
    val files = FileGroups.forTaf(all.orEmpty(), state.taf)
    when {
      all == null -> Text(Words.Common.loading, style = scaled(14, color = Theme.dim))
      files.isEmpty() -> Text(Words.Files.empty, style = scaled(15, color = Theme.soft), modifier = Modifier.testTag(FILES_EMPTY))
      state.view == FilesView.All -> for ((day, its) in FileGroups.byDay(files, zone)) {
        Heading(dayTitle(day, today))
        FileRows(its, state, act, today, context)
      }
      state.view == FilesView.ByTaf -> for (group in FileGroups.byTaf(files)) {
        Heading("${group.files.first().tafName.orEmpty()} · ${Words.Files.tafRow(group.files.size)}")
        FileRows(group.files, state, act, today, context)
      }
      else -> for (group in FileGroups.byRun(files)) {
        val first = group.files.first()
        Heading("${first.tafName.orEmpty()} · ${dayTitle(localDate(first.created), today)} · ${shortTime(first.created)}")
        FileRows(group.files, state, act, today, context)
      }
    }
    Text(Words.Files.keptLine, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(vertical = 8.dp))
  }
}

@Composable
private fun Heading(words: String) = Text(words.uppercase(), style = scaled(12, FontWeight.SemiBold, Theme.dim), modifier = Modifier.padding(top = 6.dp))

@Composable
private fun FilesChip(title: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.clip(CircleShape).background(if (selected) Theme.ink else Theme.panel).border(BorderStroke(1.dp, if (selected) Theme.ink else Theme.line), CircleShape)
      .tapping(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick).semantics { this.selected = selected }.padding(horizontal = 11.dp, vertical = 5.dp),
  ) { Text(title, style = scaled(13, color = if (selected) Theme.background else Theme.soft)) }
}

@Composable
private fun FileRows(files: List<KeptFile>, state: FilesState, act: FilesActions, today: LocalDate, context: Context) {
  Panel {
    files.forEachIndexed { i, file ->
      if (i > 0) HorizontalDivider(color = Theme.line)
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (state.open?.file?.id == file.id) Theme.raised else Theme.panel)
          .tapping { act.open(file) }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TypeTile(file)
        Column(Modifier.weight(1f)) {
          Text(shownName(file), style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 2)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOfNotNull(file.tafName, sizeWords(context, file)).joinToString(" · "), style = scaled(12, color = Theme.dim))
            Text(goesWords(file, today), style = scaled(12, color = Theme.amberText))
          }
        }
      }
    }
  }
}

/** One file: its preview as plain text, Download and Share, what it is and whose, Delete now apart at the end. */
@Composable
private fun FileScreen(detail: FileDetail, state: FilesState, act: FilesActions, today: LocalDate) {
  val file = detail.file
  val context = LocalContext.current
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(FILE_SCREEN), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      TypeTile(file)
      Text(shownName(file), style = scaled(19, FontWeight.Bold, Theme.ink))
    }
    // Plain text only, cleaned: never Markdown or HTML, whatever the file says it is.
    (detail.preview as? FilePreview.Text)?.let { preview ->
      Panel(Modifier.testTag(FILE_PREVIEW)) { Text(FileNames.preview(preview.text), style = scaled(13, color = Theme.ink, mono = true)) }
      // Cut at 4 KB: never let a start pass for the whole file.
      if (preview.truncated) Text(Words.Files.previewStart, style = scaled(12, color = Theme.dim))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FileButton(Words.Files.download, Theme.amber, Theme.amberInk, Modifier.weight(1f)) { act.download(file) }
      FileButton(Words.Files.share, Theme.panel, Theme.ink, Modifier.weight(1f)) { act.share(file) }
    }
    state.line?.let { Text(it, style = scaled(14, color = Theme.ok)) }
    Panel {
      Detail(Words.Files.madeBy, file.tafName.orEmpty())
      Detail(Words.Files.inRun, "${dayTitle(localDate(file.created), today)} · ${shortTime(file.created)}")
      Detail(Words.Files.type, FileNames.type(file))
      Detail(Words.Files.size, sizeWords(context, file))
      Detail(Words.Files.whoSees, Words.Files.everyone)
      Detail(Words.Files.goesAway, Words.Files.goesOn(shownDate(file.expires)), Theme.amberText)
    }
    act.seeInChat?.takeIf { file.chat != null }?.let { see -> FileButton("${Words.Files.seeInChat} ›", Theme.panel, Theme.ink, Modifier.fillMaxWidth()) { see(file) } }
    // It only asks; the question's Delete now is what deletes. Both are marked, so Coach driving the app never taps either.
    FileButton(Words.Files.deleteNow, Theme.panel, Theme.no, Modifier.fillMaxWidth().padding(top = 12.dp).confirms()) { act.askDelete(file) }
  }
}

@Composable
private fun Detail(label: String, value: String, tone: androidx.compose.ui.graphics.Color = Theme.ink) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = scaled(14, color = Theme.dim))
    Text(value, style = scaled(14, color = tone), modifier = Modifier.padding(start = 12.dp))
  }
}

@Composable
private fun FileButton(label: String, fill: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(fill).border(BorderStroke(1.dp, Theme.line), RoundedCornerShape(14.dp)).tapping(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { Text(label, style = scaled(16, FontWeight.SemiBold, ink)) }
}

/**
 * Download: the bytes into the phone's Downloads, through MediaStore (no permission from Android 10), under the file's cleaned
 * name. True once they're written.
 */
internal fun saveToDownloads(context: Context, file: KeptFile, bytes: ByteArray): Boolean = runCatching {
  val resolver = context.contentResolver
  val values = ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME, shownName(file))
    put(MediaStore.Downloads.MIME_TYPE, file.mediaType)
    put(MediaStore.Downloads.IS_PENDING, 1)
  }
  val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
  resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
  resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
  true
}.getOrDefault(false)

/**
 * Share: the bytes themselves, as a file in the app's cache, through the share sheet by the app's FileProvider. No link is made,
 * so nothing opens from outside the account.
 */
internal fun shareFile(context: Context, file: KeptFile, bytes: ByteArray) {
  val dir = File(context.cacheDir, "${bot.tafs.app.app.KEPT_FILES}/${file.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}").apply { mkdirs() }
  val out = File(dir, shownName(file).replace('/', '_')).apply { writeBytes(bytes) }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
  val send = Intent(Intent.ACTION_SEND).setType(file.mediaType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  // The grant reaches the app the person picks only through the clip.
  send.clipData = ClipData.newRawUri(shownName(file), uri)
  context.startActivity(Intent.createChooser(send, Words.Files.share).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal const val FILES_ACCOUNT_ROW = "files-account-row"
internal const val FILES_TAF_ROW = "files-taf-row"
internal const val FILES_CHAT = "files-chat"
internal const val FILES_SCREEN = "files-screen"
internal const val FILE_SCREEN = "file-screen"
internal const val FILE_PREVIEW = "file-preview"
internal const val FILES_EMPTY = "files-empty"
