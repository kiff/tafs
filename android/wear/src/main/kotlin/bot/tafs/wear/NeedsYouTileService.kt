package bot.tafs.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * The tile (docs/watch-wear.md): Needs you's count, as the watch last read it from the phone, and nothing else. It shows the
 * count only while the watch has a screen lock and is unlocked; locked, off the wrist or with no lock, it shows tafs's name and
 * the count is forgotten. Tapping it opens the watch app, which reads the phone again; its "Ask tafs" opens it listening.
 */
class NeedsYouTileService : TileService() {
  private val count by lazy { TileCount(EncryptedTileStore.of(this)) }

  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
    val shown = count.shown(unlocked = keyguard.isDeviceSecure && !keyguard.isDeviceLocked)
    val tile = TileBuilders.Tile.Builder()
      .setResourcesVersion(RESOURCES)
      // Read again by the watch app; the tile only asks for itself now and then.
      .setFreshnessIntervalMillis(FRESHNESS_MS)
      .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(shown)))
      .build()
    return CallbackToFutureAdapter.getFuture { it.set(tile) }
  }

  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
    CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build()) }

  private fun layout(shown: TileCount.Shown?): LayoutElementBuilders.LayoutElement {
    val open = ActionBuilders.LaunchAction.Builder()
      .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WatchActivity::class.java.name).build())
      .build()
    val column = LayoutElementBuilders.Column.Builder()
      .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
          .setClickable(ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(open).build())
          .build(),
      )
    if (shown != null) {
      column.addContent(text(Res.number(shown.count), 40f))
      column.addContent(text(Words.NeedsYou.title, 16f))
    } else {
      column.addContent(text(getString(R.string.app_name), 20f))
    }
    // Ask tafs, one tap from the watch face: the app opens listening (it asks for the watch's lock first, as any ask does).
    val listen = ActionBuilders.LaunchAction.Builder()
      .setAndroidActivity(
        ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WatchActivity::class.java.name)
          .addKeyToExtraMapping(WatchActivity.EXTRA_LISTEN, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
          .build(),
      )
      .build()
    column.addContent(
      LayoutElementBuilders.Box.Builder()
        .setModifiers(
          ModifiersBuilders.Modifiers.Builder()
            .setClickable(ModifiersBuilders.Clickable.Builder().setId("listen").setOnClick(listen).build())
            .setPadding(ModifiersBuilders.Padding.Builder().setTop(DimensionBuilders.dp(10f)).setBottom(DimensionBuilders.dp(4f)).build())
            .build(),
        )
        .addContent(text(Words.Watch.ask, 16f, AMBER))
        .build(),
    )
    return LayoutElementBuilders.Box.Builder()
      .setWidth(DimensionBuilders.expand())
      .setHeight(DimensionBuilders.expand())
      .addContent(column.build())
      .build()
  }

  private fun text(words: String, size: Float, color: Int? = null) = LayoutElementBuilders.Text.Builder()
    .setText(words)
    .setFontStyle(
      LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size))
        .apply { if (color != null) setColor(androidx.wear.protolayout.ColorBuilders.argb(color)) }
        .build(),
    )
    .build()

  companion object {
    private const val RESOURCES = "1"
    private const val AMBER = 0xFFFF8A1F.toInt()
    private const val FRESHNESS_MS = 15 * 60 * 1000L

    /** Asks the system to draw the tile again: after the watch app read the phone, or the phone signed out. */
    fun update(context: android.content.Context) {
      runCatching { getUpdater(context).requestUpdate(NeedsYouTileService::class.java) }
    }
  }
}
