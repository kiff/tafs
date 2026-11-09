package bot.tafs.app

import bot.tafs.app.model.Res
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [Res]'s words in unit tests: the strings files of one `res/values` folder, read as aapt2 reads them (quotes kept or
 * dropped, escapes, spaces collapsed), with ids matched to names through the generated `R`. Named by the test service file
 * `META-INF/services/bot.tafs.app.model.Res$Source`, so plain JVM tests read the exact English words.
 */
class XmlWords(folder: String = "values") : Res.Source {
  private val strings = mutableMapOf<String, String>()
  private val plurals = mutableMapOf<String, Map<String, String>>()

  init {
    val dir = File(RES, folder)
    dir.listFiles { f -> f.isFile && f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.forEach { read(it) }
  }

  val stringNames: Set<String> get() = strings.keys
  val pluralNames: Set<String> get() = plurals.keys

  fun stringNamed(name: String): String? = strings[name]

  fun pluralNamed(name: String): Map<String, String>? = plurals[name]

  private fun read(file: File) {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val nodes = doc.documentElement.childNodes
    for (i in 0 until nodes.length) {
      val e = nodes.item(i) as? Element ?: continue
      val name = e.getAttribute("name")
      when (e.tagName) {
        "string" -> {
          require(name !in strings) { "$name is in two strings files" }
          strings[name] = aapt(e.textContent)
        }
        "plurals" -> {
          require(name !in plurals) { "$name is in two strings files" }
          val items = e.getElementsByTagName("item")
          plurals[name] = (0 until items.length).associate { j ->
            val item = items.item(j) as Element
            item.getAttribute("quantity") to aapt(item.textContent)
          }
        }
      }
    }
  }

  override fun string(id: Int): String {
    val name = STRING_NAMES[id] ?: error("No R.string with id $id")
    return strings[name] ?: error("R.string.$name isn't in res/values")
  }

  override fun plural(id: Int, count: Int): String {
    val name = PLURAL_NAMES[id] ?: error("No R.plurals with id $id")
    val forms = plurals[name] ?: error("R.plurals.$name isn't in res/values")
    // English: "one" for exactly 1, else "other".
    return forms[if (count == 1) "one" else "other"] ?: forms["other"] ?: error("R.plurals.$name has no other")
  }

  override val locale: Locale = Locale.ROOT

  companion object {
    /** The app's resources, from the module's folder, where Gradle runs unit tests. */
    val RES = File("src/main/res")

    private fun names(kind: String): Map<Int, String> =
      runCatching { Class.forName("bot.tafs.app.R\$$kind") }.getOrNull()?.fields.orEmpty()
        .filter { it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    private val STRING_NAMES by lazy { names("string") }
    private val PLURAL_NAMES by lazy { names("plurals") }

    /** A resource string's text as aapt2 compiles it: escapes, quoted runs kept as written, other runs of spaces as one. */
    fun aapt(raw: String): String {
      val out = StringBuilder()
      var quoted = false
      var pendingSpace = false
      var i = 0
      while (i < raw.length) {
        val c = raw[i]
        when {
          c == '\\' && i + 1 < raw.length -> {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            val n = raw[i + 1]
            when (n) {
              'n' -> out.append('\n')
              't' -> out.append('\t')
              'u' -> { out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
              else -> out.append(n)
            }
            i += 2
            continue
          }
          c == '"' -> { if (pendingSpace && out.isNotEmpty()) out.append(' '); pendingSpace = false; quoted = !quoted }
          // Only ASCII spaces collapse: a no-break space is kept, as aapt2 keeps it.
          !quoted && (c == ' ' || c == '\t' || c == '\n' || c == '\r') -> pendingSpace = true
          else -> {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(c)
          }
        }
        i++
      }
      return out.toString()
    }
  }
}
