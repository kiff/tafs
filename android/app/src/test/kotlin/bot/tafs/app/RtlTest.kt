package bot.tafs.app

import bot.tafs.app.design.Directions
import java.io.File
import org.junit.Assert.assertEquals
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

  @Test fun whatIsDrawnOrDraggedByHandMirrors() {
    // A job's colour edge on the start side: the left, or the right of a 300-wide row in right to left.
    assertEquals(0f, Directions.startEdgeX(300f, 4f, rtl = false))
    assertEquals(296f, Directions.startEdgeX(300f, 4f, rtl = true))
    // The Card's ring at the top end corner.
    assertEquals(270f, Directions.fromEndX(300f, 30f, rtl = false))
    assertEquals(30f, Directions.fromEndX(300f, 30f, rtl = true))
    // Coach's panel, on the end edge: dragging its handle towards the start widens it, whichever way that is on screen.
    assertEquals(-12f, Directions.towardsEnd(-12f, rtl = false))
    assertEquals(-12f, Directions.towardsEnd(12f, rtl = true))
    // And it slides in from off the end edge: from the right, or from the left in right to left.
    assertEquals(800, Directions.offEndX(800, rtl = false))
    assertEquals(-800, Directions.offEndX(800, rtl = true))
  }

  /** A slide's or a drag's pixels don't mirror, so each goes through [Directions]: a bare `{ it }` slides in from the right always. */
  @Test fun noSlideOrDragFixedToTheRight() {
    val fixed = Regex("""slide(In|Out)Horizontally(\([^)]*\))?\s*\{\s*(it|-it)\s*\}|\{\s*_,\s*amount\s*->\s*drag\s*\+=\s*amount\s*\}""")
    val found = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
      f.readLines().mapIndexedNotNull { i, line -> if (fixed.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
    }.toList()
    assertTrue("Use Directions.offEndX / towardsEnd for these: $found", found.isEmpty())
  }

  @Test fun noFixedMaterialArrow() {
    val arrow = Regex("""Icons\.(Filled|Outlined|Rounded|Sharp|TwoTone|Default)\.(KeyboardArrow(Left|Right)|Arrow(Back|Forward)(Ios)?|Chevron(Left|Right)|Navigate(Next|Before)|Send|Reply|Undo|Redo|Logout|Login|OpenInNew|List)\b""")
    val found = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
      f.readLines().mapIndexedNotNull { i, line -> if (arrow.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
    }.toList()
    assertTrue("Use Icons.AutoMirrored for these: $found", found.isEmpty())
  }
}
