package bot.tafs.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Right to left (docs/android-app.md): every drawable that points a way (a chevron, Back, next, previous) mirrors itself, so it
 * points the way the reader reads; and no view draws a Material arrow that doesn't (`Icons.AutoMirrored` is the one that does).
 * A `›` written as text is mirrored by Unicode itself (Bidi_Mirrored) in a right-to-left line, so it needs nothing.
 */
class RtlTest {
  private val res = File("src/main/res")
  private val sources = File("src/main/kotlin")

  @Test fun everyDirectionalDrawableMirrors() {
    val directional = res.walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("drawable") &&
      Regex("(^|_)(left|right|back|forward|next|previous|prev)(_|$)", RegexOption.IGNORE_CASE).containsMatchIn(it.nameWithoutExtension) }.toList()
    assertTrue("the chevrons are found", directional.any { it.name == "ic_chevron_left.xml" } && directional.any { it.name == "ic_chevron_right.xml" })
    val fixed = directional.filter { "android:autoMirrored=\"true\"" !in it.readText() }.map { it.name }
    assertTrue("These point a way but don't mirror in right to left: $fixed", fixed.isEmpty())
  }

  @Test fun noFixedMaterialArrow() {
    val arrow = Regex("""Icons\.(Filled|Outlined|Rounded|Sharp|TwoTone|Default)\.(KeyboardArrow(Left|Right)|Arrow(Back|Forward)(Ios)?|Chevron(Left|Right)|Navigate(Next|Before)|Send|Reply|Undo|Redo|Logout|Login|OpenInNew|List)\b""")
    val found = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
      f.readLines().mapIndexedNotNull { i, line -> if (arrow.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
    }.toList()
    assertTrue("Use Icons.AutoMirrored for these: $found", found.isEmpty())
  }
}
