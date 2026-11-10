package bot.tafs.app.model

/**
 * What the lines call this device: "phone", or "tablet" on a large screen (`TafsApplication` sets it at start, from the
 * screen's smallest width, as Android's `sw600dp` does). Unit tests run as a phone.
 */
object ThisDevice {
  @Volatile var tablet: Boolean = false

  /** The device's word in the lines' language, for their `{device}`. */
  val word: String get() = if (tablet) Words.Device.tablet else Words.Device.phone
}
