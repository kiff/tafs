package bot.tafs.app.design

import bot.tafs.app.model.Words
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

/**
 * The tafs palette, taken from the mascot: a warm cream ground, deep ink, and the mascot's
 * orange → pink → blue as the brand. Status colours stay plain so they read at a glance: amber
 * for "needs you", green for done, coral for refused. The same values as iOS's Theme.
 */
object Theme {
  var preference by androidx.compose.runtime.mutableStateOf(bot.tafs.app.model.ThemePreference.System)

  fun choose(context: android.content.Context, choice: bot.tafs.app.model.ThemePreference) {
    context.getSharedPreferences("tafs.display", android.content.Context.MODE_PRIVATE).edit().putString("theme", choice.stored).apply()
    preference = choice
  }
  /**
   * Whether the phone is set to dark: [TafsTheme] follows it. Every colour below reads it, so the screens draw either way with no
   * change of their own. Dark is the owner-reviewed warm near-black, with brighter green, amber and red.
   */
  var dark by androidx.compose.runtime.mutableStateOf(false)

  private fun pick(light: Long, darkValue: Long): Color = Color(if (dark) darkValue else light)

  val background: Color get() = pick(0xFFFBF7EE, 0xFF141311)
  val panel: Color get() = pick(0xFFFFFFFF, 0xFF1F1E1B)
  val raised: Color get() = pick(0xFFF3ECDF, 0xFF2A2824)
  val ink: Color get() = pick(0xFF1B1726, 0xFFF2EFE9)
  val soft: Color get() = pick(0xFF4F4A5E, 0xFFA8A49C)

  /** Secondary text: 4.5:1 on the background and on panels (kiff/tafs#210), light or dark. */
  val dim: Color get() = pick(0xFF736D7F, 0xFF96918A)
  val line: Color get() = if (dark) Color(0xFFF2EFE9).copy(alpha = 0.12f) else Color(0xFF1B1726).copy(alpha = 0.10f)
  val amber = Color(0xFFFF8A1F)

  /** Amber as text: the amber itself is 2.2:1 on the light background, this 4.5:1 (kiff/tafs#210), and lighter on dark. */
  val amberText: Color get() = pick(0xFFB35500, 0xFFF0A45A)

  /** Text on a solid amber fill: white on light, the dark ground on dark (7.9:1). Never on the gradient: that's [onBrand]. */
  val amberInk: Color get() = pick(0xFFFFFFFF, 0xFF141311)

  /** Text on the brand gradient, which stays the same in dark: white, 4.5:1 or more on each of its stops. */
  val onBrand = Color.White
  val ok: Color get() = pick(0xFF198056, 0xFF4CC38A)
  val no: Color get() = pick(0xFFDB1F25, 0xFFFF8A73)

  val orange = Color(0xFFFF9A2E)
  val pink = Color(0xFFFF4F7B)
  val blue: Color get() = pick(0xFF2A64F5, 0xFF7FA2FF)
  val sky = Color(0xFF3EC2FF)

  /**
   * The mascot's sweep, for the main button and the brand. White text on it: each stop a shade
   * deeper than the mascot's, so the text is 4.5:1 all along it (kiff/tafs#210).
   */
  fun brand(width: Float, height: Float): Brush =
    Brush.linearGradient(BRAND_STOPS, start = Offset.Zero, end = Offset(width, height))

  /** The gradient's stops, the same light or dark. */
  val BRAND_STOPS = listOf(Color(0xFFB35C00), Color(0xFFE8003A), Color(0xFF2A64F5))

  /** Taf colours, from the mascot too: bright, and dark enough for white text, 4.5:1 or more. */
  val tafColors = listOf(
    Color(0xFFC25100), Color(0xFFE51255), Color(0xFF7B4DFF),
    Color(0xFF2A64F5), Color(0xFF0B7DA4), Color(0xFF128462),
  )

  fun tafColor(index: Int): Color = tafColors[((index % tafColors.size) + tafColors.size) % tafColors.size]
}

/** What this phone asks for before it signs, for "Approve once with …": "your fingerprint", "your screen lock". */
val LocalBiometry = androidx.compose.runtime.staticCompositionLocalOf { Words.Approval.Biometry.screenLock }

/** The brand's sweep over whatever this is drawn on, topLeading to bottomTrailing, as iOS's `Theme.brand`. */
fun Modifier.brandBackground(): Modifier = drawBehind { drawRect(Theme.brand(size.width, size.height)) }

/** The two families the screens draw in: iOS's rounded display face and its monospace. */
object Fonts {
  // SF Rounded on iOS; a rounded face is bundled later, this is the one place to swap it.
  val display: FontFamily = FontFamily.SansSerif
  val mono: FontFamily = FontFamily.Monospace
}

/** A text style as iOS's `scaledFont(size, weight, design)`: sp, so it follows the font size setting. */
fun scaled(
  size: Int,
  weight: FontWeight = FontWeight.Normal,
  color: Color = Theme.ink,
  display: Boolean = false,
  mono: Boolean = false,
  tracking: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
  fontSize = (size * BASE_TYPE).sp,
  fontWeight = weight,
  color = color,
  fontFamily = when {
    mono -> Fonts.mono
    display -> Fonts.display
    else -> null
  },
  letterSpacing = tracking,
)

/**
 * The base type: each size a screen names (iOS's point sizes) is drawn at this share of it, so the screens are as dense as
 * apps like LinkedIn. It sets the base sizes only: they are sp, so text still grows when the person raises their font size.
 */
const val BASE_TYPE = 0.9f

/**
 * Material's type, at the base sizes and with no added letter spacing: a Text without a style of its own (a button's label,
 * a field's text) matches the screens' own.
 */
private val iosTypography = Typography().run {
  fun TextStyle.ios(size: Int, line: Int) = copy(fontSize = (size * BASE_TYPE).sp, lineHeight = (line * BASE_TYPE).sp, letterSpacing = 0.sp)
  copy(
    displayLarge = displayLarge.ios(34, 41), displayMedium = displayMedium.ios(28, 34), displaySmall = displaySmall.ios(22, 28),
    headlineLarge = headlineLarge.ios(34, 41), headlineMedium = headlineMedium.ios(28, 34), headlineSmall = headlineSmall.ios(22, 28),
    titleLarge = titleLarge.ios(20, 25), titleMedium = titleMedium.ios(17, 22), titleSmall = titleSmall.ios(15, 20),
    bodyLarge = bodyLarge.ios(17, 22), bodyMedium = bodyMedium.ios(15, 20), bodySmall = bodySmall.ios(13, 18),
    labelLarge = labelLarge.ios(15, 20), labelMedium = labelMedium.ios(13, 18), labelSmall = labelSmall.ios(11, 13),
  )
}

@Composable
fun TafsTheme(content: @Composable () -> Unit) {
  // Light or dark, as the phone is set: every colour in Theme follows.
  val context = androidx.compose.ui.platform.LocalContext.current
  androidx.compose.runtime.remember(context) {
    Theme.preference = bot.tafs.app.model.ThemePreference.from(context.getSharedPreferences("tafs.display", android.content.Context.MODE_PRIVATE).getString("theme", "system"))
    true
  }
  val dark = Theme.preference.dark(isSystemInDarkTheme())
  if (Theme.dark != dark) Theme.dark = dark
  // The status and navigation bars' icons: dark on the light ground, light on the dark one.
  val view = androidx.compose.ui.platform.LocalView.current
  if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
    (view.context as? android.app.Activity)?.window?.let { window ->
      androidx.core.view.WindowCompat.getInsetsController(window, view).run {
        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
      }
    }
  }
  val colors = if (dark) {
    androidx.compose.material3.darkColorScheme(
      primary = Theme.amber,
      onPrimary = Theme.amberInk,
      background = Theme.background,
      surface = Theme.background,
      onSurface = Theme.ink,
      onBackground = Theme.ink,
      surfaceVariant = Theme.raised,
      onSurfaceVariant = Theme.soft,
      outline = Theme.dim,
    )
  } else {
    lightColorScheme(
      primary = Theme.amber,
      onPrimary = Theme.amberInk,
      background = Theme.background,
      surface = Theme.background,
      onSurface = Theme.ink,
    )
  }
  MaterialTheme(
    colorScheme = colors,
    typography = iosTypography,
    content = content,
  )
}
