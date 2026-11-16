package bot.tafs.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * The watch app's one screen, for now only its name: Needs you, Your tafs and Talk to Coach come with the phone's relay
 * (docs/watch-wear.md).
 */
class WatchActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.app_name))
        }
      }
    }
  }
}
