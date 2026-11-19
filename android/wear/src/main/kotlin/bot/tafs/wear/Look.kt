package bot.tafs.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ButtonColors
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * The watch's one palette (docs/watch-wear.md, Version 2, Colour). Colour by meaning (W7, the owner's Galaxy Watch 5 test: "every
 * button is purple"): Send and the person's own words orange, Coach blue, what's held and waits for you amber, done and sent
 * green, delete, halt, refuse and sign out red. A button that means nothing in particular is grey. Nothing is Material's purple.
 */
internal object Look {
  /** Send, and the person's own words: the microphone, what's being recorded, Retry. */
  val send = Color(0xFFE8531D)
  val onSend = Color(0xFF1A0A04)

  /** Coach's words and marks: an ask at work, an answer, Hear, the unread dot. */
  val coach = Color(0xFF7FA8FF)

  /** Coach's ground, and the words on it. */
  val coachGround = Color(0xFF1E2231)
  val onCoachGround = Color(0xFFE6ECFF)
  /** The person's own words as a bubble in Coach's conversation (W4): a warm ground, from Send's orange. */
  val mineGround = Color(0xFF3A2A22)
  val onMineGround = Color(0xFFFFE6D6)

  /** Held, waits for you: Needs you, a held call, Approve on your phone, "Not sure it arrived". */
  val held = Color(0xFFFFB547)
  val onHeld = Color(0xFF1F1400)

  /** Done and sent: the check. */
  val done = Color(0xFF3FCF7E)
  val onDone = Color(0xFF04140B)

  /** Delete, halt, refuse and sign out; dark words on it, since white on this red reads at only 3:1. */
  val danger = Color(0xFFFF5A5F)
  val onDanger = Color(0xFF240A0C)

  /** A button that means nothing in particular, and the words on it. */
  val neutral = Color(0xFF26252E)
  val onNeutral = Color(0xFFF4F3F7)

  /** A neutral button on a card, which is itself [neutral]: a step lighter, so it shows. */
  val raised = Color(0xFF3A3944)

  /** Recording, as mockup v2.1 draws it: the Stop button and "● Recording". */
  val recording = Color(0xFFE5303A)

  /** The brand's three, for Start and the listening ring: tafs' own liveliness, never a meaning. */
  val brand = listOf(Color(0xFFB35C00), Color(0xFFE8003A), Color(0xFF2A64F5))
  val text = onNeutral
  val dim = Color(0xFFA8A8B0)
  val faint = Color(0xFF6E6E78)

  /** The disc behind a small microphone. */
  val surface = Color(0xFF1F1F24)
}

/**
 * The watch's colour scheme for Wear Material3, from [Look]: primary is Send's orange, the containers are the neutral grey, and
 * error is the red, so whatever is drawn in the theme's colours is the watch's and never Material's default purple.
 */
internal val WatchColors: ColorScheme = ColorScheme(
  primary = Look.send,
  primaryDim = Look.send,
  primaryContainer = Look.neutral,
  onPrimary = Look.onSend,
  onPrimaryContainer = Look.onNeutral,
  secondary = Look.coach,
  secondaryDim = Look.coach,
  secondaryContainer = Look.coachGround,
  onSecondary = Look.coachGround,
  onSecondaryContainer = Look.onCoachGround,
  tertiary = Look.held,
  tertiaryDim = Look.held,
  tertiaryContainer = Look.neutral,
  onTertiary = Look.onHeld,
  onTertiaryContainer = Look.held,
  surfaceContainerLow = Look.surface,
  surfaceContainer = Look.neutral,
  surfaceContainerHigh = Look.raised,
  onSurface = Look.onNeutral,
  onSurfaceVariant = Look.dim,
  outline = Look.faint,
  outlineVariant = Look.raised,
  background = Color.Black,
  onBackground = Look.text,
  error = Look.danger,
  errorDim = Look.danger,
  errorContainer = Look.neutral,
  onError = Look.onDanger,
  onErrorContainer = Look.danger,
)

/** The watch's theme: Wear Material3 in [WatchColors]. */
@Composable
internal fun WatchTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = WatchColors, content = content)

/** A button that means nothing in particular: grey. [onCard] is a step lighter, for a button on a card. */
@Composable
internal fun neutralButton(onCard: Boolean = false): ButtonColors = ButtonDefaults.buttonColors(
  containerColor = if (onCard) Look.raised else Look.neutral, contentColor = Look.onNeutral,
  secondaryContentColor = Look.dim, iconColor = Look.onNeutral,
)

/** Held, waits for you: amber, with dark words. */
@Composable
internal fun heldButton(): ButtonColors = ButtonDefaults.buttonColors(containerColor = Look.held, contentColor = Look.onHeld, iconColor = Look.onHeld)

/** The last step of a halt or a sign out: red, with dark words. */
@Composable
internal fun dangerButton(): ButtonColors = ButtonDefaults.buttonColors(containerColor = Look.danger, contentColor = Look.onDanger, iconColor = Look.onDanger)

/** A door to delete, halt, refuse or sign out, asked again after: grey, with red words. */
@Composable
internal fun dangerQuietButton(onCard: Boolean = false): ButtonColors = ButtonDefaults.buttonColors(
  containerColor = if (onCard) Look.raised else Look.neutral, contentColor = Look.danger, iconColor = Look.danger,
)
