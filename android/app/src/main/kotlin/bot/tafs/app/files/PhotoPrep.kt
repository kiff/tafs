package bot.tafs.app.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import bot.tafs.app.model.FilePrep
import bot.tafs.app.model.FileProblem
import bot.tafs.app.model.PreparedFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Making a picked file ready to attach, on the phone (docs/attachments.md). Every image is redrawn as a JPEG, its long edge
 * at most [FilePrep.LONG_EDGE] pixels, on white so a transparent PNG isn't black: only its pixels are kept, so it carries no
 * location or other metadata. Other files go as they are, checked against their limit first.
 */
object PhotoPrep {
  /**
   * A file picked from the phone: a photo redrawn, anything else as it is. Refused with why when it can't be attached. [named]
   * names it in place of what the phone calls it: the photo picker's names are its own ids, so a chosen photo is "Photo 1".
   */
  fun read(context: Context, uri: Uri, named: String? = null): PreparedFile {
    val resolver = context.contentResolver
    var name = named ?: "File"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        if (named == null) c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
        if (!c.isNull(1)) size = c.getLong(1)
      }
    }
    val type = FilePrep.mediaType(resolver.getType(uri), name)
    // Over every limit: refused before it's read into memory.
    if (type != null && !FilePrep.isImage(type) && size > FilePrep.LARGEST) {
      throw FileProblem("$name is ${FilePrep.megabytes(size)}; the most is ${FilePrep.megabytes(FilePrep.LARGEST)}.")
    }
    val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileProblem("$name couldn't be read.")
    return if (type != null && FilePrep.isImage(type)) photo(data, name) else FilePrep.file(name, data, type)
  }

  /** A photo, any format the phone reads (HEIC included), turned the way it was taken: as a JPEG, with a thumbnail for the tray. */
  fun photo(data: ByteArray, name: String): PreparedFile {
    val bitmap = try {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
        val (w, h) = FilePrep.scaledSize(info.size.width, info.size.height, FilePrep.LONG_EDGE)
        decoder.setTargetSize(w, h)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (e: Exception) {
      throw FileProblem("$name couldn't be read as a photo.")
    }
    val named = FilePrep.photoName(name)
    val jpeg = jpeg(onWhite(bitmap, bitmap.width, bitmap.height), 85)
    FilePrep.check(named, jpeg.size.toLong(), "image/jpeg")
    val (tw, th) = FilePrep.scaledSize(bitmap.width, bitmap.height, 160)
    return PreparedFile(named, "image/jpeg", jpeg, thumbnail = jpeg(onWhite(bitmap, tw, th), 70))
  }

  /** The image drawn again at [w]×[h] on white: always a new bitmap, so nothing but its pixels goes with it. */
  private fun onWhite(image: Bitmap, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
      drawColor(Color.WHITE)
      drawBitmap(Bitmap.createScaledBitmap(image, w, h, true), 0f, 0f, null)
    }
    return out
  }

  private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
