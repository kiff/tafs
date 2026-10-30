package bot.tafs.app.views

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.files.PhotoPrep
import bot.tafs.app.model.AttachmentRef
import bot.tafs.app.model.FilePrep
import bot.tafs.app.model.FileProblem
import bot.tafs.app.model.PendingFile
import bot.tafs.app.model.PreparedFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Files a person attaches to a message (docs/attachments.md): the paperclip beside the message box, the tray above it, and
// the files in the thread, opened full size.

/** What the chat asks of the model for files: each by the chat's id. */
class FileActions(
  /** Adds a file made ready to the chat's next message, and uploads it. */
  val attach: suspend (PreparedFile, String) -> Unit = { _, _ -> },
  /** A file that can't be attached: said in the tray. */
  val refuse: (String, String) -> Unit = { _, _ -> },
  /** Takes a file out of the tray, by its id. */
  val remove: (String, String) -> Unit = { _, _ -> },
  /** A sent file, downloaded to open full size; null when it couldn't be. */
  val open: suspend (AttachmentRef, String) -> File? = { _, _ -> null },
)

/** The paperclip: take a photo, choose photos, or choose a file. Each file goes up as soon as it's picked, and waits in the tray. */
@Composable
fun AttachButton(tafId: String, state: AppState, files: FileActions) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var menu by remember { mutableStateOf(false) }
  val room = (FilePrep.PER_MESSAGE - state.files(tafId).size).coerceAtLeast(0)

  /** Reads and readies a file off the main thread, then uploads it; one that can't go is said in the tray. */
  fun add(prepare: () -> PreparedFile) {
    scope.launch {
      val file = try {
        withContext(Dispatchers.IO) { prepare() }
      } catch (e: FileProblem) {
        files.refuse(e.message ?: "That file can't be attached.", tafId)
        return@launch
      } catch (e: Exception) {
        files.refuse("That file couldn't be read.", tafId)
        return@launch
      }
      files.attach(file, tafId)
    }
  }

  val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(FilePrep.PER_MESSAGE)) { uris ->
    uris.take(room).forEachIndexed { n, uri -> add { PhotoPrep.read(context, uri, named = "Photo ${n + 1}") } }
  }
  val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    uris.take(room).forEach { uri -> add { PhotoPrep.read(context, uri) } }
  }
  // The camera writes its photo to the app's own cache; it's redrawn, then that copy is removed.
  var shot by remember { mutableStateOf<File?>(null) }
  val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { took ->
    val file = shot ?: return@rememberLauncherForActivityResult
    shot = null
    if (!took) {
      file.delete()
      return@rememberLauncherForActivityResult
    }
    add {
      try {
        PhotoPrep.photo(file.readBytes(), "Photo")
      } finally {
        file.delete()
      }
    }
  }
  val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

  Box {
    Box(
      Modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(Theme.panel)
        .border(BorderStroke(1.dp, Theme.line), CircleShape)
        .alpha(if (room == 0) 0.4f else 1f)
        .clickable(enabled = room > 0) { menu = true }
        .semantics {
          contentDescription = if (room == 0) "Attach: ${FilePrep.PER_MESSAGE} files is the most a message carries" else "Attach a photo or file"
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(R.drawable.ic_paperclip), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
      if (hasCamera) DropdownMenuItem(text = { Text("Take photo") }, onClick = {
        menu = false
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        shot = file
        try {
          camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        } catch (_: ActivityNotFoundException) {
          shot = null
          files.refuse("There's no camera app on this phone to take a photo with.", tafId)
        }
      })
      DropdownMenuItem(text = { Text("Choose photos") }, onClick = {
        menu = false
        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      })
      DropdownMenuItem(text = { Text("Choose file") }, onClick = {
        menu = false
        documents.launch(FilePrep.PICKABLE)
      })
    }
  }
}

/** The files waiting for the next message, above the box: each with a remove control, going up, ready, or saying why it can't go. */
@Composable
fun FileTray(tafId: String, state: AppState, files: FileActions) {
  val pending = state.files(tafId)
  val notice = state.fileNotices[tafId]
  if (pending.isEmpty() && notice == null) return
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (pending.isNotEmpty()) {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pending.forEach { file -> FileChip(file) { files.remove(file.id, tafId) } }
      }
    }
    notice?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/** One file in the tray. */
@Composable
private fun FileChip(file: PendingFile, remove: () -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Row(
    Modifier.clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape).padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FileIcon(file.mediaType, file.thumbnail)
    Column(Modifier.widthIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(file.name, style = scaled(13, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
      when (val s = file.state) {
        PendingFile.State.Uploading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          CircularProgressIndicator(Modifier.size(10.dp), color = Theme.dim, strokeWidth = 1.5.dp)
          Text("Uploading", style = scaled(12, color = Theme.dim))
        }
        is PendingFile.State.Ready -> Text("Ready", style = scaled(12, color = Theme.dim))
        is PendingFile.State.Failed -> Text(s.why, style = scaled(12, color = Theme.no), maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
    Box(
      Modifier.size(28.dp).clip(CircleShape).clickable(onClick = remove).semantics { contentDescription = "Remove ${file.name}" },
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = Theme.dim, modifier = Modifier.size(12.dp))
    }
  }
}

/** A file's picture: a photo's thumbnail, else an icon for its kind. */
@Composable
private fun FileIcon(mediaType: String, thumbnail: ByteArray? = null) {
  val shape = RoundedCornerShape(8.dp)
  val image = remember(thumbnail) { thumbnail?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
  if (image != null) {
    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(shape))
  } else {
    Box(Modifier.size(36.dp).clip(shape).background(Theme.line.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
      val icon = when (FilePrep.icon(mediaType)) {
        FilePrep.Icon.photo -> R.drawable.ic_photo
        FilePrep.Icon.table -> R.drawable.ic_table
        FilePrep.Icon.pdf, FilePrep.Icon.text -> R.drawable.ic_doc
      }
      Icon(painterResource(icon), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(18.dp))
    }
  }
}

/** The files sent with a message, in the thread: each opens full size, in the phone's own app for its kind. */
@Composable
fun MessageFiles(files: List<AttachmentRef>, tafId: String, actions: FileActions) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var opening by remember { mutableStateOf<String?>(null) }
  Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    files.forEach { file ->
      val shape = RoundedCornerShape(12.dp)
      Row(
        Modifier
          .clip(shape)
          .background(Theme.panel)
          .border(BorderStroke(1.dp, Theme.line), shape)
          .clickable(enabled = opening == null) {
            opening = file.id
            scope.launch {
              val copy = actions.open(file, tafId)
              opening = null
              if (copy != null && !openWith(context, copy, file.mediaType)) {
                actions.refuse("No app on this phone opens ${file.name}.", tafId)
              }
            }
          }
          .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
          .semantics { contentDescription = "Open ${file.name}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FileIcon(file.mediaType)
        Column(Modifier.widthIn(max = 200.dp)) {
          Text(file.name, style = scaled(13, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(if (opening == file.id) "Opening…" else FilePrep.sizeWords(file.size), style = scaled(12, color = Theme.dim))
        }
      }
    }
  }
}

/** Hands the downloaded copy to the phone's app for its kind, read-only. False when no app opens it. */
private fun openWith(context: Context, file: File, mediaType: String): Boolean {
  val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
  val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mediaType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  return try {
    context.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
  } catch (_: ActivityNotFoundException) {
    false
  }
}
